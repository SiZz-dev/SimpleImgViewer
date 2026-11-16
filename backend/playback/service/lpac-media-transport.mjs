import fs from 'node:fs/promises';import {constants} from 'node:fs';import path from 'node:path';
import {LpacTransport} from '../../conversion/service/lpac-transport.mjs';
import {mediaLpacBinding} from '../../conversion/util/lpac-bindings.mjs';
import {encodeMediaLpacRequest} from '../util/lpac-media-request.mjs';
import {requireThat,ApiError} from '../../system/util/errors.mjs';
import {regular} from '../../media/util/files.mjs';
import {openWorkLease} from '../../conversion/util/work-lease.mjs';
export class LpacMediaTransport extends LpacTransport {
 async execute({profile,work,timeout},runtime){
  requireThat(runtime?.kind==='ffmpeg'&&typeof work==='string'&&path.isAbsolute(work),503,'RUNTIME_REFERENCE_REQUIRED');
  for(const directory of [work,runtime.path]){const inside=(a,b)=>{const rel=path.relative(a,b);return rel===''||rel!=='..'&&!rel.startsWith('..'+path.sep)&&!path.isAbsolute(rel);};requireThat(!inside(directory,this.root)&&!inside(this.root,directory),503,'LPAC_ROOT_OVERLAP');}
  await this.verifyLauncher();await runtime.verify();const {request,root}=await this.reserve();let copied=false;
  try{
   const binding=mediaLpacBinding(profile,root);requireThat(binding.outputs.every(output=>path.dirname(output.target)===work),500,'INVALID_WORK_DIRECTORY');
   await this.writeRequest(binding,encodeMediaLpacRequest(binding,runtime,work,{...this.processing,timeoutMs:timeout}));requireThat(!this.closed,503,'PROCESSING_CLOSING');
   let text;try{text=await this.spawn(request,{raw:true,channelBytes:1048576,timeoutMs:timeout});}catch(error){if(error.code==='CONVERSION_TIMEOUT')throw new ApiError(504,'PLAYBACK_TIMEOUT');if(error.code==='CONVERSION_UNSUPPORTED_PROFILE')throw new ApiError(422,'INVALID_MEDIA');throw error;}
   const lease=openWorkLease(work);try{requireThat(lease.state==='active',409,'WORK_RETIRED');await runtime.verify();for(const output of binding.outputs){const source=path.join(root,'out',output.name),stat=await regular(source,output.maxBytes);requireThat(stat.nlink===1,422,'INVALID_LPAC_OUTPUT');await fs.copyFile(source,output.target,constants.COPYFILE_EXCL);copied=true;}}finally{lease.close();}
   await this.complete(binding);return text;
  }catch(error){if(copied)await fs.unlink(profile.output);throw error;}finally{this.reservations.delete(path.basename(root));}
 }
}
