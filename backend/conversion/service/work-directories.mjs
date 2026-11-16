import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {privateRoot} from '../../media/util/files.mjs';
import {requireThat,uuidPattern} from '../../system/util/errors.mjs';
import {openWorkLease} from '../util/work-lease.mjs';

export class WorkDirectories {
  constructor(root,policy,kind='image'){
    requireThat(['image','playback'].includes(kind),500,'INVALID_WORK_KIND');
    this.root=path.join(root,'derived',kind+'-work');this.policy=policy;this.active=new Set();this.closing=false;this.waiters=[];
    this.outputs=new Set(kind==='image'?['preview.png','thumbnail.png','export.png','facts.json','job.json']:['playback','facts.json']);
  }
  async retire(work,lease){
    // The native launcher checks this marker while holding the same file handle
    // exclusively. Commit it before releasing our SQLite handle, including crashes.
    try{await fs.writeFile(path.join(work,'retired'),'',{flag:'wx',mode:0o600});}catch(error){if(error.code!=='EEXIST')throw error;}
    lease.retire();
  }
  async init(){
    await privateRoot(this.root);const result={scanned:0,recovered:0,busy:0,preserved:0};
    if(!this.policy.recoveryEnabled)return result;
    const cutoff=Date.now()-this.policy.minAgeMs,directory=await fs.opendir(this.root);
    for await(const entry of directory){
      requireThat(++result.scanned<=this.policy.scanLimit,503,'WORK_STORAGE_SCAN_LIMIT');
      const work=path.join(this.root,entry.name);
      if(!uuidPattern.test(entry.name)||!entry.isDirectory()||(await fs.lstat(work)).mtimeMs>cutoff){result.preserved++;continue;}
      let lease;
      try{lease=openWorkLease(work);}catch(error){if(error.errcode===5||error.errcode===6)result.busy++;else result.preserved++;continue;}
      try{
        // Keep the committed tombstone and lock inode. A delayed orphan must
        // observe retired even if it had not acquired its lease when we scanned.
        await this.retire(work,lease);await this.removeOutputs(work);result.recovered++;
      }finally{lease.close();}
    }
    return result;
  }
  async create(){
    requireThat(!this.closing,503,'STORAGE_CLOSING');const work=path.join(this.root,randomUUID());this.active.add(work);
    try{await fs.mkdir(work,{mode:0o700});const lease=openWorkLease(work,{create:true});lease.close();return work;}
    catch(error){this.done(work);throw error;}
  }
  async removeOutputs(work){
    const directory=await fs.opendir(work);let entries=0;
    for await(const entry of directory){
      requireThat(++entries<=100,503,'WORK_FILE_LIMIT');
      if(!this.outputs.has(entry.name)&&!(entry.name.startsWith('facts.json.')&&entry.name.endsWith('.tmp')&&uuidPattern.test(entry.name.slice(11,-4))))continue;
      const file=path.join(work,entry.name),stat=await fs.lstat(file);
      if(entry.isFile()&&stat.isFile()&&!stat.isSymbolicLink()&&await fs.realpath(file)===file)await fs.unlink(file);
    }
  }
  done(work){this.active.delete(work);if(!this.active.size)for(const resolve of this.waiters.splice(0))resolve();}
  async release(work){
    requireThat(this.active.has(work),500,'UNKNOWN_WORK_DIRECTORY');let lease;
    try{
      try{lease=openWorkLease(work);}catch(error){if(error.code==='ENOENT')return;throw error;}
      await this.retire(work,lease);await this.removeOutputs(work);lease.close();lease=null;
      // This is a current-owner work item after all its worker processes closed.
      // Crash recovery above deliberately never removes the lock/tombstone.
      const names=await fs.readdir(work);
      if(names.length===2&&names.includes('lease.sqlite')&&names.includes('retired')){await fs.unlink(path.join(work,'retired'));await fs.unlink(path.join(work,'lease.sqlite'));await fs.rmdir(work);}
    }finally{lease?.close();this.done(work);}
  }
  async publish(work,target,cache=null,beforePublish=null){
    requireThat(this.active.has(work)&&path.dirname(target)===path.dirname(this.root),500,'UNKNOWN_WORK_DIRECTORY');
    const lease=openWorkLease(work);try{await this.retire(work,lease);}finally{lease.close();}
    // Keep the retired lease in the private cache. If publication fails, the
    // source work item must still have its lease so finally/recovery can clean it.
    const action=async()=>{if(beforePublish)await beforePublish();await fs.rename(work,target);};
    if(cache)await cache.publish(work,target,action);else await action();
  }
  async close(){this.closing=true;if(this.active.size)await new Promise(resolve=>this.waiters.push(resolve));}
}
