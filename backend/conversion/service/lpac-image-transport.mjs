import fs from 'node:fs/promises';
import {constants} from 'node:fs';
import path from 'node:path';
import {regular} from '../../media/util/files.mjs';
import {requireThat} from '../../system/util/errors.mjs';
import {imageLpacBinding} from '../util/lpac-bindings.mjs';
import {encodeImageLpacRequest} from '../util/lpac-image-request.mjs';
import {openWorkLease} from '../util/work-lease.mjs';

import {LpacTransport} from './lpac-transport.mjs';
export class LpacImageTransport extends LpacTransport {
  async execute(job,runtime){
    requireThat(runtime?.kind==='node',503,'RUNTIME_REFERENCE_REQUIRED');
    requireThat(runtime.noteFont||!(job.items||[]).some(item=>item.type==='note'),503,'LPAC_NOTES_NOT_VALIDATED');
    const work=path.dirname(job.output);
    requireThat(![work,runtime.path].some(directory=>{const relative=path.relative(directory,this.root),inverse=path.relative(this.root,directory);return !relative||!inverse||!relative.startsWith('..'+path.sep)&&!path.isAbsolute(relative)||!inverse.startsWith('..'+path.sep)&&!path.isAbsolute(inverse);}),503,'LPAC_ROOT_OVERLAP');
    await this.verifyLauncher();await runtime.verify();
    const {request,root}=await this.reserve(),copied=[];
    try{
      const binding=imageLpacBinding(job,root,{maxPixels:this.processing.maxPixels});
      requireThat(binding.outputs.every(output=>path.dirname(output.target)===work),500,'INVALID_WORK_DIRECTORY');
      await this.writeRequest(binding,encodeImageLpacRequest(binding,runtime,work,this.processing));
      requireThat(!this.closed,503,'PROCESSING_CLOSING');
      const facts=await this.spawn(request);
      const lease=openWorkLease(work);
      try{
        requireThat(lease.state==='active',409,'WORK_RETIRED');await runtime.verify();
        for(const output of binding.outputs){const source=path.join(root,'out',output.name),stat=await regular(source,output.maxBytes);requireThat(stat.nlink===1,422,'INVALID_LPAC_OUTPUT');await fs.copyFile(source,output.target,constants.COPYFILE_EXCL);copied.push(output.target);}
      }finally{lease.close();}
      await this.complete(binding);return facts;
    }catch(error){for(const file of copied)await fs.unlink(file);throw error;}finally{this.reservations.delete(path.basename(root));}
    // Failed owned roots/requests remain charged until startup/idle recovery.
    // Unrecorded or unknown data is preserved; no recursive deletion is used.
  }
}
