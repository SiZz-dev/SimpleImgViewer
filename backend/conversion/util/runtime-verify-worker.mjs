import {parentPort,isMainThread} from 'node:worker_threads';
import {runtimeDescriptor,verifyRuntimeTree} from './runtime-inventory.mjs';
if(isMainThread||!parentPort)throw Error('Verification worker requires its fixed parent');
let busy=false;
parentPort.on('message',async request=>{
 if(busy)throw Error('Concurrent worker protocol request');busy=true;
 try{
  if(request?.protocol!==1||!Number.isSafeInteger(request.id)||Object.keys(request).some(key=>!['protocol','id','root','descriptor'].includes(key)))throw Error('Invalid worker protocol');
  const descriptor=runtimeDescriptor(request.descriptor);
  await verifyRuntimeTree(request.root,descriptor);
  parentPort.postMessage({id:request.id,ok:true,nativeDigest:descriptor.nativeDigest});
 }catch(error){
  const code=['RUNTIME_TAMPERED','UNSAFE_RUNTIME_PATH','INVALID_RUNTIME_DESCRIPTOR','INVALID_RUNTIME_DIGEST','ENOENT','ENOTDIR','EACCES','EPERM','EIO','EMFILE','ENFILE'].includes(error.code)?error.code:'RUNTIME_VERIFY_FAILED';
  parentPort.postMessage({id:request?.id,ok:false,code});
 }finally{busy=false;}
});
