import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {privateRoot,regular,digest,atomicJson,readJson,detect} from '../util/files.mjs';
import {requireThat,uuidPattern} from '../../system/util/errors.mjs';
import {TemporaryFiles} from '../service/temporary-files.mjs';
import {WorkDirectories} from '../../conversion/service/work-directories.mjs';
import {CacheStorage} from '../../conversion/service/cache-storage.mjs';
import {UploadCapacity} from '../service/upload-capacity.mjs';
export class MediaStore {
  constructor(config){this.config=config;this.root=config.storage.root;}
  async init(){for(const name of ['originals','derived','multipart','stamps','editing','sessions'])await privateRoot(path.join(this.root,name));
    this.temporary=new TemporaryFiles(this.root,this.config.temporary);this.recovery=await this.temporary.init();
    try{this.capacity=new UploadCapacity(this.config);this.originalInventory=await this.capacity.init();
      this.work=new WorkDirectories(this.root,this.config.temporary);this.workRecovery=await this.work.init();
      this.playbackWork=new WorkDirectories(this.root,this.config.temporary,'playback');this.playbackRecovery=await this.playbackWork.init();
      this.cache=new CacheStorage(this.root,this.config.cache);this.cacheCleanup=await this.cache.init();
    }catch(error){await this.temporary.close();throw error;}}
  file(record){return path.join(this.root,'originals',record.mediaId,'source');}
  async require(id,actor){
    requireThat(uuidPattern.test(id),404,'MEDIA_NOT_FOUND');let record;
    try{record=await readJson(path.join(this.root,'originals',id,'record.json'));}catch{requireThat(false,404,'MEDIA_NOT_FOUND');}
    requireThat(record.mediaId===id&&record.tenantId===actor.tenantId&&(record.ownerId===actor.userId||this.config.development),404,'MEDIA_NOT_FOUND');
    requireThat(this.config.features[record.mediaType]!==false,403,'FEATURE_DISABLED');
    return record;
  }
  async verify(record){await regular(this.file(record),this.config.upload.maxBytes);requireThat(await digest(this.file(record))===record.sha256,409,'ORIGINAL_CHANGED');}
  async register(temp,fileName,actor){
    const stat=await regular(temp,this.config.upload.maxBytes);this.capacity.check(temp,stat.size);const handle=await fs.open(temp,'r');let header;
    try{header=Buffer.alloc(Math.min(512,stat.size));await handle.read(header,0,header.length,0);}finally{await handle.close();}
    const type=detect(header);requireThat(type&&type[0]!=='unsupported',415,'UNSUPPORTED_MEDIA');
    requireThat(this.config.features[type[0]]!==false,403,'FEATURE_DISABLED');
    requireThat(typeof fileName==='string'&&fileName.length>0&&fileName.length<=255&&!/[\x00-\x1f]/.test(fileName),400,'INVALID_FILENAME');
    const record={mediaId:randomUUID(),mediaVersion:randomUUID(),tenantId:actor.tenantId,ownerId:actor.userId,fileName:path.basename(fileName),mediaType:type[0],originalMimeType:type[1],sizeBytes:stat.size,sha256:await digest(temp),createdAt:new Date().toISOString()};
    const dir=await fs.mkdir(path.join(this.root,'originals',record.mediaId),{mode:0o700}).then(()=>path.join(this.root,'originals',record.mediaId));
    await fs.rename(temp,path.join(dir,'source'));
    // Count the physical original even if writing its record fails afterwards.
    this.capacity.commit(temp,stat.size);await atomicJson(path.join(dir,'record.json'),record);
    return record;
  }
  async temp(bytes=this.config.upload.maxBytes,original=true){
    const file=this.temporary.allocate();
    try{await this.capacity.reserve(file,bytes,original);return file;}catch(error){await this.temporary.release(file);throw error;}
  }
  async releaseTemp(file){await this.temporary.release(file);this.capacity.release(file);}
  async close(){await Promise.all([this.work?.close(),this.playbackWork?.close()]);await this.temporary?.close();}
}
