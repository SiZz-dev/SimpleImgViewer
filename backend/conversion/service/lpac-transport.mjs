import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {DatabaseSync} from 'node:sqlite';
import {privateRoot,regular,digest} from '../../media/util/files.mjs';
import {ApiError,requireThat,integer} from '../../system/util/errors.mjs';
import {LpacWorkRecovery} from './lpac-work-recovery.mjs';

// Internal transport injection only. YAML selection awaits concurrent-load and
// supported-OS acceptance; no HTTP maintenance endpoint is exposed.
export class LpacTransport {
  constructor({root,launcher,sha256,processing,maxEntries=40,recovery={}}){this.root=path.resolve(root);this.launcher=launcher;this.sha256=sha256;this.processing=processing;this.maxEntries=maxEntries;this.children=new Set();this.operations=new Set();this.reservations=new Set();this.gate=Promise.resolve();this.closed=false;this.invalid=false;this.workRecovery=new LpacWorkRecovery(this.root,args=>this.control(args),recovery);}
  async verifyLauncher(){
    requireThat(!this.invalid,503,'LPAC_LAUNCHER_CHANGED');
    try{await regular(this.launcher,16777216);requireThat(await digest(this.launcher)===this.sha256,503,'LPAC_LAUNCHER_CHANGED');}catch{this.invalid=true;throw new ApiError(503,'LPAC_LAUNCHER_CHANGED');}
  }
  async init(){
    requireThat(process.platform==='win32'&&process.arch==='x64'&&path.isAbsolute(this.launcher)&&/^[a-f0-9]{64}$/.test(this.sha256||'')&&integer(this.maxEntries,3,200),503,'LPAC_CONFIGURATION_REQUIRED');
    await this.verifyLauncher();await privateRoot(this.root);
    // Do not open an existing link or unexpected SQLite sidecar.
    for(const name of ['owner.sqlite','owner.sqlite-journal','owner.sqlite-wal','owner.sqlite-shm']){
      const file=path.join(this.root,name);try{if(name!=='owner.sqlite')throw await fs.lstat(file);const stat=await regular(file,262144);requireThat(stat.nlink===1,503,'LPAC_POOL_UNSAFE');}catch(error){if(error.code!=='ENOENT')throw new ApiError(503,'LPAC_POOL_UNSAFE');}
    }
    let owner;
    try{owner=new DatabaseSync(path.join(this.root,'owner.sqlite'),{timeout:0,allowExtension:false});owner.exec('CREATE TABLE IF NOT EXISTS ownership (id INTEGER PRIMARY KEY CHECK(id=1)); INSERT OR IGNORE INTO ownership VALUES (1); BEGIN EXCLUSIVE');this.owner=owner;this.recoveryReport=await this.recover();this.startRecovery();return this;}catch(error){owner?.close();this.owner=null;throw error;}
  }
  async control(args){await this.verifyLauncher();try{const result=await promisify(execFile)(this.launcher,args,{windowsHide:true,shell:false,timeout:30000,maxBuffer:8192,env:{SystemRoot:process.env.SystemRoot||'',WINDIR:process.env.WINDIR||''}});return {code:0,stdout:result.stdout};}catch(error){if(error.code===25)return {code:25,stdout:error.stdout};throw new ApiError(503,'LPAC_RECOVERY_REJECTED');}}
  startRecovery(){const policy=this.workRecovery.policy;if(!policy.enabled||!policy.intervalMs)return;this.recoveryTimer=setInterval(()=>{if(this.closed||this.recovering||this.operations.size||this.reservations.size)return;this.recover().then(report=>{this.recoveryReport=report;this.recoveryError=null;},error=>{this.recoveryError=error.code||'LPAC_RECOVERY_FAILED';});},policy.intervalMs);this.recoveryTimer.unref();}
  async recover(){requireThat(!this.recovering,409,'LPAC_RECOVERY_BUSY');this.recovering=true;const previous=this.gate;let release;this.gate=new Promise(resolve=>{release=resolve;});await previous;try{requireThat(this.owner&&!this.closed,503,'PROCESSING_CLOSING');requireThat(!this.operations.size&&!this.reservations.size,409,'LPAC_RECOVERY_BUSY');return await this.workRecovery.recover();}finally{this.recovering=false;release();}}
  async reserve(){
    const previous=this.gate;let release;this.gate=new Promise(resolve=>{release=resolve;});await previous;
    try{
      requireThat(!this.closed&&this.owner,503,'PROCESSING_CLOSING');
      const names=await this.workRecovery.list(),pendingRoots=[...this.reservations].filter(id=>!names.includes(id)).length;
      requireThat(names.filter(name=>!['owner.sqlite','.imagegate-lpac-state'].includes(name)).length+pendingRoots+3<=this.maxEntries,507,'LPAC_POOL_FULL');
      const disk=await fs.statfs(this.root,{bigint:true});requireThat(disk.bavail*disk.bsize>=805306368n,507,'LPAC_DISK_FULL');
      const id=randomUUID(),request=path.join(this.root,id+'.request'),root=path.join(this.root,id);
      await this.workRecovery.allocate(id);
      const handle=await fs.open(request,'wx',0o600);await handle.close();this.reservations.add(id);return {request,root};
    }finally{release();}
  }
  async writeRequest(binding,bytes){await this.workRecovery.prepare(binding,bytes);}
  async complete(binding){await this.workRecovery.complete(binding,()=>this.removeCurrent(binding));}
  run(job,{runtime}){
    requireThat(!this.closed&&this.owner,503,'PROCESSING_CLOSING');
    const operation=this.execute(job,runtime);this.operations.add(operation);operation.then(()=>this.operations.delete(operation),()=>this.operations.delete(operation));return operation;
  }
  spawn(request,{timeoutMs=this.processing.timeoutMs,channelBytes=8192,raw=false}={}){
    return new Promise((resolve,reject)=>{
      const child=spawn(this.launcher,[request],{windowsHide:true,shell:false,stdio:['pipe','pipe','pipe'],env:{SystemRoot:process.env.SystemRoot||'',WINDIR:process.env.WINDIR||''}});
      this.children.add(child);child.stdin.on('error',()=>{});let output='',errors=0,failed=false,oversize=false,timedOut=false,killTimer;
      const cancel=()=>{child.stdin.destroy();killTimer??=setTimeout(()=>child.kill('SIGKILL'),5000);killTimer.unref();};
      child.lpacCancel=cancel;
      const timer=setTimeout(()=>{timedOut=true;cancel();},timeoutMs+10000);timer.unref();
      child.stdout.on('data',bytes=>{if(Buffer.byteLength(output)+bytes.length>channelBytes){oversize=true;cancel();}else output+=bytes.toString();});
      child.stderr.on('data',bytes=>{errors+=bytes.length;if(errors>channelBytes){oversize=true;cancel();}});
      child.once('error',()=>{failed=true;});
      child.once('close',code=>{clearTimeout(timer);clearTimeout(killTimer);this.children.delete(child);let facts;try{facts=JSON.parse(output);}catch{}
        if(timedOut)return reject(new ApiError(504,'CONVERSION_TIMEOUT'));
        if(code===124)return reject(new ApiError(504,'CONVERSION_TIMEOUT'));
        if(code===125||failed)return reject(new ApiError(503,'LPAC_EXECUTION_REJECTED'));
        if(code!==0||oversize||!raw&&!facts?.ok)return reject(new ApiError(422,'CONVERSION_UNSUPPORTED_PROFILE'));
        resolve(raw?output:facts);
      });
    });
  }
  async removeCurrent(binding){
    const files=[['bin',['all-app.check','restricted-app.check']],['input',binding.inputs.map(input=>input.name)],['out',[...(binding.kind==='image'?['job.json']:[]),...binding.outputs.map(output=>output.name)]]];
    requireThat(JSON.stringify((await fs.readdir(binding.root)).sort())===JSON.stringify(['bin','input','out']),503,'LPAC_RESIDUE_UNKNOWN');
    for(const [directory,names] of files){const folder=path.join(binding.root,directory);requireThat(path.normalize(await fs.realpath(folder))===folder,503,'LPAC_RESIDUE_UNKNOWN');requireThat(JSON.stringify((await fs.readdir(folder)).sort())===JSON.stringify([...names].sort()),503,'LPAC_RESIDUE_UNKNOWN');for(const name of names){const stat=await regular(path.join(folder,name),binding.kind==='media'?268435456:104857600);requireThat(stat.nlink===1,503,'LPAC_RESIDUE_UNKNOWN');}}
    for(const [directory,names] of files){const folder=path.join(binding.root,directory);for(const name of names)await fs.unlink(path.join(folder,name));await fs.rmdir(folder);}await fs.rmdir(binding.root);
  }
  async close(){this.closed=true;clearInterval(this.recoveryTimer);for(const child of this.children)child.lpacCancel();await Promise.allSettled([...this.operations]);await this.gate;this.owner?.close();this.owner=null;}
}
