import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import path from 'node:path';import os from 'node:os';import {createHash} from 'node:crypto';
import {ManagedRuntimes} from '../../../conversion/service/managed-runtimes.mjs';import {RuntimeVersions} from '../../../conversion/service/runtime-versions.mjs';import {createApplication} from '../../../system/controller/application.mjs';
import http from 'node:http';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
async function fixture(){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-managed-verifier-')),source=path.join(root,'source'),catalog=path.join(root,'catalog'),directories=['backend','backend/conversion','backend/conversion/util'];await fs.mkdir(path.join(source,'backend/conversion/util'),{recursive:true});
 const files=[];for(const name of ['backend/conversion/util/image-worker.mjs','node.exe']){const bytes=Buffer.from('synthetic hash-only fixture '+name);await fs.writeFile(path.join(source,name),bytes);files.push({path:name,bytes:bytes.length,sha256:hash(bytes)});}
 const lines=[...directories.map(name=>({name,line:'D\t'+name+'\n'})),...files.map(f=>({name:f.path,line:'F\t'+f.path+'\t'+f.bytes+'\t'+f.sha256+'\n'}))].sort((a,b)=>a.name<b.name?-1:1).map(x=>x.line).join(''),descriptor={kind:'node',directories,files,nativeDigest:hash(Buffer.from(lines))},registry=await new RuntimeVersions(catalog).init();
 const version=await registry.publish({source,descriptor,prepare:async(from,to)=>{await fs.cp(from,to,{recursive:true});return descriptor.nativeDigest;}});await registry.activate(version.id);await registry.close();return {root,catalog,version,config:{root:catalog,verification:{mode:'worker',maxQueue:1,timeoutMs:5000}}};
}
function hold(manager){const slot=[...manager.verifier.slots][0],post=slot.worker.postMessage.bind(slot.worker);let send;slot.worker.postMessage=(...args)=>{send=()=>post(...args);};return {slot,release(){slot.worker.postMessage=post;assert.ok(send);send();},get entered(){return Boolean(send);}};}
async function entered(gate){const end=Date.now()+5000;while(!gate.entered&&Date.now()<end)await new Promise(resolve=>setTimeout(resolve,5));assert.equal(gate.entered,true);}
test('selected manager verifies in its owned worker and releases it before catalog reuse',async()=>{
 const f=await fixture(),manager=await new ManagedRuntimes(f.config).init();const lease=await manager.acquire('node');assert.equal(lease.id,f.version.id);assert.equal(manager.verifier.slots.size,1);await lease.release();await manager.close();assert.equal(manager.verifier.slots.size,0);assert.equal(manager.verifier.pending.size,0);await manager.close();const registry=await new RuntimeVersions(f.catalog).init();await registry.close();
});
test('manager close drains an acquiring proof and rejects handing out a late reference',async()=>{
 const f=await fixture(),manager=await new ManagedRuntimes(f.config).init(),gate=hold(manager),acquiring=assert.rejects(manager.acquire('node'),{code:'RUNTIME_MANAGER_CLOSING'});await entered(gate);let closed=false;const closing=manager.close().then(()=>{closed=true;});await new Promise(resolve=>setImmediate(resolve));assert.equal(closed,false);await assert.rejects(manager.acquire('node'),{code:'RUNTIME_MANAGER_CLOSING',status:503});gate.release();await acquiring;await closing;assert.equal(manager.verifier.slots.size,0);assert.equal(manager.operations.size,0);
});
test('manager close also drains font approval after the full proof completed',async()=>{
 const f=await fixture(),manager=await new ManagedRuntimes(f.config).init();let signal,release;const started=new Promise(resolve=>{signal=resolve;}),gate=new Promise(resolve=>{release=resolve;});manager.verifyNoteFont=async()=>{signal();await gate;return false;};const checking=assert.rejects(manager.acquire('node'),{code:'RUNTIME_MANAGER_CLOSING'});await started;let closed=false;const closing=manager.close().then(()=>{closed=true;});await new Promise(resolve=>setImmediate(resolve));assert.equal(closed,false);release();await checking;await closing;assert.equal(manager.operations.size,0);
});
test('startup timeout never falls back and releases pool and catalog ownership',async()=>{
 const f=await fixture(),manager=new ManagedRuntimes({...f.config,verification:{mode:'worker',timeoutMs:1,maxQueue:0}});await assert.rejects(manager.init(),{code:'RUNTIME_VERIFY_TIMEOUT'});assert.equal(manager.verifier.slots.size,0);assert.equal(manager.verifier.pending.size,0);const registry=await new RuntimeVersions(f.catalog).init();await registry.close();
});
test('an application startup failure closes a worker initialized before LPAC profile validation',async()=>{
 const f=await fixture();await assert.rejects(createApplication({overrides:{storage:{root:path.join(f.root,'media')},upload:{minFreeBytes:0},playback:{enabled:false},runtimeVersions:{...f.config,enabled:true},isolation:{mode:'lpac',root:path.join(f.root,'pool'),imageLauncher:path.join(f.root,'missing.exe'),imageLauncherSha256:'a'.repeat(64)}}}),{code:'LPAC_NOTE_FONT_REQUIRED'});const registry=await new RuntimeVersions(f.catalog).init();await registry.close();
});
test('normal application shutdown is shared and closes the configured verification thread',async()=>{
 const f=await fixture(),app=await createApplication({overrides:{server:{host:'127.0.0.1',port:0},storage:{root:path.join(f.root,'media')},upload:{minFreeBytes:0},playback:{enabled:false},runtimeVersions:{...f.config,enabled:true}}});await app.listen();const pool=app.runtimes.verifier;assert.equal(pool.slots.size,1);const first=app.close(),second=app.close();assert.equal(first,second);await first;assert.equal(pool.slots.size,0);assert.equal(pool.pending.size,0);const registry=await new RuntimeVersions(f.catalog).init();await registry.close();
});
test('a listening-port conflict closes the already initialized worker, catalog and store',async()=>{
 const f=await fixture(),occupied=http.createServer();await new Promise(resolve=>occupied.listen(0,'127.0.0.1',resolve));let app;
 try{app=await createApplication({overrides:{server:{host:'127.0.0.1',port:occupied.address().port},storage:{root:path.join(f.root,'media')},upload:{minFreeBytes:0},playback:{enabled:false},runtimeVersions:{...f.config,enabled:true}}});const pool=app.runtimes.verifier;await assert.rejects(app.listen(),{code:'EADDRINUSE'});assert.equal(pool.slots.size,0);assert.equal(pool.pending.size,0);const replacement=await createApplication({overrides:{server:{host:'127.0.0.1',port:0},storage:{root:path.join(f.root,'media')},upload:{minFreeBytes:0},playback:{enabled:false},runtimeVersions:{...f.config,enabled:true}}});await replacement.close();}
 finally{await app?.close();await new Promise(resolve=>occupied.close(resolve));}
});
