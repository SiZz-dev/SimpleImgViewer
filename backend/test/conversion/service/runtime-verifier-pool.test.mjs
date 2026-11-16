import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {createHash} from 'node:crypto';
import {RuntimeVerifierPool} from '../../../conversion/service/runtime-verifier-pool.mjs';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
async function fixture(t,policy={}){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-verifier-')),bytes=Buffer.alloc(2097169,71),file=path.join(root,'sample.bin');await fs.writeFile(file,bytes);
 const sha256=hash(bytes),descriptor={kind:'ffmpeg',files:[{path:'sample.bin',bytes:bytes.length,sha256}],directories:[],nativeDigest:hash(Buffer.from('sample.bin\t'+bytes.length+'\t'+sha256+'\n'))};
 const pool=new RuntimeVerifierPool(policy);t.after(()=>pool.close());return {root,file,bytes,descriptor,pool};
}
function hold(pool,slot=[...pool.slots][0]){const post=slot.worker.postMessage.bind(slot.worker);let send;slot.worker.postMessage=(...args)=>{send=()=>post(...args);};return {slot,release(){slot.worker.postMessage=post;assert.ok(send);send();}};}
test('fixed worker rechecks every byte after reuse and rejects links and unknown inventory',async t=>{
 const f=await fixture(t);await f.pool.verify(f.root,f.descriptor);const slot=[...f.pool.slots][0];
 for(const index of [1048575,1048576,f.bytes.length-1]){const changed=Buffer.from(f.bytes);changed[index]^=255;await fs.writeFile(f.file,changed);await assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_TAMPERED'});await fs.writeFile(f.file,f.bytes);}
 const link=path.join(path.dirname(f.root),path.basename(f.root)+'-linked');await fs.link(f.file,link);await assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_TAMPERED'});await fs.unlink(link);
 const unknown=path.join(f.root,'unknown');await fs.writeFile(unknown,'preserve');await assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_TAMPERED'});assert.equal(await fs.readFile(unknown,'utf8'),'preserve');await fs.unlink(unknown);
 await f.pool.verify(f.root,f.descriptor);assert.equal([...f.pool.slots][0],slot);assert.equal(f.pool.pending.size,0);
});
test('invalid descriptors, roots and executable overrides reject before allocating a worker',async t=>{
 const f=await fixture(t);for(const policy of [{threads:3},{threads:0},{maxQueue:33},{timeoutMs:0},{workerUrl:'anything'},{execArgv:['--inspect']}])assert.throws(()=>new RuntimeVerifierPool(policy));
 await assert.rejects(f.pool.verify('relative',f.descriptor),{code:'UNSAFE_RUNTIME_PATH'});await assert.rejects(f.pool.verify(f.root,{...f.descriptor,files:[{...f.descriptor.files[0],path:'../escape'}]}),{code:'INVALID_RUNTIME_DESCRIPTOR'});assert.equal(f.pool.slots.size,0);
});
test('bounded queue refuses excess work; close rejects queued work and drains the active proof',async t=>{
 const f=await fixture(t,{threads:1,maxQueue:1});await f.pool.verify(f.root,f.descriptor);const gate=hold(f.pool),active=f.pool.verify(f.root,f.descriptor),queued=f.pool.verify(f.root,f.descriptor),denied=assert.rejects(queued,{code:'RUNTIME_VERIFY_CLOSING'});
 await assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_VERIFY_QUEUE_FULL'});let closed=false;const closing=f.pool.close().then(()=>{closed=true;});await denied;await new Promise(resolve=>setImmediate(resolve));assert.equal(closed,false);gate.release();await active;await closing;assert.equal(f.pool.slots.size,0);assert.equal(f.pool.pending.size,0);await assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_VERIFY_CLOSING'});await f.pool.close();
});
test('active deadline rejects only after worker exit and the next request uses a fresh worker',async t=>{
 const f=await fixture(t,{threads:1});await f.pool.verify(f.root,f.descriptor);const gate=hold(f.pool);let exited=false;gate.slot.worker.on('exit',()=>{exited=true;});f.pool.policy.timeoutMs=10;await assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_VERIFY_TIMEOUT'});assert.equal(exited,true);assert.equal(f.pool.pending.size,0);f.pool.policy.timeoutMs=5000;await f.pool.verify(f.root,f.descriptor);assert.notEqual([...f.pool.slots][0],gate.slot);
});
test('queued deadline removes only the queued request and leaves the active worker reusable',async t=>{
 const f=await fixture(t,{threads:1,maxQueue:1});await f.pool.verify(f.root,f.descriptor);const gate=hold(f.pool),active=f.pool.verify(f.root,f.descriptor);f.pool.policy.timeoutMs=10;await assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_VERIFY_TIMEOUT'});assert.equal(f.pool.pending.size,1);assert.equal(f.pool.queue.length,0);assert.equal(f.pool.slots.has(gate.slot),true);f.pool.policy.timeoutMs=5000;gate.release();await active;await f.pool.verify(f.root,f.descriptor);assert.equal([...f.pool.slots][0],gate.slot);
});
test('two-worker admission remains bounded and queued descriptors are independent snapshots',async t=>{
 const f=await fixture(t,{threads:2,maxQueue:1});await Promise.all([f.pool.verify(f.root,f.descriptor),f.pool.verify(f.root,f.descriptor)]);assert.equal(f.pool.slots.size,2);const gates=[...f.pool.slots].map(slot=>hold(f.pool,slot));const first=f.pool.verify(f.root,f.descriptor),second=f.pool.verify(f.root,f.descriptor),queued=f.pool.verify(f.root,f.descriptor);await assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_VERIFY_QUEUE_FULL'});assert.equal(f.pool.pending.size,3);const original=f.descriptor.files[0].sha256;f.descriptor.files[0].sha256='0'.repeat(64);for(const gate of gates)gate.release();await Promise.all([first,second,queued]);f.descriptor.files[0].sha256=original;assert.equal(f.pool.pending.size,0);assert.equal(f.pool.slots.size,2);
});
test('unexpected thread exit fails the active proof without parent-thread fallback',async t=>{
 const f=await fixture(t,{threads:1});await f.pool.verify(f.root,f.descriptor);const gate=hold(f.pool),checking=assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_VERIFY_WORKER_EXIT'});await gate.slot.worker.terminate();await checking;assert.equal(f.pool.pending.size,0);await f.pool.verify(f.root,f.descriptor);
});
test('a reply bound to another digest fails closed and waits for worker exit',async t=>{
 const f=await fixture(t,{threads:1});await f.pool.verify(f.root,f.descriptor);const gate=hold(f.pool);let exited=false;gate.slot.worker.on('exit',()=>{exited=true;});const checking=assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_VERIFY_PROTOCOL'});gate.slot.worker.emit('message',{id:gate.slot.job.id,ok:true,nativeDigest:'0'.repeat(64)});await checking;assert.equal(exited,true);assert.equal(f.pool.pending.size,0);
});
test('a reply after its monotonic deadline cannot succeed before a delayed timer callback',async t=>{
 const f=await fixture(t,{threads:1});await f.pool.verify(f.root,f.descriptor);const gate=hold(f.pool);let exited=false;gate.slot.worker.on('exit',()=>{exited=true;});const checking=assert.rejects(f.pool.verify(f.root,f.descriptor),{code:'RUNTIME_VERIFY_TIMEOUT'});gate.slot.job.deadline=performance.now()-1;gate.slot.worker.emit('message',{id:gate.slot.job.id,ok:true,nativeDigest:f.descriptor.nativeDigest});await checking;assert.equal(exited,true);assert.equal(f.pool.pending.size,0);
});
