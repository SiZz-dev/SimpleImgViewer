import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {RuntimeCatalog} from '../repository/runtime-catalog.mjs';
import {runtimeDescriptor,safeRuntimePath,verifyRuntimeTree} from '../util/runtime-inventory.mjs';
import {requireThat} from '../../system/util/errors.mjs';
export class RuntimeVersions {
  constructor(root,{maxBytes=1073741824,maxVersions=10}={}){this.root=path.resolve(root);this.policy={maxBytes,maxVersions};this.usage=new Map();this.verifications=new Map();this.queue=Promise.resolve();this.closed=true;}
  serial(action){const next=this.queue.then(action);this.queue=next.catch(()=>{});return next;}
  async init(){
    requireThat(this.closed&&!this.opening,409,'RUNTIME_MANAGER_ALREADY_OPEN');this.opening=true;
    try{
    requireThat(Number.isSafeInteger(this.policy.maxBytes)&&this.policy.maxBytes>0&&Number.isSafeInteger(this.policy.maxVersions)&&this.policy.maxVersions>0,400,'INVALID_RUNTIME_POLICY');
    await safeRuntimePath(this.root,{missing:true});await fs.mkdir(this.root,{recursive:true});await safeRuntimePath(this.root);
    this.catalog=new RuntimeCatalog(this.root);await this.catalog.open();
    try{
      const versions=path.join(this.root,'versions');await safeRuntimePath(versions,{missing:true});await fs.mkdir(versions,{recursive:true});
      for(const entry of await fs.readdir(this.root))requireThat(['owner.sqlite','owner.sqlite-journal','catalog.sqlite','catalog.sqlite-journal','versions'].includes(entry),500,'UNKNOWN_RUNTIME_ENTRY');
      const rows=this.catalog.list(),ids=new Set(rows.map(row=>row.id));
      for(const entry of await fs.readdir(versions)){requireThat(ids.has(entry),500,'UNKNOWN_RUNTIME_VERSION');}
      for(const row of rows){this.folder(row.id);requireThat(['publishing','ready','retiring','deleting','retired','quarantined'].includes(row.state),500,'INVALID_RUNTIME_CATALOG');requireThat(runtimeDescriptor(JSON.parse(row.descriptor)).kind===row.kind,500,'INVALID_RUNTIME_CATALOG');if(row.state==='publishing')this.catalog.state(row.id,'quarantined');if(['ready','retiring','deleting'].includes(row.state))await this.useFile(row.id);}
      for(const kind of ['node','ffmpeg']){const id=this.catalog.active(kind);if(id)requireThat(this.catalog.get(id)?.kind===kind&&this.catalog.get(id)?.state==='ready',500,'INVALID_RUNTIME_CATALOG');}
      this.closed=false;this.recovery=await this.recover();return this;
    }catch(error){this.closed=true;this.catalog.close();throw error;}
    }finally{this.opening=false;}
  }
  ready(){requireThat(!this.closed,503,'RUNTIME_MANAGER_CLOSED');}
  folder(id){requireThat(/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id||''),400,'INVALID_RUNTIME_VERSION');return path.join(this.root,'versions',id);}
  async useFile(id){const file=path.join(this.folder(id),'runtime.use');await safeRuntimePath(file,{missing:true});try{await fs.writeFile(file,'1',{flag:'wx'});}catch(error){if(error.code!=='EEXIST')throw error;}const stat=await fs.lstat(file);requireThat(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1&&stat.size===1&&await fs.readFile(file,'utf8')==='1',500,'UNSAFE_RUNTIME_USE');return file;}
  async publish({source,descriptor,prepare}){return this.serial(async()=>{
    this.ready();const approved=runtimeDescriptor(descriptor);requireThat(typeof prepare==='function',400,'RUNTIME_PREPARER_REQUIRED');
    const rows=this.catalog.list().filter(row=>row.state!=='retired');requireThat(rows.length<this.policy.maxVersions&&rows.reduce((n,row)=>n+runtimeDescriptor(JSON.parse(row.descriptor)).bytes,0)+approved.bytes<=this.policy.maxBytes,507,'RUNTIME_CAPACITY_REACHED');
    source=path.resolve(source);requireThat(source!==this.root&&!source.startsWith(this.root+path.sep),400,'INVALID_RUNTIME_SOURCE');await verifyRuntimeTree(source,approved);
    const id=randomUUID(),directory=this.folder(id),payload=path.join(directory,'payload');this.catalog.add(id,approved);
    try{await fs.mkdir(directory);await this.useFile(id);requireThat(await prepare(source,payload)===approved.nativeDigest,500,'RUNTIME_PREPARATION_MISMATCH');await verifyRuntimeTree(payload,approved);this.catalog.state(id,'ready');return {id,kind:approved.kind,nativeDigest:approved.nativeDigest};}
    catch(error){this.catalog.state(id,'quarantined');throw error;}
  });}
  async activate(id){return this.serial(async()=>{this.ready();const row=this.catalog.get(id);requireThat(row?.state==='ready',409,'RUNTIME_NOT_READY');await verifyRuntimeTree(path.join(this.folder(id),'payload'),runtimeDescriptor(JSON.parse(row.descriptor)));this.catalog.activate(row.kind,id);});}
  verifyTree(payload,descriptor){return verifyRuntimeTree(payload,descriptor);}
  checkPayload(context){
    if(!this.verifications.has(context.id)){const checking=this.verifyTree(context.payload,context.descriptor);this.verifications.set(context.id,checking);checking.then(()=>this.verifications.delete(context.id),()=>this.verifications.delete(context.id));}
    return this.verifications.get(context.id);
  }
  async acquire(kind){
    // Reserve the generation before releasing the metadata lock. Disk hashing
    // may overlap for different generations and coalesces only while in flight.
    const context=await this.serial(async()=>{this.ready();const id=this.catalog.active(kind),row=id&&this.catalog.get(id);requireThat(row?.state==='ready'&&row.kind===kind,503,'RUNTIME_NOT_ACTIVE');const descriptor=runtimeDescriptor(JSON.parse(row.descriptor)),payload=path.join(this.folder(id),'payload'),useFile=await this.useFile(id);this.usage.set(id,(this.usage.get(id)||0)+1);return {id,kind:row.kind,descriptor,payload,useFile};});
    const {id,payload,useFile,descriptor}=context;let released=false;
    const release=()=>this.serial(async()=>{if(!released){released=true;this.usage.set(id,this.usage.get(id)-1);}});
    try{await this.checkPayload(context);}catch(error){await release();throw error;}
    return Object.freeze({id,kind:context.kind,path:payload,useFile,nativeDigest:descriptor.nativeDigest,release,verify:async()=>{
      await this.serial(async()=>{this.ready();requireThat(!released,409,'RUNTIME_REFERENCE_RELEASED');this.usage.set(id,this.usage.get(id)+1);});
      try{await this.checkPayload(context);requireThat(!released,409,'RUNTIME_REFERENCE_RELEASED');}
      finally{await this.serial(async()=>{this.usage.set(id,this.usage.get(id)-1);});}
    }});
  }
  async retire(id){return this.serial(async()=>{
    this.ready();const row=this.catalog.get(id);requireThat(row,404,'RUNTIME_VERSION_NOT_FOUND');requireThat(this.catalog.active(row.kind)!==id,409,'RUNTIME_ACTIVE');requireThat(!(this.usage.get(id)>0),409,'RUNTIME_IN_USE');
    if(row.state==='retired')return {id,state:'retired'};requireThat(['ready','retiring','deleting'].includes(row.state),409,'RUNTIME_NOT_RETIRABLE');
    const descriptor=runtimeDescriptor(JSON.parse(row.descriptor)),directory=this.folder(id),payload=path.join(directory,'payload'),removed=path.join(directory,'retired');
    await safeRuntimePath(directory);
    if(row.state==='ready')this.catalog.state(id,'retiring');let barrier;
    try{barrier=await fs.open(await this.useFile(id),'r+');}catch(error){if(['EPERM','EACCES','EBUSY'].includes(error.code))return {id,state:row.state==='deleting'?'deleting':'retiring',busy:true};throw error;}
    try{
    if(row.state!=='deleting'){
      const original=await fs.lstat(payload).then(()=>true,error=>{if(error.code==='ENOENT')return false;throw error;});
      if(original){await verifyRuntimeTree(payload,descriptor);this.catalog.state(id,'retiring');try{await fs.rename(payload,removed);}catch(error){if(['EPERM','EACCES','EBUSY'].includes(error.code))return {id,state:'retiring',busy:true};throw error;}}
      else await verifyRuntimeTree(removed,descriptor);
      this.catalog.state(id,'deleting');
    }
    // Restartable, inventory-only deletion. Unknown/corrupt/link entries are preserved.
    await verifyRuntimeTree(removed,descriptor,{partial:true,absent:true});
    for(const file of descriptor.files){const target=path.join(removed,file.path);await safeRuntimePath(target,{missing:true});await fs.unlink(target).catch(error=>{if(error.code!=='ENOENT')throw error;});}
    for(const name of [...descriptor.directories].sort((a,b)=>b.split('/').length-a.split('/').length)){await fs.rmdir(path.join(removed,name)).catch(error=>{if(error.code!=='ENOENT')throw error;});}
    await fs.rmdir(removed).catch(error=>{if(error.code!=='ENOENT')throw error;});this.catalog.state(id,'retired');return {id,state:'retired'};
    }finally{await barrier.close();}
  });}
  async list(){return this.serial(async()=>{this.ready();return this.catalog.list().map(row=>{const descriptor=runtimeDescriptor(JSON.parse(row.descriptor));return {id:row.id,kind:row.kind,state:row.state,nativeDigest:descriptor.nativeDigest,bytes:descriptor.bytes,active:this.catalog.active(row.kind)===row.id,inUse:this.usage.get(row.id)||0};});});}
  async recover(){const rows=(await this.list()).filter(row=>['retiring','deleting'].includes(row.state)),results=[];for(const row of rows){try{results.push(await this.retire(row.id));}catch(error){results.push({id:row.id,state:row.state,preserved:true,code:error.code||'RUNTIME_RECOVERY_FAILED'});}}return results;}
  async close(){return this.serial(async()=>{if(this.closed)return;requireThat(![...this.usage.values()].some(n=>n>0),409,'RUNTIME_IN_USE');this.closed=true;this.catalog.close();});}
}
