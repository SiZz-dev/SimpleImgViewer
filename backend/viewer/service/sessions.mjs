import path from 'node:path';
import fs from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {atomicJson,readJson} from '../../media/util/files.mjs';
import {requireThat,uuidPattern,integer,finite} from '../../system/util/errors.mjs';
export class ViewerSessions {
  constructor(store,media){this.store=store;this.media=media;this.queue=Promise.resolve();}
  async exclusive(action){
    const previous=this.queue;let release;this.queue=new Promise(resolve=>{release=resolve;});
    await previous;try{return await action();}finally{release();}
  }
  async cleanupExpired(){return this.exclusive(()=>this.scan());}
  async scan(){
    const policy=this.store.config.sessions,now=Date.now();let scanned=0,remaining=0,removed=0;
    const directory=await fs.opendir(path.join(this.store.root,'sessions'));
    for await(const entry of directory){
      requireThat(++scanned<=policy.scanLimit,503,'SESSION_STORAGE_SCAN_LIMIT');
      remaining++;
      // Never follow links, recurse into folders, or delete unknown/corrupt files.
      if(!policy.cleanupEnabled||!entry.isFile()||!entry.name.endsWith('.json')||!uuidPattern.test(entry.name.slice(0,-5)))continue;
      const file=path.join(this.store.root,'sessions',entry.name);let session;
      try{session=await readJson(file);}catch{continue;}
      const created=Date.parse(session?.createdAt),expires=Date.parse(session?.expiresAt);
      if(session?.sessionId!==entry.name.slice(0,-5)||!Number.isFinite(created)||!Number.isFinite(expires)||expires<=created||expires>now)continue;
      try{await fs.unlink(file);remaining--;removed++;}catch(e){if(e.code!=='ENOENT')throw e;remaining--;}
    }
    return {scanned,remaining,removed};
  }
  async create(body,actor){
    requireThat(Array.isArray(body.mediaIds)&&body.mediaIds.length>=1&&body.mediaIds.length<=100&&new Set(body.mediaIds).size===body.mediaIds.length,400,'INVALID_SESSION');
    for(const id of body.mediaIds)await this.store.require(id,actor);
    return this.exclusive(async()=>{
    const {remaining}=await this.scan();requireThat(remaining<this.store.config.sessions.maxEntries,429,'SESSION_LIMIT');
    const now=Date.now();const session={sessionId:randomUUID(),ownerId:actor.userId,tenantId:actor.tenantId,mediaIds:body.mediaIds,
      createdAt:new Date(now).toISOString(),expiresAt:new Date(now+this.store.config.sessions.ttlMs).toISOString(),selectedMediaId:body.selectedMediaId||body.mediaIds[0],initialPage:body.initialPage||1,initialTimeSeconds:body.initialTimeSeconds||0,theme:body.theme||'dark',leftPanelOpen:body.leftPanelOpen!==false};
    requireThat(body.mediaIds.includes(session.selectedMediaId)&&integer(session.initialPage,1,10000)&&finite(session.initialTimeSeconds,0,7200)&&['dark','light'].includes(session.theme),400,'INVALID_SESSION');
    await atomicJson(path.join(this.store.root,'sessions',session.sessionId+'.json'),session);return this.view(session.sessionId,actor);
    });
  }
  // Restores where the viewer left off. Only the position fields change; expiry is never extended.
  async update(id,body,actor){
    requireThat(uuidPattern.test(id),404,'SESSION_NOT_FOUND');
    requireThat(Object.keys(body).every(key=>['selectedMediaId','initialPage','initialTimeSeconds'].includes(key)),400,'INVALID_SESSION');
    return this.exclusive(async()=>{
      const file=path.join(this.store.root,'sessions',id+'.json');let session;
      try{session=await readJson(file);}catch{requireThat(false,404,'SESSION_NOT_FOUND');}
      requireThat(session.sessionId===id&&session.ownerId===actor.userId&&session.tenantId===actor.tenantId&&Date.parse(session.expiresAt)>Date.now(),404,'SESSION_NOT_FOUND');
      const next={...session};for(const key of ['selectedMediaId','initialPage','initialTimeSeconds'])if(body[key]!==undefined)next[key]=body[key];
      requireThat(next.mediaIds.includes(next.selectedMediaId)&&integer(next.initialPage,1,10000)&&finite(next.initialTimeSeconds,0,7200),400,'INVALID_SESSION');
      await atomicJson(file,next);
      return Object.fromEntries(['sessionId','expiresAt','selectedMediaId','initialPage','initialTimeSeconds'].map(k=>[k,next[k]]));
    });
  }
  async view(id,actor){
    requireThat(uuidPattern.test(id),404,'SESSION_NOT_FOUND');let session;
    try{session=await readJson(path.join(this.store.root,'sessions',id+'.json'));}catch{requireThat(false,404,'SESSION_NOT_FOUND');}
    requireThat(session.ownerId===actor.userId&&session.tenantId===actor.tenantId&&Date.parse(session.expiresAt)>Date.now(),404,'SESSION_NOT_FOUND');
    // Deleted media are skipped so the rest of the session still opens.
    const media=[];for(const id of session.mediaIds){try{media.push(this.media.public(await this.store.require(id,actor)));}catch(error){if(error.status!==404)throw error;}}
    requireThat(media.length>0,404,'SESSION_NOT_FOUND');
    const view={...Object.fromEntries(['sessionId','createdAt','expiresAt','selectedMediaId','initialPage','initialTimeSeconds','theme','leftPanelOpen'].map(k=>[k,session[k]])),media,positionState:'ready'};
    if(!media.some(item=>item.mediaId===view.selectedMediaId))Object.assign(view,{selectedMediaId:media[0].mediaId,initialPage:1,initialTimeSeconds:0});
    return view;
  }
}
