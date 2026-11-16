import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import {privateRoot} from '../util/files.mjs';
import {ApiError,requireThat,uuidPattern} from '../../system/util/errors.mjs';

// The database contains no media. Its OS-backed transaction lock is held for
// the lifetime of this owner; never delete/replace it to reclaim a stale PID.
export class TemporaryFiles {
  constructor(root,policy){this.root=path.join(root,'multipart','transfers');this.policy=policy;this.active=new Set();this.waiters=[];this.closing=false;}
  async init(){
    requireThat(!this.database&&!this.closing,500,'TEMPORARY_OWNER_ALREADY_USED');
    await privateRoot(this.root);
    const file=path.join(this.root,'owner.sqlite');
    try{const handle=await fs.open(file,'ax',0o600);await handle.close();}catch(e){if(e.code!=='EEXIST')throw e;}
    const stat=await fs.lstat(file);
    requireThat(stat.isFile()&&!stat.isSymbolicLink()&&await fs.realpath(file)===file,500,'UNSAFE_STORAGE');
    try{
      this.database=new DatabaseSync(file,{timeout:0,allowExtension:false});
      this.database.exec('BEGIN EXCLUSIVE');
      return await this.#recover();
    }catch(error){this.database?.close();this.database=null;
      if(error.errcode===5||error.errcode===6)throw new ApiError(503,'STORAGE_IN_USE');throw error;
    }
  }
  async #recover(){
    let scanned=0,removed=0;const cutoff=Date.now()-this.policy.minAgeMs;
    if(!this.policy.recoveryEnabled)return {scanned,removed};
    const directory=await fs.opendir(this.root);
    for await(const entry of directory){
      requireThat(++scanned<=this.policy.scanLimit,503,'TEMP_STORAGE_SCAN_LIMIT');
      if(!entry.isFile()||!entry.name.endsWith('.part')||!uuidPattern.test(entry.name.slice(0,-5)))continue;
      const file=path.join(this.root,entry.name),stat=await fs.lstat(file);
      if(!stat.isFile()||stat.isSymbolicLink()||stat.mtimeMs>cutoff||await fs.realpath(file)!==file)continue;
      await fs.unlink(file);removed++;
    }
    return {scanned,removed};
  }
  allocate(){
    requireThat(this.database&&!this.closing,503,'STORAGE_CLOSING');
    const file=path.join(this.root,randomUUID()+'.part');this.active.add(file);return file;
  }
  async release(file){
    requireThat(this.active.has(file),500,'UNKNOWN_TEMPORARY_FILE');
    try{await fs.unlink(file);}catch(e){if(e.code!=='ENOENT')throw e;}
    finally{this.active.delete(file);if(!this.active.size)for(const resolve of this.waiters.splice(0))resolve();}
  }
  async close(){
    this.closing=true;
    if(this.active.size)await new Promise(resolve=>this.waiters.push(resolve));
    this.database?.close();this.database=null;
  }
}
