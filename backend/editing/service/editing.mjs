import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {readJson,atomicJson,regular,detect,digest} from '../../media/util/files.mjs';
import {requireThat,uuidPattern,finite} from '../../system/util/errors.mjs';
export class EditingService {
  constructor(store,media){this.store=store;this.media=media;this.locks=new Map();}
  docPath(id,page){return path.join(this.store.root,'editing',id+'-'+page+'.json');}
  async document(id,page,actor,runtime=undefined){
    const info=runtime===undefined?await this.media.page(id,page,actor):await this.media.pageWithRuntime(id,page,actor,runtime);
    let doc;try{doc=await readJson(this.docPath(id,page));}catch(e){if(e.code!=='ENOENT')throw e;doc={mediaVersion:info.mediaVersion,page,coordinateSpaceId:info.coordinateSpaceId,revision:0,items:[],undo:[],redo:[]};}
    requireThat(doc.mediaVersion===info.mediaVersion&&doc.coordinateSpaceId===info.coordinateSpaceId,409,'STALE_COORDINATES');return {info,doc};
  }
  public(doc){return {...Object.fromEntries(['mediaVersion','page','coordinateSpaceId','revision','items'].map(k=>[k,doc[k]])),history:{canUndo:doc.undo.length>0,canRedo:doc.redo.length>0,undoCount:doc.undo.length,redoCount:doc.redo.length}};}
  async change(id,page,actor,body,action,itemId){
    const key=id+':'+page;const previous=this.locks.get(key)||Promise.resolve();let release;const gate=new Promise(resolve=>{release=resolve;});this.locks.set(key,gate);await previous;
    try{
      const {info,doc}=await this.document(id,page,actor);const permitted=item=>this.store.config.features[item.type==='stamp'?'stamps':'annotations'];
      requireThat(body.mediaVersion===doc.mediaVersion&&body.coordinateSpaceId===doc.coordinateSpaceId&&body.expectedRevision===doc.revision,409,'EDIT_CONFLICT');
      if(action==='undo'||action==='redo'){
        const from=action==='undo'?doc.undo:doc.redo,to=action==='undo'?doc.redo:doc.undo;requireThat(from.length>0,409,'HISTORY_EMPTY');requireThat([...doc.items,...from.at(-1)].every(permitted),403,'FEATURE_DISABLED');to.push(structuredClone(doc.items));doc.items=from.pop();
      }else{
        const before=structuredClone(doc.items);
        const existing=doc.items.find(item=>item.id===itemId);if(existing)requireThat(permitted(existing),403,'FEATURE_DISABLED');
        if(action==='delete'){requireThat(doc.items.some(i=>i.id===itemId),404,'ANNOTATION_NOT_FOUND');doc.items=doc.items.filter(i=>i.id!==itemId);}
        else{
          requireThat(['note','stamp'].includes(body.type)&&[body.x,body.y,body.width,body.height].every(Number.isFinite)
            &&body.x>=0&&body.y>=0&&body.width>=1&&body.height>=1&&body.width*body.height<=4194304&&body.x+body.width<=info.coordinateWidth&&body.y+body.height<=info.coordinateHeight,400,'INVALID_ANNOTATION');
          requireThat(body.type!=='note'||typeof body.text==='string'&&body.text.length<=500,400,'INVALID_NOTE');
          requireThat(permitted(body),403,'FEATURE_DISABLED');
          if(body.type==='stamp'){requireThat(this.store.config.features.stamps,403,'FEATURE_DISABLED');await this.stamp(body.stampId,actor);}
          const item={id:itemId||randomUUID(),type:body.type,x:body.x,y:body.y,width:body.width,height:body.height,text:body.type==='note'?body.text:null,stampId:body.type==='stamp'?body.stampId:null};
          if(action==='create'){requireThat(doc.items.length<200,413,'ANNOTATION_LIMIT');doc.items.push(item);}
          else{const index=doc.items.findIndex(i=>i.id===itemId);requireThat(index>=0,404,'ANNOTATION_NOT_FOUND');doc.items[index]=item;}
        }
        doc.undo.push(before);doc.undo=doc.undo.slice(-20);doc.redo=[];
      }
      doc.revision++;await this.store.require(id,actor);await atomicJson(this.docPath(id,page),doc);return this.public(doc);
    }finally{release();if(this.locks.get(key)===gate)this.locks.delete(key);}
  }
  async stamp(id,actor){
    requireThat(uuidPattern.test(id||''),404,'STAMP_NOT_FOUND');const record=await readJson(path.join(this.store.root,'stamps',id+'.json'));
    requireThat(record.tenantId===actor.tenantId&&record.ownerId===actor.userId,404,'STAMP_NOT_FOUND');return {record,file:path.join(this.store.root,'stamps',id+'.png')};
  }
  async listStamps(actor){const results=[];for(const name of (await fs.readdir(path.join(this.store.root,'stamps'))).filter(n=>n.endsWith('.json'))){const s=await readJson(path.join(this.store.root,'stamps',name));if(s.tenantId===actor.tenantId&&s.ownerId===actor.userId)results.push({stampId:s.stampId,fileName:s.fileName,width:s.width,height:s.height,url:'/api/v1/stamps/'+s.stampId+'/content'});}return results;}
  async createStamp(temp,fileName,actor){
    return this.media.images.withRuntime(runtime=>this.createStampWithRuntime(temp,fileName,actor,runtime));
  }
  async createStampWithRuntime(temp,fileName,actor,runtime){
    requireThat(this.store.config.features.stamps&&this.store.config.features.stampUpload,403,'FEATURE_DISABLED');requireThat((await this.listStamps(actor)).length<100,413,'STAMP_LIMIT');
    await regular(temp,2097152);const handle=await fs.open(temp,'r');const header=Buffer.alloc(32);try{await handle.read(header,0,32,0);}finally{await handle.close();}
    requireThat(['image/png','image/jpeg','image/webp'].includes(detect(header)?.[1]),415,'UNSUPPORTED_STAMP');
    const sourceHash=await digest(temp),job=await this.store.work.create();
    try{
      const facts=await this.media.images.run({operation:'preview',input:temp,page:1,edge:1024,maxPixels:4194304,output:path.join(job,'preview.png'),thumbnail:path.join(job,'thumbnail.png')},{runtime});
      requireThat(this.store.config.features.stamps&&this.store.config.features.stampUpload,403,'FEATURE_DISABLED');requireThat(await digest(temp)===sourceHash,409,'STAMP_CHANGED');if(runtime)await runtime.verify();
      const id=randomUUID();await fs.rename(path.join(job,'preview.png'),path.join(this.store.root,'stamps',id+'.png'));
      const s={stampId:id,fileName,ownerId:actor.userId,tenantId:actor.tenantId,width:facts.previewWidth,height:facts.previewHeight};await atomicJson(path.join(this.store.root,'stamps',id+'.json'),s);return {stampId:id,fileName,width:s.width,height:s.height,url:'/api/v1/stamps/'+id+'/content'};
    }finally{await this.media.removeWork(job);}
  }
  async export(id,page,actor,body,edited){
    return this.media.images.withRuntime(runtime=>this.exportWithRuntime(id,page,actor,body,edited,runtime));
  }
  async exportWithRuntime(id,page,actor,body,edited,runtime){
    requireThat(edited?this.store.config.features.exportEdited:this.store.config.features.cropAndSave,403,'FEATURE_DISABLED');
    const {info,doc}=await this.document(id,page,actor,runtime);requireThat(body.mediaVersion===info.mediaVersion&&body.coordinateSpaceId===info.coordinateSpaceId,409,'STALE_COORDINATES');
    const x=Math.floor(body.x),y=Math.floor(body.y),width=Math.ceil(body.width),height=Math.ceil(body.height);
    requireThat([body.x,body.y,body.width,body.height].every(Number.isFinite)&&x>=0&&y>=0&&width>=1&&height>=1&&x+width<=info.coordinateWidth&&y+height<=info.coordinateHeight&&width*height<=8000000,400,'INVALID_CROP');
    requireThat(!edited||body.expectedRevision===doc.revision,409,'EDIT_CONFLICT');
    const brightness=edited?body.brightness??100:100,rotation=edited?body.rotation??0:0;requireThat(finite(brightness,25,200)&&[0,90,180,270].includes(rotation),400,'INVALID_EDIT');
    requireThat((brightness===100||this.store.config.features.brightness)&&(rotation===0||this.store.config.features.rotation),403,'FEATURE_DISABLED');
    const record=await this.store.require(id,actor);const work=await this.store.work.create();
    try{
      // Items of a disabled feature are kept in the document but never composed.
      const permitted=item=>this.store.config.features[item.type==='stamp'?'stamps':'annotations'];
      const items=[];if(edited&&body.includeAnnotations)for(const item of doc.items.filter(permitted))items.push(item.type==='stamp'?{...item,file:(await this.stamp(item.stampId,actor)).file}:item);
      requireThat(items.reduce((total,item)=>total+item.width*item.height,0)<=16000000,413,'EDIT_PIXEL_LIMIT');
      await this.media.images.run({operation:'export',input:this.store.file(record),page,output:path.join(work,'export.png'),crop:{left:x,top:y,width,height},brightness,rotation,items},{runtime});
      const current=await this.store.require(id,actor);requireThat(current.mediaVersion===record.mediaVersion,409,'MEDIA_CHANGED');await this.store.verify(record);if(runtime)await runtime.verify();return await fs.readFile(path.join(work,'export.png'));
    }finally{await this.media.removeWork(work);}
  }
}
