import busboy from 'busboy';
import {createWriteStream} from 'node:fs';
import {pipeline} from 'node:stream/promises';
import {ApiError,requireThat} from '../../system/util/errors.mjs';
export async function receiveUpload(req,store,maxBytes=store.config.upload.maxBytes,original=true){
  const temp=await store.temp(maxBytes,original);let fileName,task,limited=false,extra=false;
  try{
    const parser=busboy({headers:req.headers,limits:{files:1,fields:0,parts:2,fileSize:maxBytes,headerPairs:32}});
    parser.on('file',(_name,file,info)=>{fileName=info.filename;file.on('limit',()=>{limited=true;});task=pipeline(file,createWriteStream(temp,{flags:'wx',mode:0o600}));task.catch(()=>{});});
    parser.on('filesLimit',()=>{extra=true;});parser.on('fieldsLimit',()=>{extra=true;});parser.on('partsLimit',()=>{extra=true;});
    await pipeline(req,parser);if(task)await task;
    requireThat(!limited,413,'FILE_TOO_LARGE');requireThat(fileName&&!extra,400,'INVALID_UPLOAD');return {temp,fileName};
  }catch(e){if(task)await task.catch(()=>{});await store.releaseTemp(temp);throw e instanceof ApiError?e:new ApiError(400,'INVALID_UPLOAD');}
}
