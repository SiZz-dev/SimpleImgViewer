import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {randomUUID} from 'node:crypto';
import {fork} from 'node:child_process';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {TemporaryFiles} from '../media/service/temporary-files.mjs';
import {createApplication} from '../system/controller/application.mjs';
import {settings} from '../system/config/settings.mjs';

const policy={recoveryEnabled:true,minAgeMs:60000,scanLimit:100};
async function root(t){const directory=await fs.mkdtemp(path.join(os.tmpdir(),'siv-temp-owner-'));t.after(()=>fs.rm(directory,{recursive:true,force:true}));return directory;}
async function old(file){const date=new Date(Date.now()-120000);await fs.utimes(file,date,date);}

test('temporary owner blocks competing owners and close waits for active transfers',async t=>{
  const directory=await root(t),owner=new TemporaryFiles(directory,policy);await owner.init();t.after(()=>owner.close());
  const file=owner.allocate();await fs.writeFile(file,'active');await old(file);
  const other=new TemporaryFiles(directory,policy);await assert.rejects(other.init(),{code:'STORAGE_IN_USE'});
  assert.equal(await fs.readFile(file,'utf8'),'active');
  let closed=false;const closing=owner.close().then(()=>{closed=true;});
  await new Promise(resolve=>setImmediate(resolve));assert.equal(closed,false);
  assert.throws(()=>owner.allocate(),{code:'STORAGE_CLOSING'});
  await owner.release(file);await closing;await assert.rejects(fs.stat(file),{code:'ENOENT'});
  await other.init();await other.close();
});

test('crashed process loses its OS lock; only old UUID transfer files are reclaimed',async t=>{
  const directory=await root(t);
  const child=fork(fileURLToPath(new URL('./fixtures/temporary-owner.mjs',import.meta.url)),[directory],{execArgv:[],windowsHide:true,stdio:['ignore','ignore','pipe','ipc']});
  t.after(async()=>{if(child.exitCode===null&&child.signalCode===null){const ended=once(child,'exit');child.kill();await ended;}});
  const [{file}]=await once(child,'message',{signal:AbortSignal.timeout(10000)});
  const competing=new TemporaryFiles(directory,policy);await assert.rejects(competing.init(),{code:'STORAGE_IN_USE'});
  assert.equal(await fs.readFile(file,'utf8'),'abandoned upload');
  const recent=path.join(path.dirname(file),randomUUID()+'.part');await fs.writeFile(recent,'recent');
  const unknown=path.join(path.dirname(file),'unknown.part');await fs.writeFile(unknown,'unknown');await old(unknown);
  const legacy=path.join(directory,'multipart',randomUUID()+'.part');await fs.writeFile(legacy,'legacy');await old(legacy);
  const nested=path.join(path.dirname(file),randomUUID()+'.part');await fs.mkdir(nested);await fs.writeFile(path.join(nested,'keep'),'nested');
  const exited=once(child,'exit');child.kill();await exited;
  const recovered=new TemporaryFiles(directory,policy);const result=await recovered.init();
  try{assert.equal(result.removed,1);await assert.rejects(fs.stat(file),{code:'ENOENT'});
    for(const item of [recent,unknown,legacy,path.join(nested,'keep')])await fs.stat(item);
  }finally{await recovered.close();}
});

test('recovery opt-out preserves old transfers; unowned paths cannot be released',async t=>{
  const directory=await root(t),owner=new TemporaryFiles(directory,{...policy,recoveryEnabled:false});await owner.init();
  const file=owner.allocate();await fs.writeFile(file,'old');await old(file);
  // Simulate retained bytes from a prior owner without operating on an active path.
  const retained=path.join(path.dirname(file),randomUUID()+'.part');await fs.writeFile(retained,'retained');await old(retained);
  await assert.rejects(owner.release(retained),{code:'UNKNOWN_TEMPORARY_FILE'});
  await owner.release(file);await owner.close();
  const next=new TemporaryFiles(directory,{...policy,recoveryEnabled:false});assert.equal((await next.init()).removed,0);
  await fs.stat(retained);await next.close();
});

test('startup failure releases the storage owner and temporary settings reject invalid values',async t=>{
  const directory=await root(t);
  const overrides={storage:{root:directory},playback:{enabled:true}};
  await assert.rejects(createApplication({overrides}),{code:'FFMPEG_CONFIGURATION_REQUIRED'});
  const app=await createApplication({overrides:{storage:{root:directory},playback:{enabled:false}}});await app.close();
  for(const temporary of [{minAgeMs:0},{scanLimit:0},{recoveryEnabled:'true'}])await assert.rejects(settings({overrides:{temporary}}),{code:'INVALID_CONFIGURATION'});
});

test('recovery scan is bounded and never follows directory links',async t=>{
  const directory=await root(t),owner=new TemporaryFiles(directory,policy);await owner.init();await owner.close();
  const transfers=path.join(directory,'multipart','transfers');
  const outside=path.join(directory,'preserved');await fs.mkdir(outside);
  const original=path.join(outside,randomUUID()+'.part');await fs.writeFile(original,'keep');await old(original);
  const link=path.join(transfers,randomUUID()+'.part');await fs.symlink(outside,link,process.platform==='win32'?'junction':'dir');
  for(let i=0;i<100;i++)await fs.writeFile(path.join(transfers,'unknown-'+i),'keep');
  const bounded=new TemporaryFiles(directory,policy);await assert.rejects(bounded.init(),{code:'TEMP_STORAGE_SCAN_LIMIT'});
  assert.equal(await fs.readFile(original,'utf8'),'keep');
  const next=new TemporaryFiles(directory,{...policy,scanLimit:1000});assert.equal((await next.init()).removed,0);await next.close();
  assert.equal(await fs.readFile(original,'utf8'),'keep');
});
