import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {randomUUID} from 'node:crypto';
import {CacheStorage} from '../conversion/service/cache-storage.mjs';
import {settings} from '../system/config/settings.mjs';

const policy={cleanupOnStartup:true,maxBytes:1000000,maxAgeMs:60000,scanLimit:100};
async function fixture(t){const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-cache-test-'));await fs.mkdir(path.join(root,'derived'));t.after(()=>fs.rm(root,{recursive:true,force:true}));return root;}
const name=()=>randomUUID()+'-'+'a'.repeat(64);
async function image(directory,age=0){
  await fs.mkdir(directory);await fs.writeFile(path.join(directory,'facts.json'),JSON.stringify({width:16,height:16}));
  await fs.writeFile(path.join(directory,'preview.png'),Buffer.alloc(256,3));await fs.writeFile(path.join(directory,'thumbnail.png'),Buffer.alloc(128,5));
  const time=new Date(Date.now()-age);await fs.utimes(directory,time,time);
}

test('startup TTL evicts only recognized caches and preserves originals, malformed files and links',async t=>{
  const root=await fixture(t),old=path.join(root,'derived',name());await image(old,120000);
  const original=path.join(root,'original');await fs.writeFile(original,'original');
  const invalid=path.join(root,'derived',name());await image(invalid,120000);await fs.writeFile(path.join(invalid,'unknown'),'keep');
  const linked=path.join(root,'derived',name());await fs.symlink(root,linked,process.platform==='win32'?'junction':'dir');
  const hardlink=path.join(root,'derived',name());await image(hardlink,120000);await fs.unlink(path.join(hardlink,'preview.png'));await fs.link(original,path.join(hardlink,'preview.png'));
  const manager=new CacheStorage(root,policy),result=await manager.init();assert.equal(result.removed,1);
  await assert.rejects(fs.stat(old),{code:'ENOENT'});assert.equal(await fs.readFile(original,'utf8'),'original');
  await fs.stat(path.join(invalid,'unknown'));await fs.lstat(linked);await fs.stat(hardlink);
});

test('startup budget removes the least recently used recognized cache first',async t=>{
  const root=await fixture(t),directories=[];
  for(const age of [30000,20000,10000]){const directory=path.join(root,'derived',name());await image(directory,age);directories.push(directory);}
  const sample=await new CacheStorage(root,policy).inspect(directories[0],'image');
  const manager=new CacheStorage(root,{...policy,maxBytes:sample.bytes*2});const result=await manager.init();
  assert.equal(result.removed,1);assert.equal(result.bytes,sample.bytes*2);await assert.rejects(fs.stat(directories[0]),{code:'ENOENT'});
  await fs.stat(directories[1]);await fs.stat(directories[2]);
});

test('runtime publication is serialized and cannot exceed the cache budget',async t=>{
  const root=await fixture(t),sources=[];
  for(let i=0;i<3;i++){const directory=path.join(root,'work-'+i);await image(directory);sources.push(directory);}
  const sample=await new CacheStorage(root,policy).inspect(sources[0],'image');
  const manager=new CacheStorage(root,{...policy,maxBytes:sample.bytes});await manager.init();
  const results=await Promise.allSettled(sources.map(source=>{const target=path.join(root,'derived',name());return manager.publish(source,target,()=>fs.rename(source,target));}));
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  for(const failure of results.filter(r=>r.status==='rejected'))assert.equal(failure.reason.code,'CACHE_CAPACITY_REACHED');
  assert.equal(manager.bytes,sample.bytes);assert.equal(manager.entries.size,1);
});

test('budget refusal never evicts a cache being read and startup cleanup can be disabled',async t=>{
  const root=await fixture(t),existing=path.join(root,'derived',name());await image(existing,120000);
  const manager=new CacheStorage(root,{...policy,cleanupOnStartup:false,maxBytes:1});assert.equal((await manager.init()).removed,0);
  const input=await fs.open(path.join(existing,'preview.png'),'r');
  try{
    const source=path.join(root,'work');await image(source);
    await assert.rejects(manager.publish(source,path.join(root,'derived',name()),()=>assert.fail('must not publish')),{code:'CACHE_CAPACITY_REACHED'});
    assert.deepEqual(await input.readFile(),Buffer.alloc(256,3));await fs.stat(existing);
  }finally{await input.close();}
});

test('cache touch renews retention and bounded inventory never partly evicts before failing',async t=>{
  const root=await fixture(t),existing=path.join(root,'derived',name());await image(existing,120000);
  const manager=new CacheStorage(root,{...policy,cleanupOnStartup:false});await manager.init();await manager.touch(existing);
  assert.equal((await new CacheStorage(root,policy).init()).removed,0);
  const time=new Date(Date.now()-120000);await fs.utimes(existing,time,time);
  for(let i=0;i<100;i++)await fs.mkdir(path.join(root,'derived','unknown-'+i));
  await assert.rejects(new CacheStorage(root,policy).init(),{code:'CACHE_STORAGE_SCAN_LIMIT'});await fs.stat(existing);
});

test('cache settings reject unsafe bounds and wrong types',async()=>{
  for(const cache of [{maxBytes:0},{maxAgeMs:0},{scanLimit:0},{cleanupOnStartup:'true'}])await assert.rejects(settings({overrides:{cache}}),{code:'INVALID_CONFIGURATION'});
});
