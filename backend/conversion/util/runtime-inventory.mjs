import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {requireThat} from '../../system/util/errors.mjs';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
export async function safeRuntimePath(file,{missing=false}={}){
  requireThat(path.isAbsolute(file)&&path.normalize(file)===file,500,'UNSAFE_RUNTIME_PATH');
  let part=path.parse(file).root;
  for(const segment of file.slice(part.length).split(path.sep)){
    part=path.join(part,segment);
    try{const stat=await fs.lstat(part);requireThat(!stat.isSymbolicLink()&&await fs.realpath(part)===part,500,'UNSAFE_RUNTIME_PATH');}
    catch(error){if(missing&&error.code==='ENOENT')return;throw error;}
  }
}
export function runtimeDescriptor(value){
  requireThat(value&&['node','ffmpeg'].includes(value.kind)&&Array.isArray(value.files)&&Array.isArray(value.directories),400,'INVALID_RUNTIME_DESCRIPTOR');
  requireThat(value.files.length>0&&value.files.length+value.directories.length<=512,400,'INVALID_RUNTIME_DESCRIPTOR');
  const names=new Set(),valid=name=>typeof name==='string'&&name.length<200&&name.split('/').every(p=>/^[a-z0-9@_+.-]+$/i.test(p)&&!['.','..'].includes(p)&&!p.endsWith('.')&&!/^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i.test(p));
  const add=name=>{requireThat(valid(name)&&!names.has(name.toLowerCase()),400,'INVALID_RUNTIME_DESCRIPTOR');names.add(name.toLowerCase());};
  const directories=[...value.directories].sort();directories.forEach(add);
  const files=value.files.map(file=>{add(file.path);requireThat(Number.isSafeInteger(file.bytes)&&file.bytes>0&&file.bytes<=268435456&&/^[a-f0-9]{64}$/.test(file.sha256),400,'INVALID_RUNTIME_DESCRIPTOR');return {path:file.path,bytes:file.bytes,sha256:file.sha256};}).sort((a,b)=>a.path<b.path?-1:1);
  const dirs=new Set(directories);for(const name of [...directories,...files.map(x=>x.path)]){let parent=path.posix.dirname(name);while(parent!=='.'){requireThat(dirs.has(parent),400,'INVALID_RUNTIME_DESCRIPTOR');parent=path.posix.dirname(parent);}}
  const bytes=files.reduce((n,x)=>n+x.bytes,0);requireThat(bytes<=268435456&&(value.kind!=='ffmpeg'||directories.length===0),400,'INVALID_RUNTIME_DESCRIPTOR');
  const lines=value.kind==='ffmpeg'?files.map(x=>x.path+'\t'+x.bytes+'\t'+x.sha256+'\n'): [...directories.map(name=>({name,line:'D\t'+name+'\n'})),...files.map(x=>({name:x.path,line:'F\t'+x.path+'\t'+x.bytes+'\t'+x.sha256+'\n'}))].sort((a,b)=>a.name<b.name?-1:1).map(x=>x.line);
  const nativeDigest=hash(Buffer.from(lines.join('')));requireThat(value.nativeDigest===nativeDigest,400,'INVALID_RUNTIME_DIGEST');
  return {schemaVersion:1,kind:value.kind,files,directories,bytes,nativeDigest};
}
export async function verifyRuntimeTree(root,descriptor,{partial=false,absent=false,readChunkBytes=1048576,fileConcurrency=2,observe}={}){
  // Internal bounded comparison options; never cache a proof or skip input bytes.
  requireThat(Number.isSafeInteger(readChunkBytes)&&readChunkBytes>=65536&&readChunkBytes<=1048576,400,'INVALID_RUNTIME_READ_CHUNK');
  requireThat(Number.isSafeInteger(fileConcurrency)&&fileConcurrency>=1&&fileConcurrency<=4,400,'INVALID_RUNTIME_CONCURRENCY');
  requireThat(observe===undefined||typeof observe==='function',400,'INVALID_RUNTIME_OBSERVER');
  const timings={rootMs:0,inventoryMs:0,fileMetadataMs:0,readMs:0,hashMs:0},counts={files:0,bytes:0,reads:0},clock=()=>observe?performance.now():0;
  const add=(key,start)=>{if(observe)timings[key]+=performance.now()-start;};
  let passed=false;const started=clock();
  try{
    let start=clock();
    try{await safeRuntimePath(root,{missing:absent});try{requireThat((await fs.lstat(root)).isDirectory(),500,'RUNTIME_TAMPERED');}catch(error){if(absent&&error.code==='ENOENT'){passed=true;return;}throw error;}}finally{add('rootMs',start);}
    const expected=new Map([...descriptor.directories.map(name=>[name,{directory:true}]),...descriptor.files.map(file=>[file.path,file])]),found=new Set(),files=[];
    async function scan(directory,prefix=''){
      for(const entry of await fs.readdir(directory,{withFileTypes:true})){
        const name=prefix+entry.name,record=expected.get(name);requireThat(record&&!found.has(name),500,'RUNTIME_TAMPERED');found.add(name);
        const file=path.join(directory,entry.name),stat=await fs.lstat(file);requireThat(!stat.isSymbolicLink()&&await fs.realpath(file)===file,500,'RUNTIME_TAMPERED');
        if(record.directory){requireThat(stat.isDirectory(),500,'RUNTIME_TAMPERED');await scan(file,name+'/');}
        else{requireThat(stat.isFile()&&stat.nlink===1&&stat.size===record.bytes,500,'RUNTIME_TAMPERED');files.push({file,record});}
      }
    }
    start=clock();try{await scan(root);requireThat(partial||found.size===expected.size,500,'RUNTIME_TAMPERED');}finally{add('inventoryMs',start);}
    let next=0,failed=false;
    async function worker(){
      const buffer=Buffer.alloc(readChunkBytes);
      while(!failed&&next<files.length){
        const {file,record}=files[next++];let handle;
        try{
          // Recheck after the inventory phase, immediately before opening this file.
          let start=clock();try{const stat=await fs.lstat(file);requireThat(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1&&stat.size===record.bytes&&await fs.realpath(file)===file,500,'RUNTIME_TAMPERED');handle=await fs.open(file,'r');const check=await handle.stat();requireThat(check.isFile()&&check.nlink===1&&check.size===record.bytes,500,'RUNTIME_TAMPERED');}finally{add('fileMetadataMs',start);}
          const algorithm=createHash('sha256');let total=0;
          for(;;){let bytesRead;start=clock();try{({bytesRead}=await handle.read(buffer,0,buffer.length,null));}finally{add('readMs',start);}counts.reads++;if(!bytesRead)break;total+=bytesRead;counts.bytes+=bytesRead;requireThat(total<=record.bytes,500,'RUNTIME_TAMPERED');start=clock();algorithm.update(buffer.subarray(0,bytesRead));add('hashMs',start);}
          start=clock();const digest=algorithm.digest('hex');add('hashMs',start);requireThat(total===record.bytes&&digest===record.sha256,500,'RUNTIME_TAMPERED');counts.files++;
        }catch(error){failed=true;throw error;}
        finally{if(handle)await handle.close();}
      }
    }
    // Drain all workers, including failures, before the runtime reference may release.
    const workers=await Promise.allSettled(Array.from({length:Math.min(fileConcurrency,files.length)},()=>worker()));
    const rejected=workers.find(result=>result.status==='rejected');if(rejected)throw rejected.reason;passed=true;
  }finally{if(observe){try{observe(Object.freeze({passed,wallMs:performance.now()-started,...timings,...counts,fileConcurrency,readChunkBytes}));}catch{}}}
}
