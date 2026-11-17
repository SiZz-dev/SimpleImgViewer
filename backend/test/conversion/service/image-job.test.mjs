import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import net from 'node:net';
import {fork,execFile} from 'node:child_process';
import {once} from 'node:events';
import {promisify} from 'node:util';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {ImageEngine} from '../../../conversion/service/image-engine.mjs';
import {WorkDirectories} from '../../../conversion/service/work-directories.mjs';
import {settings} from '../../../system/config/settings.mjs';

async function fixture(t){const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-image-job-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));return root;}
async function bmp(root){
  const width=4000,height=4000,stride=width*3,size=54+stride*height,header=Buffer.alloc(54);
  header.write('BM');header.writeUInt32LE(size,2);header.writeUInt32LE(54,10);header.writeUInt32LE(40,14);header.writeInt32LE(width,18);header.writeInt32LE(height,22);header.writeUInt16LE(1,26);header.writeUInt16LE(24,28);
  const file=path.join(root,'valid.bmp'),handle=await fs.open(file,'wx');try{await handle.write(header);await handle.truncate(size);}finally{await handle.close();}return file;
}
async function until(fn){for(let i=0;i<200;i++){if(await fn())return;await new Promise(resolve=>setTimeout(resolve,25));}assert.fail('image process did not settle');}
const alive=pid=>{try{process.kill(pid,0);return true;}catch(e){if(e.code==='ESRCH')return false;throw e;}};

test('actual image worker rejects a valid large BMP under 64MiB and converts it under 512MiB',async t=>{
  const root=await fixture(t),input=await bmp(root),config=await settings({development:true});
  const work=new WorkDirectories(root,config.temporary);await work.init();
  for(const memory of [64,512]){
    const directory=await work.create(),images=new ImageEngine({...config,processing:{...config.processing,jobMemoryMiB:memory}});
    try{
      const operation=images.run({input,operation:'preview',page:1,edge:64,output:path.join(directory,'preview.png'),thumbnail:path.join(directory,'thumbnail.png')});
      if(memory===64)await assert.rejects(operation,{code:'CONVERSION_UNSUPPORTED_PROFILE'});
      else{const result=await operation;assert.equal(result.width,4000);assert.equal(result.previewWidth,64);}
      assert.equal(images.children.size,0);assert.equal(images.active,0);await assert.rejects(fs.stat(path.join(directory,'job.json')),{code:'ENOENT'});
    }finally{await images.close();await work.release(directory);}
  }
  await work.close();
});

test('closing an active image job waits for process exit and clears its manifest',async t=>{
  const root=await fixture(t),input=await bmp(root),config=await settings({development:true});
  const work=new WorkDirectories(root,config.temporary);await work.init();const directory=await work.create(),images=new ImageEngine(config);
  const operation=images.run({input,operation:'preview',page:1,edge:64,output:path.join(directory,'preview.png'),thumbnail:path.join(directory,'thumbnail.png')});const rejected=assert.rejects(operation);
  await until(()=>images.children.size===1);const args=[...images.children][0].spawnargs;
  assert.equal(args[args.indexOf('--memory-mib')+1],'512');assert.equal(args[args.indexOf('--cpu-seconds')+1],'30');
  await images.close();await rejected;assert.equal(images.children.size,0);assert.equal(images.active,0);
  await assert.rejects(fs.stat(path.join(directory,'job.json')),{code:'ENOENT'});await work.release(directory);await work.close();
});

test('image execution fails closed without a launcher and validates resource settings',async t=>{
  const root=await fixture(t),config=await settings({overrides:{playback:{enabled:false}}}),images=new ImageEngine(config);
  await assert.rejects(images.run({output:path.join(root,'preview.png')}),{code:'PROCESS_JOB_CONFIGURATION_REQUIRED'});assert.equal(images.active,0);await images.close();
  for(const processing of [{jobMemoryMiB:0},{jobCpuSeconds:0},{jobCpuRatePercent:101}])await assert.rejects(settings({overrides:{processing}}),{code:'INVALID_CONFIGURATION'});
});

test('parent crash terminates the actual image worker and releases its work for recovery',async t=>{
  const root=await fixture(t),pipe='\\\\.\\pipe\\siv-image-'+randomUUID(),sockets=new Set();
  const server=net.createServer(socket=>{sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>sockets.delete(socket));});
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(pipe,resolve);});
  t.after(async()=>{for(const socket of sockets)socket.destroy();await new Promise(resolve=>server.close(resolve));});
  const parent=fork(fileURLToPath(new URL('../../fixtures/image-job-parent.mjs',import.meta.url)),[root,pipe],{execArgv:[],windowsHide:true,stdio:['ignore','ignore','pipe','ipc']});
  t.after(async()=>{if(parent.exitCode===null&&parent.signalCode===null){const exited=once(parent,'exit');parent.kill('SIGKILL');await exited;}});
  const [{launcherPid,work}]=await once(parent,'message',{signal:AbortSignal.timeout(10000)});await until(()=>sockets.size>0);
  const script=`Get-CimInstance Win32_Process -Filter 'ParentProcessId = ${Number(launcherPid)}' | Select-Object ProcessId,ExecutablePath,CommandLine | ConvertTo-Json -Compress`;
  const {stdout}=await promisify(execFile)('powershell.exe',['-NoProfile','-Command',script],{windowsHide:true,timeout:10000});const processes=JSON.parse(stdout);
  const worker=(Array.isArray(processes)?processes:[processes]).find(p=>p.ExecutablePath&&path.resolve(p.ExecutablePath).toLowerCase()===path.resolve(process.execPath).toLowerCase()&&p.CommandLine.includes('image-worker.mjs'));assert.ok(worker);
  const old=new Date(Date.now()-120000);await fs.utimes(work,old,old);
  const recovery=new WorkDirectories(root,{recoveryEnabled:true,minAgeMs:60000,scanLimit:100});assert.equal((await recovery.init()).recovered,0);
  const exited=once(parent,'exit');parent.kill('SIGKILL');await exited;await until(()=>!alive(worker.ProcessId));await until(()=>!alive(launcherPid));
  assert.equal((await recovery.init()).recovered,1);await assert.rejects(fs.stat(path.join(work,'job.json')),{code:'ENOENT'});
});
