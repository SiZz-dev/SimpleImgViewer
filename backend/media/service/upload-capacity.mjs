import fs from 'node:fs/promises';
import path from 'node:path';
import {requireThat,uuidPattern} from '../../system/util/errors.mjs';

export class UploadCapacity {
  constructor(config,available=async()=>{const s=await fs.statfs(config.storage.root,{bigint:true});return s.bavail*s.bsize;}){
    this.config=config;this.available=available;this.used=0;this.reserved=0;this.diskReserved=0;this.leases=new Map();this.queue=Promise.resolve();
  }
  async init(){
    let count=0,bytes=0;
    const root=path.join(this.config.storage.root,'originals');
    for await(const entry of await fs.opendir(root)){
      requireThat(++count<=this.config.storage.originalScanLimit,503,'ORIGINAL_STORAGE_SCAN_LIMIT');
      const directory=path.join(root,entry.name);
      requireThat(uuidPattern.test(entry.name)&&entry.isDirectory()&&await fs.realpath(directory)===directory,503,'ORIGINAL_STORAGE_UNAVAILABLE');
      let files=0;
      for await(const child of await fs.opendir(directory)){
        requireThat(++files<=8&&(child.name==='source'||child.name==='record.json'||child.name.startsWith('record.json.')&&child.name.endsWith('.tmp')&&uuidPattern.test(child.name.slice(12,-4))),503,'ORIGINAL_STORAGE_UNAVAILABLE');
        const file=path.join(directory,child.name),stat=await fs.lstat(file);
        requireThat(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1&&await fs.realpath(file)===file&&Number.isSafeInteger(stat.size),503,'ORIGINAL_STORAGE_UNAVAILABLE');
        if(child.name==='source'){bytes+=stat.size;requireThat(Number.isSafeInteger(bytes),503,'ORIGINAL_STORAGE_UNAVAILABLE');}
        else requireThat(stat.size<=1048576,503,'ORIGINAL_STORAGE_UNAVAILABLE');
      }
    }
    this.used=bytes;return {entries:count,bytes};
  }
  async reserve(key,bytes,original){
    const previous=this.queue;let release;this.queue=new Promise(resolve=>{release=resolve;});await previous;
    try{
      requireThat(!this.leases.has(key)&&Number.isSafeInteger(bytes)&&bytes>0&&bytes<=104857600,500,'INVALID_UPLOAD_RESERVATION');
      const available=await this.available();
      requireThat(!original||this.used+this.reserved+bytes<=this.config.storage.maxOriginalBytes,507,'ORIGINAL_CAPACITY_REACHED');
      requireThat(available>=BigInt(this.config.upload.minFreeBytes+this.diskReserved+bytes),507,'INSUFFICIENT_STORAGE');
      this.leases.set(key,{bytes,original,committed:false});this.diskReserved+=bytes;if(original)this.reserved+=bytes;
    }finally{release();}
  }
  check(key,bytes){const lease=this.leases.get(key);requireThat(lease?.original&&!lease.committed&&bytes>0&&bytes<=lease.bytes,500,'INVALID_UPLOAD_RESERVATION');}
  commit(key,bytes){this.check(key,bytes);const lease=this.leases.get(key);this.used+=bytes;this.reserved-=lease.bytes;lease.committed=true;}
  // A deleted original's committed bytes; only called after its source file is actually gone.
  remove(bytes){requireThat(Number.isSafeInteger(bytes)&&bytes>=0&&bytes<=this.used,500,'INVALID_CAPACITY_RELEASE');this.used-=bytes;}
  release(key){const lease=this.leases.get(key);requireThat(lease,500,'INVALID_UPLOAD_RESERVATION');this.diskReserved-=lease.bytes;if(lease.original&&!lease.committed)this.reserved-=lease.bytes;this.leases.delete(key);}
}
