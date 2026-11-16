import {Worker} from 'node:worker_threads';
import path from 'node:path';
import {runtimeDescriptor} from '../util/runtime-inventory.mjs';
import {ApiError,requireThat,integer} from '../../system/util/errors.mjs';
const workerUrl=new URL('../util/runtime-verify-worker.mjs',import.meta.url);
const codes={RUNTIME_TAMPERED:500,UNSAFE_RUNTIME_PATH:500,INVALID_RUNTIME_DESCRIPTOR:400,INVALID_RUNTIME_DIGEST:400,ENOENT:404,ENOTDIR:500,EACCES:503,EPERM:503,EIO:503,EMFILE:503,ENFILE:503,RUNTIME_VERIFY_FAILED:503};
// Internal candidate only. Application/settings do not instantiate this pool.
export class RuntimeVerifierPool {
 constructor({threads=1,maxQueue=8,timeoutMs=30000,...unknown}={}){
  requireThat(!Object.keys(unknown).length&&integer(threads,1,2)&&integer(maxQueue,0,32)&&integer(timeoutMs,1,120000),400,'INVALID_RUNTIME_VERIFIER_POLICY');
  this.policy={threads,maxQueue,timeoutMs};this.slots=new Set();this.pending=new Map();this.queue=[];this.nextId=0;this.closing=false;this.closePromise=null;
 }
 verify(root,value){
  try{
   requireThat(!this.closing,503,'RUNTIME_VERIFY_CLOSING');
   requireThat(typeof root==='string'&&path.isAbsolute(root)&&path.normalize(root)===root,500,'UNSAFE_RUNTIME_PATH');
   const descriptor=runtimeDescriptor(value);
   requireThat(this.pending.size<this.policy.threads+this.policy.maxQueue,429,'RUNTIME_VERIFY_QUEUE_FULL');
   const promise=new Promise((resolve,reject)=>{
    const job={id:++this.nextId,root,descriptor,resolve,reject,deadline:performance.now()+this.policy.timeoutMs};this.pending.set(job.id,job);this.queue.push(job);
    job.timer=setTimeout(()=>this.expire(job),this.policy.timeoutMs);job.timer.unref();
   });this.pump();return promise;
  }catch(error){return Promise.reject(error);}
 }
 finish(job,error){
  if(!this.pending.delete(job.id))return;clearTimeout(job.timer);
  if(error)job.reject(error);else job.resolve();
 }
 expire(job){
  if(!this.pending.has(job.id))return;
  const error=new ApiError(504,'RUNTIME_VERIFY_TIMEOUT'),index=this.queue.indexOf(job);
  if(index>=0){this.queue.splice(index,1);this.finish(job,error);this.checkClosed();return;}
  const slot=[...this.slots].find(slot=>slot.job===job);if(slot)this.retire(slot,error);
 }
 createSlot(){
  const worker=new Worker(workerUrl,{name:'siv-runtime-verify',execArgv:[],argv:[],trackUnmanagedFds:true,stdout:true,stderr:true,env:{SystemRoot:process.env.SystemRoot||'',WINDIR:process.env.WINDIR||''},resourceLimits:{maxOldGenerationSizeMb:32,maxYoungGenerationSizeMb:8,stackSizeMb:2}});
  const slot={worker,job:null,retiring:false,error:null,outputBytes:0};this.slots.add(slot);
  for(const stream of [worker.stdout,worker.stderr])stream.on('data',bytes=>{slot.outputBytes+=bytes.length;if(slot.outputBytes>8192)this.retire(slot,new ApiError(503,'RUNTIME_VERIFY_OUTPUT_LIMIT'));});
  worker.on('message',message=>this.complete(slot,message));
  worker.on('messageerror',()=>this.retire(slot,new ApiError(503,'RUNTIME_VERIFY_PROTOCOL')));
  worker.on('error',()=>this.retire(slot,new ApiError(503,'RUNTIME_VERIFY_WORKER_FAILED')));
  worker.on('exit',()=>{
   this.slots.delete(slot);
   // A timeout/crash cannot release the caller's runtime reference before exit.
   if(slot.job)this.finish(slot.job,slot.error||new ApiError(503,'RUNTIME_VERIFY_WORKER_EXIT'));
   this.pump();this.checkClosed();
  });return slot;
 }
 retire(slot,error=null){
  if(slot.retiring)return;slot.retiring=true;slot.error=error;
  slot.worker.terminate().catch(()=>{});
 }
 complete(slot,message){
  if(slot.retiring)return;const job=slot.job;
  if(!job||message?.id!==job.id||typeof message.ok!=='boolean'||Object.keys(message).length!==3||Object.keys(message).some(key=>!['id','ok',message.ok?'nativeDigest':'code'].includes(key))||message.ok&&message.nativeDigest!==job.descriptor.nativeDigest||!message.ok&&!Object.hasOwn(codes,message.code))return this.retire(slot,new ApiError(503,'RUNTIME_VERIFY_PROTOCOL'));
  if(performance.now()>=job.deadline)return this.retire(slot,new ApiError(504,'RUNTIME_VERIFY_TIMEOUT'));
  slot.job=null;this.finish(job,message.ok?null:new ApiError(codes[message.code],message.code));
  if(this.closing)this.retire(slot);else this.pump();
 }
 pump(){
  while(!this.closing&&this.queue.length){
   let slot=[...this.slots].find(slot=>!slot.job&&!slot.retiring);
   if(!slot&&this.slots.size>=this.policy.threads)return;
   const job=this.queue.shift();
   if(performance.now()>=job.deadline){this.finish(job,new ApiError(504,'RUNTIME_VERIFY_TIMEOUT'));continue;}
   try{slot??=this.createSlot();slot.job=job;slot.worker.postMessage({protocol:1,id:job.id,root:job.root,descriptor:job.descriptor});}
   catch{if(slot)this.retire(slot,new ApiError(503,'RUNTIME_VERIFY_WORKER_FAILED'));else this.finish(job,new ApiError(503,'RUNTIME_VERIFY_WORKER_FAILED'));}
  }
 }
 close(){
  if(this.closePromise)return this.closePromise;this.closing=true;
  this.closePromise=new Promise(resolve=>{this.closedResolve=resolve;});
  for(const job of this.queue.splice(0))this.finish(job,new ApiError(503,'RUNTIME_VERIFY_CLOSING'));
  for(const slot of this.slots)if(!slot.job)this.retire(slot);
  // Active checks drain normally, or their existing deadline terminates the worker.
  this.checkClosed();return this.closePromise;
 }
 checkClosed(){if(this.closing&&!this.slots.size&&!this.pending.size)this.closedResolve?.();}
}
