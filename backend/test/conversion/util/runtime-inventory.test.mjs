import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {createHash} from 'node:crypto';
import {runtimeDescriptor,verifyRuntimeTree} from '../../../conversion/util/runtime-inventory.mjs';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
async function fixture(t){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-inventory-'));
 // Cross both read sizes with a short tail and a second small file.
 const bytes=Buffer.alloc(2097169);for(let i=0;i<bytes.length;i++)bytes[i]=(i*31+i%257)%256;
 const files=[];for(const [name,data] of [['large.bin',bytes],['small.bin',Buffer.from('approved tail')]]){await fs.writeFile(path.join(root,name),data);files.push({path:name,bytes:data.length,sha256:hash(data)});}
 const nativeDigest=hash(Buffer.from(files.map(file=>file.path+'\t'+file.bytes+'\t'+file.sha256+'\n').join('')));
 return {root,bytes,descriptor:runtimeDescriptor({kind:'ffmpeg',files,directories:[],nativeDigest})};
}
test('full inventory hashing detects same-size edits at read boundaries and the final tail',async t=>{
 const {root,bytes,descriptor}=await fixture(t),file=path.join(root,'large.bin');
 for(const readChunkBytes of [65536,262144,1048576]){
  await verifyRuntimeTree(root,descriptor,{readChunkBytes});
  for(const index of [65535,65536,1048575,1048576,bytes.length-1]){const changed=Buffer.from(bytes);changed[index]^=255;await fs.writeFile(file,changed);await assert.rejects(verifyRuntimeTree(root,descriptor,{readChunkBytes}),{code:'RUNTIME_TAMPERED'});await fs.writeFile(file,bytes);}
 }
 await verifyRuntimeTree(root,descriptor);
});
test('larger reads retain complete inventory, file size, hardlink and partial recovery checks',async t=>{
 const {root,bytes,descriptor}=await fixture(t),file=path.join(root,'large.bin');
 await fs.appendFile(file,'x');await assert.rejects(verifyRuntimeTree(root,descriptor),{code:'RUNTIME_TAMPERED'});await fs.writeFile(file,bytes);
 const extra=path.join(root,'unknown.bin');await fs.writeFile(extra,'preserve');await assert.rejects(verifyRuntimeTree(root,descriptor,{partial:true}),{code:'RUNTIME_TAMPERED'});await fs.unlink(extra);
 const link=path.join(path.dirname(root),path.basename(root)+'-link.bin');await fs.link(file,link);await assert.rejects(verifyRuntimeTree(root,descriptor),{code:'RUNTIME_TAMPERED'});await fs.unlink(link);
 await fs.unlink(path.join(root,'small.bin'));await assert.rejects(verifyRuntimeTree(root,descriptor),{code:'RUNTIME_TAMPERED'});await verifyRuntimeTree(root,descriptor,{partial:true});
 for(const readChunkBytes of [0,65535,1048577,NaN,'1048576'])await assert.rejects(verifyRuntimeTree(root,descriptor,{readChunkBytes}),{code:'INVALID_RUNTIME_READ_CHUNK'});
});
test('bounded workers hash the complete inventory and observer failures cannot change acceptance',async t=>{
 const {root,descriptor}=await fixture(t);
 for(const fileConcurrency of [1,2,4]){let report;await verifyRuntimeTree(root,descriptor,{fileConcurrency,observe:value=>{report=value;throw Error('observer must be isolated');}});assert.equal(report.passed,true);assert.equal(report.files,descriptor.files.length);assert.equal(report.bytes,descriptor.bytes);assert.ok(report.reads>=descriptor.files.length);assert.ok(report.wallMs>=0);assert.equal(report.fileConcurrency,fileConcurrency);}
 for(const fileConcurrency of [0,5,1.5,'2'])await assert.rejects(verifyRuntimeTree(root,descriptor,{fileConcurrency}),{code:'INVALID_RUNTIME_CONCURRENCY'});
 await assert.rejects(verifyRuntimeTree(root,descriptor,{observe:true}),{code:'INVALID_RUNTIME_OBSERVER'});
 await fs.appendFile(path.join(root,'small.bin'),'changed');let failed;await assert.rejects(verifyRuntimeTree(root,descriptor,{fileConcurrency:4,observe:value=>{failed=value;}}),{code:'RUNTIME_TAMPERED'});assert.equal(failed.passed,false);
});
test('a failed worker drains another open read before rejecting the full verification',async t=>{
 const {root,descriptor}=await fixture(t),large=path.join(root,'large.bin'),small=path.join(root,'small.bin');
 await fs.writeFile(small,Buffer.alloc(descriptor.files.find(file=>file.path==='small.bin').bytes,0));
 const original=fs.open;let release,entered,smallDone,closed=0,settled=false;
 const gate=new Promise(resolve=>{release=resolve;}),started=new Promise(resolve=>{entered=resolve;}),eof=new Promise(resolve=>{smallDone=resolve;});let blocked=false;
 fs.open=async(file,...args)=>{const handle=await original(file,...args);if(file!==large&&file!==small)return handle;return {stat:()=>handle.stat(),read:async(...args)=>{if(file===large&&!blocked){blocked=true;entered();await gate;}const result=await handle.read(...args);if(file===small&&result.bytesRead===0)smallDone();return result;},close:async()=>{try{await handle.close();}finally{closed++;}}};};
 let checking;
 try{checking=verifyRuntimeTree(root,descriptor,{fileConcurrency:2});const rejected=assert.rejects(checking,{code:'RUNTIME_TAMPERED'}).finally(()=>{settled=true;});await Promise.all([started,eof]);await new Promise(resolve=>setImmediate(resolve));assert.equal(settled,false);release();await rejected;assert.equal(closed,2);}
 finally{release();await checking?.catch(()=>{});fs.open=original;}
});
