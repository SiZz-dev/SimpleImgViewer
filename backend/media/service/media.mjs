import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {readJson,atomicJson,digest,regular,privateRoot} from '../util/files.mjs';
import {requireThat,integer} from '../../system/util/errors.mjs';
export class MediaService {
  constructor(store,images,playback){this.store=store;this.images=images;this.playback=playback;this.running=new Map();}
  public(record){return {...Object.fromEntries(['mediaId','mediaVersion','fileName','mediaType','originalMimeType','sizeBytes'].map(k=>[k,record[k]])),
    permissions:{view:true,annotate:true,stamp:true,cropAndSave:true,exportEdited:true}};}
  sourceId(record,page,runtime=null,edge=this.store.config.processing.previewEdge){return createHash('sha256').update(record.sha256+record.mediaVersion+':sharp-node-v2:'+this.images.identityFor(runtime)+':'+page+':'+edge).digest('hex');}
  cache(record,page,runtime=null){return path.join(this.store.root,'derived',record.mediaId+'-'+this.sourceId(record,page,runtime));}
  async page(id,page,actor){
    return this.images.withRuntime(runtime=>this.pageWithRuntime(id,page,actor,runtime));
  }
  async pageWithRuntime(id,page,actor,runtime){
    requireThat(integer(page,1,10000),400,'INVALID_PAGE');const record=await this.store.require(id,actor);requireThat(record.mediaType==='image',415,'NOT_IMAGE');
    await this.store.verify(record);const target=this.cache(record,page,runtime);const key=record.mediaVersion+':'+page+':'+this.images.identityFor(runtime);
    if(!this.running.has(key))this.running.set(key,this.prepare(record,page,target,runtime,actor).finally(()=>this.running.delete(key)));
    const facts=await this.running.get(key);const current=await this.store.require(id,actor);requireThat(current.mediaVersion===record.mediaVersion,409,'MEDIA_CHANGED');await this.store.verify(record);
    const sourceId=this.sourceId(record,page,runtime);const url='/api/v1/media/'+id+'/sources/'+sourceId;
    return {mediaId:id,mediaVersion:record.mediaVersion,page,state:'ready',pageCount:facts.pageCount,coordinateSpaceId:'original:'+record.mediaVersion+':'+page+':upright-v1',
      originalCoordinatesAvailable:true,editingReady:true,coordinateWidth:facts.width,coordinateHeight:facts.height,encodedWidth:facts.width,encodedHeight:facts.height,
      displayWidth:facts.previewWidth,displayHeight:facts.previewHeight,exifOrientation:facts.orientation,
      previewToOriginal:{a:facts.width/facts.previewWidth,b:0,c:0,d:facts.height/facts.previewHeight,e:0,f:0},
      source:{sourceId,role:'preview',page,mimeType:'image/png',width:facts.previewWidth,height:facts.previewHeight,url:url+'/content?page='+page},
      thumbnail:{sourceId,role:'thumbnail',page,mimeType:'image/png',url:url+'/thumbnail?page='+page}};
  }
  async prepare(record,page,target,runtime,actor){
    const sourceId=this.sourceId(record,page,runtime);
    try{const cached=await readJson(path.join(target,'facts.json'));requireThat(cached.sourceId===sourceId&&cached.mediaVersion===record.mediaVersion&&cached.page===page&&cached.runtimeIdentity===this.images.identityFor(runtime),409,'CACHE_BINDING_CHANGED');await regular(path.join(target,'preview.png'),33554432);await this.store.cache.touch(target);return cached;}catch(e){if(e.code!=='ENOENT'&&e.status!==404)throw e;}
    const work=await this.store.work.create();
    try{
      const facts=await this.images.run({operation:'preview',input:this.store.file(record),page,edge:this.store.config.processing.previewEdge,output:path.join(work,'preview.png'),thumbnail:path.join(work,'thumbnail.png')},{runtime});
      await atomicJson(path.join(work,'facts.json'),{...facts,sourceId,mediaVersion:record.mediaVersion,page,previewEdge:this.store.config.processing.previewEdge,runtimeIdentity:this.images.identityFor(runtime)});
      await this.store.work.publish(work,target,this.store.cache,async()=>{const current=await this.store.require(record.mediaId,actor);requireThat(current.mediaVersion===record.mediaVersion,409,'MEDIA_CHANGED');await this.store.verify(record);if(runtime)await runtime.verify();});return facts;
    }finally{await this.removeWork(work);}
  }
  async removeWork(work){
    await this.store.work.release(work);
  }
  async source(id,page,sourceId,role,actor){requireThat(integer(page,1,10000)&&/^[a-f0-9]{64}$/.test(sourceId||''),404,'SOURCE_NOT_FOUND');const record=await this.store.require(id,actor);requireThat(record.mediaType==='image',415,'NOT_IMAGE');await this.store.verify(record);const directory=path.join(this.store.root,'derived',id+'-'+sourceId),facts=await readJson(path.join(directory,'facts.json'));requireThat(facts.sourceId===sourceId&&facts.mediaVersion===record.mediaVersion&&facts.page===page&&/^[a-f0-9]{64}$/.test(facts.runtimeIdentity||'')&&integer(facts.previewEdge,64,4096)&&this.sourceId(record,page,{identity:facts.runtimeIdentity},facts.previewEdge)===sourceId,404,'SOURCE_NOT_FOUND');const file=path.join(directory,role==='thumbnail'?'thumbnail.png':'preview.png');await regular(file,33554432);await this.store.cache.touch(directory);return file;}
}
