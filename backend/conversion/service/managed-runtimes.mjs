import path from 'node:path';
import {RuntimeVersions} from './runtime-versions.mjs';
import {regular} from '../../media/util/files.mjs';
import {requireThat} from '../../system/util/errors.mjs';
import {approvedNoteFont} from './note-font.mjs';
// Version selection only. WindowsJobRunner continues to enforce process resources.
// This adapter does not enable LPAC or execute the test-only LPAC launchers.
export class ManagedRuntimes {
  constructor(config){this.versions=new RuntimeVersions(config.root,config);}
  async init({playback=false}={}){await this.versions.init();try{for(const kind of playback?['node','ffmpeg']:['node']){const runtime=await this.acquire(kind);await runtime.release();}return this;}catch(error){await this.versions.close();throw error;}}
  verifyNoteFont(root){return approvedNoteFont(root);}
  async acquire(kind){
    const lease=await this.versions.acquire(kind);
    try{
      const files=kind==='node'?{executable:path.join(lease.path,'node.exe'),worker:path.join(lease.path,'backend/conversion/util/image-worker.mjs')}:{ffmpeg:path.join(lease.path,'ffmpeg.exe'),ffprobe:path.join(lease.path,'ffprobe.exe')};
      for(const file of Object.values(files))await regular(file,268435456);
      requireThat(kind==='node'||kind==='ffmpeg',500,'INVALID_RUNTIME_KIND');
      return Object.freeze({...lease,...files,identity:lease.nativeDigest,noteFont:kind==='node'?await this.verifyNoteFont(lease.path):false});
    }catch(error){await lease.release();throw error;}
  }
  async close(){await this.versions.close();}
}
