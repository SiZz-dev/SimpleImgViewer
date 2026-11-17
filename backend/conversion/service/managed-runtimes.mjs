import path from 'node:path';
import {RuntimeVersions} from './runtime-versions.mjs';
import {regular} from '../../media/util/files.mjs';
import {ApiError,requireThat} from '../../system/util/errors.mjs';
import {approvedNoteFont} from './note-font.mjs';
import {RuntimeVerifierPool} from './runtime-verifier-pool.mjs';
// Version selection only. WindowsJobRunner continues to enforce process resources.
// This adapter does not enable LPAC or execute the test-only LPAC launchers.
export class ManagedRuntimes {
  constructor(config){
    this.versions=new RuntimeVersions(config.root,config);this.operations=new Set();this.closing=false;
    const policy=config.verification??{mode:'main'};requireThat(['main','worker'].includes(policy.mode),400,'INVALID_RUNTIME_VERIFIER_POLICY');
    if(policy.mode==='worker'){this.verifier=new RuntimeVerifierPool({threads:1,maxQueue:policy.maxQueue??8,timeoutMs:policy.timeoutMs??30000});this.versions.verifyTree=this.verifier.verify.bind(this.verifier);}
  }
  async init({playback=false}={}){try{await this.versions.init();for(const kind of playback?['node','ffmpeg']:['node']){const runtime=await this.acquire(kind);await runtime.release();}return this;}catch(error){await this.close();throw error;}}
  verifyNoteFont(root){return approvedNoteFont(root);}
  acquire(kind){
    if(this.closing)return Promise.reject(new ApiError(503,'RUNTIME_MANAGER_CLOSING'));
    const operation=this.acquireChecked(kind);this.operations.add(operation);operation.then(()=>this.operations.delete(operation),()=>this.operations.delete(operation));return operation;
  }
  async acquireChecked(kind){
    const lease=await this.versions.acquire(kind);
    try{
      requireThat(!this.closing,503,'RUNTIME_MANAGER_CLOSING');
      const files=kind==='node'?{executable:path.join(lease.path,'node.exe'),worker:path.join(lease.path,'backend/conversion/util/image-worker.mjs')}:{ffmpeg:path.join(lease.path,'ffmpeg.exe'),ffprobe:path.join(lease.path,'ffprobe.exe')};
      for(const file of Object.values(files))await regular(file,268435456);
      requireThat(kind==='node'||kind==='ffmpeg',500,'INVALID_RUNTIME_KIND');
      const noteFont=kind==='node'?await this.verifyNoteFont(lease.path):false;requireThat(!this.closing,503,'RUNTIME_MANAGER_CLOSING');
      return Object.freeze({...lease,...files,identity:lease.nativeDigest,noteFont});
    }catch(error){await lease.release();throw error;}
  }
  async close(){this.closing=true;await this.verifier?.close();await Promise.allSettled([...this.operations]);await this.versions.close();}
}
