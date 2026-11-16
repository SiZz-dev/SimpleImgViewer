import fs from 'node:fs/promises';
import path from 'node:path';
import {readJson} from '../../media/util/files.mjs';
import {requireThat,uuidPattern} from '../../system/util/errors.mjs';

const imageName=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}-[a-f0-9]{64}$/;
const playbackName=/^playback-[a-f0-9]{64}$/;
export class CacheStorage {
  constructor(root,policy){this.root=path.join(root,'derived');this.policy=policy;this.entries=new Map();this.bytes=0;this.queue=Promise.resolve();}
  kind(name){return imageName.test(name)?'image':playbackName.test(name)?'playback':null;}
  async inspect(directory,kind){
    const stat=await fs.lstat(directory);if(!stat.isDirectory()||stat.isSymbolicLink()||await fs.realpath(directory)!==directory)return null;
    const expected=kind==='image'?['facts.json','preview.png','thumbnail.png']:['facts.json','playback'];
    const names=[];for await(const entry of await fs.opendir(directory)){names.push(entry.name);if(names.length>5)return null;}names.sort();
    if(expected.some(name=>!names.includes(name))||names.some(name=>![...expected,'lease.sqlite','retired'].includes(name)))return null;
    const files=[];let bytes=0;
    for(const name of names){
      const file=path.join(directory,name),s=await fs.lstat(file);
      if(!s.isFile()||s.isSymbolicLink()||s.nlink!==1||await fs.realpath(file)!==file||s.size===0&&name!=='retired')return null;
      if(s.size>(name==='playback'?268435456:name.endsWith('.png')?33554432:1048576))return null;
      files.push({name,size:s.size,mtimeMs:s.mtimeMs,ino:s.ino});bytes+=s.size;
    }
    const facts=await readJson(path.join(directory,'facts.json'));
    if(kind==='image'){
      if(!Number.isSafeInteger(facts?.width)||!Number.isSafeInteger(facts?.height)||facts.width<1||facts.height<1||facts.width*facts.height>40000000)return null;
    }else if(!facts||facts.state!=='ready'||!uuidPattern.test(facts.mediaVersion||'')||!/^([a-f0-9]{64})$/.test(facts.sourceId||'')
      ||!['audio/mpeg','video/webm'].includes(facts.mimeType)||facts.sizeBytes!==files.find(f=>f.name==='playback').size)return null;
    return {directory,kind,files,bytes,lastUsed:stat.mtimeMs,sourceId:facts.sourceId};
  }
  async init(){
    const entries=new Map(),result={scanned:0,removed:0,preserved:0,bytes:0};
    // Complete the bounded inventory before deleting anything.
    const directory=await fs.opendir(this.root);
    for await(const entry of directory){
      requireThat(++result.scanned<=this.policy.scanLimit,503,'CACHE_STORAGE_SCAN_LIMIT');
      const kind=this.kind(entry.name);if(!kind||!entry.isDirectory()){result.preserved++;continue;}
      let item;try{item=await this.inspect(path.join(this.root,entry.name),kind);}catch{result.preserved++;continue;}
      if(!item||kind==='playback'&&entry.name!=='playback-'+item.sourceId){result.preserved++;continue;}
      entries.set(entry.name,item);result.bytes+=item.bytes;
    }
    if(this.policy.cleanupOnStartup){
      for(const [name,item] of [...entries].sort((a,b)=>a[1].lastUsed-b[1].lastUsed)){
        if(Date.now()-item.lastUsed<this.policy.maxAgeMs&&result.bytes<=this.policy.maxBytes)continue;
        const current=await this.inspect(item.directory,item.kind);
        requireThat(current&&JSON.stringify(current.files)===JSON.stringify(item.files),503,'CACHE_CHANGED_DURING_CLEANUP');
        // Startup only: no HTTP listeners or conversion publishers are active.
        for(const file of current.files)await fs.unlink(path.join(item.directory,file.name));
        await fs.rmdir(item.directory);entries.delete(name);result.bytes-=item.bytes;result.removed++;
      }
    }
    this.entries=entries;this.bytes=result.bytes;return result;
  }
  async touch(directory){
    requireThat(path.dirname(directory)===this.root,500,'INVALID_CACHE_PATH');
    const now=new Date();await fs.utimes(directory,now,now);const item=this.entries.get(path.basename(directory));if(item)item.lastUsed=now.getTime();
  }
  async publish(work,target,action){
    const previous=this.queue;let release;this.queue=new Promise(resolve=>{release=resolve;});await previous;
    try{
      const name=path.basename(target),kind=this.kind(name);requireThat(path.dirname(target)===this.root&&kind,500,'INVALID_CACHE_PATH');
      const item=await this.inspect(work,kind);requireThat(item,500,'INVALID_CACHE_OUTPUT');
      requireThat(kind!=='playback'||name==='playback-'+item.sourceId,500,'INVALID_CACHE_OUTPUT');
      requireThat(this.bytes+item.bytes<=this.policy.maxBytes,507,'CACHE_CAPACITY_REACHED');
      try{await fs.lstat(target);requireThat(false,409,'CACHE_ALREADY_EXISTS');}catch(error){if(error.code!=='ENOENT')throw error;}
      await action();this.entries.set(name,{...item,directory:target,lastUsed:Date.now()});this.bytes+=item.bytes;
    }finally{release();}
  }
}
