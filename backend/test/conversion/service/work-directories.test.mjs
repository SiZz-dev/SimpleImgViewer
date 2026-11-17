import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {fork} from 'node:child_process';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {WorkDirectories} from '../../../conversion/service/work-directories.mjs';
import {openWorkLease} from '../../../conversion/util/work-lease.mjs';
import {ImageEngine} from '../../../conversion/service/image-engine.mjs';
import {PlaybackService} from '../../../playback/service/ffmpeg.mjs';
import {settings} from '../../../system/config/settings.mjs';
import sharp from 'sharp';

const policy={recoveryEnabled:true,minAgeMs:60000,scanLimit:100};
async function fixture(t){
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-work-test-'));
  t.after(()=>fs.rm(root,{recursive:true,force:true}));
  const work=new WorkDirectories(root,policy);await work.init();return {root,work};
}
async function old(directory){const value=new Date(Date.now()-120000);await fs.utimes(directory,value,value);}

test('recovery preserves a live worker lease, then retires abandoned work after worker crash',async t=>{
  const {root,work}=await fixture(t),directory=await work.create();
  const child=fork(fileURLToPath(new URL('../../fixtures/work-owner.mjs',import.meta.url)),[directory],{execArgv:[],windowsHide:true,stdio:['ignore','ignore','pipe','ipc']});
  t.after(async()=>{if(child.exitCode===null&&child.signalCode===null){const ended=once(child,'exit');child.kill('SIGKILL');await ended;}});
  await once(child,'message',{signal:AbortSignal.timeout(10000)});await old(directory);
  const recovery=new WorkDirectories(root,policy);assert.equal((await recovery.init()).busy,1);
  assert.equal(await fs.readFile(path.join(directory,'preview.png'),'utf8'),'active raster');
  const ended=once(child,'exit');child.kill('SIGKILL');await ended;
  assert.equal((await recovery.init()).recovered,1);
  await assert.rejects(fs.stat(path.join(directory,'preview.png')),{code:'ENOENT'});
  const lease=openWorkLease(directory);try{assert.equal(lease.state,'retired');}finally{lease.close();}
  // A delayed real image worker is refused before opening the input/output.
  const input=path.join(root,'valid.png');await sharp({create:{width:64,height:32,channels:3,background:'#4488cc'}}).png().toFile(input);
  const images=new ImageEngine(await settings({development:true}));
  await assert.rejects(images.run({input,output:path.join(directory,'preview.png'),thumbnail:path.join(directory,'thumbnail.png'),operation:'preview',page:1,edge:64,maxPixels:4096}),{code:'CONVERSION_UNSUPPORTED_PROFILE'});
  await images.close();
  await assert.rejects(fs.stat(path.join(directory,'preview.png')),{code:'ENOENT'});
  await work.release(directory);await work.close();
});

test('work recovery deletes only fixed outputs and preserves recent, unknown and linked content',async t=>{
  const {root,work}=await fixture(t),directory=await work.create(),recent=await work.create();
  await fs.writeFile(path.join(directory,'export.png'),'partial');await fs.writeFile(path.join(directory,'unknown'),'keep');
  await fs.mkdir(path.join(directory,'thumbnail.png'));
  const outside=path.join(root,'outside');await fs.mkdir(outside);await fs.writeFile(path.join(outside,'keep'),'keep');
  await fs.symlink(outside,path.join(directory,'preview.png'),process.platform==='win32'?'junction':'dir');
  await old(directory);
  const recovery=new WorkDirectories(root,policy);assert.equal((await recovery.init()).recovered,1);
  await assert.rejects(fs.stat(path.join(directory,'export.png')),{code:'ENOENT'});
  for(const item of ['unknown','thumbnail.png','preview.png'])await fs.lstat(path.join(directory,item));
  assert.equal(await fs.readFile(path.join(outside,'keep'),'utf8'),'keep');
  const lease=openWorkLease(recent);try{assert.equal(lease.state,'active');}finally{lease.close();}
  await work.release(directory);await work.release(recent);await work.close();
});

test('work close rejects new jobs and waits until current-owner files are released',async t=>{
  const {work}=await fixture(t),directory=await work.create();await fs.writeFile(path.join(directory,'export.png'),'partial');
  let closed=false;const closing=work.close().then(()=>{closed=true;});
  await new Promise(resolve=>setImmediate(resolve));assert.equal(closed,false);
  await assert.rejects(work.create(),{code:'STORAGE_CLOSING'});
  await work.release(directory);await closing;await assert.rejects(fs.stat(directory),{code:'ENOENT'});
});

test('retirement marker blocks work even after a crash before the SQLite state commit',async t=>{
  const {work}=await fixture(t),directory=await work.create();
  await fs.writeFile(path.join(directory,'retired'),'');
  const lease=openWorkLease(directory);
  try{assert.equal(lease.database.prepare('SELECT state FROM lifecycle').get().state,'active');assert.equal(lease.state,'retired');}finally{lease.close();}
  await work.release(directory);await work.close();
});

test('cache publication preserves its retired lease and failed publication remains cleanable',async t=>{
  const {root,work}=await fixture(t),directory=await work.create();
  await fs.writeFile(path.join(directory,'preview.png'),'ready');const target=path.join(root,'derived','ready-cache');
  await work.publish(directory,target);await work.release(directory);
  assert.equal(await fs.readFile(path.join(target,'preview.png'),'utf8'),'ready');
  const lease=openWorkLease(target);try{assert.equal(lease.state,'retired');}finally{lease.close();}
  const failed=await work.create();await fs.writeFile(path.join(failed,'preview.png'),'partial');
  await assert.rejects(work.publish(failed,target));await work.release(failed);
  await assert.rejects(fs.stat(failed),{code:'ENOENT'});await work.close();
});

test('image worker CLI rejects missing job data instead of waiting for unbounded IPC',async()=>{
  const worker=fork(fileURLToPath(new URL('../../../conversion/util/image-worker.mjs',import.meta.url)),[],{execArgv:[],windowsHide:true,stdio:['ignore','ignore','pipe','ipc']});
  const [code]=await once(worker,'close',{signal:AbortSignal.timeout(10000)});assert.notEqual(code,0);
});

test('FFmpeg timeout and service close wait for child exit; closed engines refuse new work',{skip:process.env.SIV_NATIVE_TESTS!=='1'},async()=>{
  const config=await settings({development:true}),playback=new PlaybackService(config,{});
  await assert.rejects(playback.run(process.execPath,['-e','setInterval(()=>{},1000)'],100),{code:'PLAYBACK_TIMEOUT'});
  assert.equal(playback.children.size,0);
  const operation=playback.run(process.execPath,['-e','setInterval(()=>{},1000)']);const settled=assert.rejects(operation);
  for(let i=0;i<100&&playback.children.size===0;i++)await new Promise(resolve=>setTimeout(resolve,10));
  assert.equal(playback.children.size,1);
  await playback.close();await settled;assert.equal(playback.children.size,0);
  await assert.rejects(playback.run(process.execPath,['-v']),{code:'PROCESSING_CLOSING'});
});

test('closed image engines refuse new work',async()=>{
  const images=new ImageEngine(await settings());await images.close();await assert.rejects(images.run({output:path.resolve('unused.png')}),{code:'PROCESSING_CLOSING'});
});
