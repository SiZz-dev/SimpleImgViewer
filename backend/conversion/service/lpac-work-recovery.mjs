import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {requireThat,integer,uuidPattern} from '../../system/util/errors.mjs';
import {safeRuntimePath} from '../util/runtime-inventory.mjs';

const marker=Buffer.from('RETIRED-LPAC-REQUEST/1\n'),hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const exists=async file=>{try{await fs.lstat(file);return true;}catch(error){if(error.code==='ENOENT')return false;throw error;}};
function check(value){requireThat(value,503,'LPAC_RECOVERY_RECORD_UNSAFE');}
function keys(value,allowed){check(value&&Object.getPrototypeOf(value)===Object.prototype&&Object.keys(value).every(key=>allowed.includes(key)));}
export function residueLayout(binding){
 if(binding.kind==='image')return {type:binding.document.operation==='preview'?'image-preview':'image-export',stamps:binding.inputs.length-1};
 return {type:['version','license'].includes(binding.operation)?'media-empty':binding.operation==='probe-output'?'media-result':['video','audio'].includes(binding.operation)?'media-convert':'media-source',stamps:0};
}
export function residueFiles(layout){
 const image=layout.type.startsWith('image-'),outputs=layout.type==='image-preview'?['preview.png','thumbnail.png']:layout.type==='image-export'?['export.png']:layout.type==='media-convert'?['playback']:[];
 return {bin:['all-app.check','restricted-app.check'],input:[...(layout.type==='media-empty'?[]:[image?'source.cjs':'source.media']),...Array.from({length:layout.stamps},(_,i)=>'stamp-'+String(i).padStart(3,'0')+'.png')],out:[...(image?['job.json']:[]),...outputs]};
}
export class LpacWorkRecovery {
 constructor(root,native,policy={}){this.root=root;this.native=native;this.policy={enabled:true,minAgeMs:60000,scanLimit:200,intervalMs:60000,...policy};keys(this.policy,['enabled','minAgeMs','scanLimit','intervalMs']);check(typeof this.policy.enabled==='boolean'&&integer(this.policy.minAgeMs,0,86400000)&&integer(this.policy.scanLimit,1,10000)&&(this.policy.intervalMs===0||integer(this.policy.intervalMs,1000,3600000)));}
 paths(id){check(uuidPattern.test(id));return {root:path.join(this.root,id),request:path.join(this.root,id+'.request'),receipt:path.join(this.root,id+'.receipt.json'),next:path.join(this.root,id+'.receipt.next')};}
 async file(file,max=16384){await safeRuntimePath(file);const stat=await fs.lstat(file);check(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1&&stat.size<=max);return stat;}
 async list(){await safeRuntimePath(this.root);const names=[],directory=await fs.opendir(this.root);for await(const entry of directory){requireThat(names.length<this.policy.scanLimit,503,'LPAC_POOL_SCAN_LIMIT');names.push(entry.name);}return names;}
 validate(record,id){
  keys(record,['schemaVersion','id','state','createdAt','layout','requestSha256','requestBytes']);check(record.schemaVersion===1&&record.id===id&&integer(record.createdAt,0,8640000000000000)&&['allocated','prepared','retiring'].includes(record.state));
  if(record.state==='allocated')check(record.layout===null&&record.requestSha256===null&&record.requestBytes===0);
  else{keys(record.layout,['type','stamps']);check(['image-preview','image-export','media-empty','media-source','media-result','media-convert'].includes(record.layout.type)&&integer(record.layout.stamps,0,200)&&(record.layout.type==='image-export'||record.layout.stamps===0)&&/^[a-f0-9]{64}$/.test(record.requestSha256)&&integer(record.requestBytes,8,1048576));}
  return record;
 }
 async read(id){const file=this.paths(id).receipt;await this.file(file);return this.validate(JSON.parse(await fs.readFile(file,'utf8')),id);}
 async write(file,value,flag='wx'){const handle=await fs.open(file,flag,0o600);try{await handle.writeFile(JSON.stringify(value));await handle.sync();}finally{await handle.close();}}
 async update(record){const paths=this.paths(record.id);if(await exists(paths.next)){await this.file(paths.next);await fs.unlink(paths.next);}await this.write(paths.next,record);await fs.rename(paths.next,paths.receipt);}
 async allocate(id){const paths=this.paths(id);for(const file of Object.values(paths))check(!await exists(file));const record={schemaVersion:1,id,state:'allocated',createdAt:Date.now(),layout:null,requestSha256:null,requestBytes:0};await this.write(paths.receipt,record);}
 async prepare(binding,bytes){
  const id=path.basename(binding.root),paths=this.paths(id),record=await this.read(id);check(record.state==='allocated'&&paths.root===binding.root&&!await exists(paths.root));await this.file(paths.request,1048576);
  await this.update({...record,state:'prepared',layout:residueLayout(binding),requestSha256:hash(bytes),requestBytes:bytes.length});
  const handle=await fs.open(paths.request,'r+');try{await handle.truncate(0);await handle.writeFile(bytes);await handle.sync();}finally{await handle.close();}
 }
 async barrier(record){const paths=this.paths(record.id),stat=await this.file(paths.request,1048576),handle=await fs.open(paths.request,'r+');try{const actual=await handle.stat();check(actual.nlink===1&&actual.ino===stat.ino&&actual.dev===stat.dev&&actual.size<=1048576);return handle;}catch(error){await handle.close();throw error;}}
 async checkRequest(record,handle){const bytes=await handle.readFile();if(record.state==='allocated')check(bytes.length===0);else check(bytes.length===record.requestBytes&&hash(bytes)===record.requestSha256||record.state==='retiring'&&bytes[0]===marker[0]);}
 async invalidate(record,handle){const retired={...record,state:'retiring'};if(record.state!=='retiring')await this.update(retired);await handle.write(marker,0,marker.length,0);await handle.truncate(marker.length);await handle.sync();return retired;}
 async finish(id){const paths=this.paths(id);for(const file of [paths.request,paths.next])if(await exists(file)){await this.file(file,file===paths.request?1048576:16384);await fs.unlink(file);}await this.file(paths.receipt);await fs.unlink(paths.receipt);}
 async complete(binding,remove){const id=path.basename(binding.root),record=await this.read(id);check(this.paths(id).root===binding.root&&record.state==='prepared'&&JSON.stringify(record.layout)===JSON.stringify(residueLayout(binding)));const handle=await this.barrier(record);try{await this.checkRequest(record,handle);await this.invalidate(record,handle);await remove();await this.finish(id);}finally{await handle.close();}}
 async removeFile(file){await this.file(file,268435456);await fs.unlink(file);}
 async removeResidue(record){
  const root=this.paths(record.id).root;if(!await exists(root))return;await safeRuntimePath(root);const allowed=residueFiles(record.layout),folders=await fs.readdir(root);
  check(folders.every(name=>Object.hasOwn(allowed,name)));const files=[];
  for(const folder of folders){const dir=path.join(root,folder);await safeRuntimePath(dir);check((await fs.lstat(dir)).isDirectory());for(const name of await fs.readdir(dir)){check(allowed[folder].includes(name));const file=path.join(dir,name);await this.file(file,268435456);files.push(file);}}
  for(const file of files)await this.removeFile(file);for(const folder of folders)await fs.rmdir(path.join(root,folder));await fs.rmdir(root);
 }
 async recover(){
  const report={scanned:0,recovered:0,preserved:0,busy:0,recent:0,unknown:0};if(!this.policy.enabled)return report;
  const names=await this.list(),ids=names.filter(name=>name.endsWith('.receipt.json')&&uuidPattern.test(name.slice(0,-13))).map(name=>name.slice(0,-13));
  const known=new Set(['owner.sqlite','.imagegate-lpac-state']);for(const id of ids)for(const name of [id,id+'.request',id+'.receipt.json',id+'.receipt.next'])known.add(name);report.unknown=names.filter(name=>!known.has(name)).length;
  for(const id of ids){report.scanned++;let handle;
   try{
    const record=await this.read(id),paths=this.paths(id);if(Date.now()-record.createdAt<this.policy.minAgeMs){report.recent++;continue;}
    if(!await exists(paths.request)){check(!await exists(paths.root)&&['allocated','retiring'].includes(record.state));await this.finish(id);report.recovered++;continue;}
    handle=await this.barrier(record);await this.checkRequest(record,handle);
    if(record.state==='allocated'){check(!await exists(paths.root));await this.finish(id);report.recovered++;continue;}
    const state=path.join(this.root,'.imagegate-lpac-state');if(await exists(state)){const result=await this.native(['--recover',state]);const match=/^recovered=\d+ busy=(\d+)\s*$/.exec(result.stdout);check(match);if(Number(match[1])>0){report.busy++;continue;}check(result.code===0);}
    if(await exists(paths.root)){const result=await this.native(['--inspect-residue',paths.root,record.layout.type,String(record.layout.stamps)]);check(result.code===0&&result.stdout.trim()==='residue-ok');}
    await this.invalidate(record,handle);await this.removeResidue(record);await this.finish(id);report.recovered++;
   }catch(error){if(['EACCES','EPERM','EBUSY'].includes(error.code))report.busy++;else report.preserved++;}
   finally{await handle?.close();}
  }
  return report;
 }
}
