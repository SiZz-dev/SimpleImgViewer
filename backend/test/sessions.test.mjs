import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {randomUUID} from 'node:crypto';
import {ViewerSessions} from '../viewer/service/sessions.mjs';
import {settings} from '../system/config/settings.mjs';

async function fixture(t,policy={}){
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-session-cleanup-'));
  t.after(()=>fs.rm(root,{recursive:true,force:true}));
  const directory=path.join(root,'sessions');await fs.mkdir(directory);
  const config=await settings({overrides:{sessions:policy}});
  const actor={userId:'user',tenantId:'tenant'},mediaId=randomUUID();
  const sessions=new ViewerSessions({root,config,async require(id){assert.equal(id,mediaId);return {mediaId};}},{public:record=>record});
  async function seed(expiresAt,extra={}){
    const id=randomUUID(),file=path.join(directory,id+'.json');
    await fs.writeFile(file,JSON.stringify({sessionId:id,createdAt:new Date(Date.now()-3600000).toISOString(),expiresAt,...extra}));return file;
  }
  return {root,directory,sessions,actor,mediaId,seed};
}

test('session cleanup removes only expired valid session JSON and preserves other data',async t=>{
  const f=await fixture(t);const expired=await f.seed(new Date(Date.now()-1000).toISOString());
  const live=await f.seed(new Date(Date.now()+60000).toISOString());
  const mismatch=await f.seed(new Date(Date.now()-1000).toISOString(),{sessionId:randomUUID()});
  const invalidDate=await f.seed('not-a-date');
  const corrupt=path.join(f.directory,randomUUID()+'.json');await fs.writeFile(corrupt,'{broken');
  const unrelated=path.join(f.directory,'notes.json');await fs.writeFile(unrelated,'keep');
  const folder=path.join(f.directory,randomUUID()+'.json');await fs.mkdir(folder);
  const original=path.join(f.root,'original');await fs.writeFile(original,'original');
  const result=await f.sessions.cleanupExpired();assert.equal(result.removed,1);assert.equal(result.remaining,6);
  await assert.rejects(fs.stat(expired),{code:'ENOENT'});
  for(const file of [live,mismatch,invalidDate,corrupt,unrelated,folder,original])await fs.stat(file);
});

test('expired sessions release capacity; concurrent creation cannot exceed the configured limit',async t=>{
  const f=await fixture(t,{maxEntries:1});await f.seed(new Date(Date.now()-1000).toISOString());
  const results=await Promise.allSettled(Array.from({length:5},()=>f.sessions.create({mediaIds:[f.mediaId]},f.actor)));
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  for(const r of results.filter(r=>r.status==='rejected'))assert.equal(r.reason.code,'SESSION_LIMIT');
  assert.equal((await fs.readdir(f.directory)).length,1);
  const session=results.find(r=>r.status==='fulfilled').value;
  assert.equal(Date.parse(session.expiresAt)-Date.parse(session.createdAt),1800000);
  await assert.rejects(f.sessions.view(session.sessionId,{...f.actor,userId:'another'}),{code:'SESSION_NOT_FOUND'});
});

test('cleanup can be disabled; bounded scanning and failed creation do not poison the queue',async t=>{
  const f=await fixture(t,{cleanupEnabled:false,maxEntries:1,scanLimit:1});
  const expired=await f.seed(new Date(Date.now()-1000).toISOString());
  assert.equal((await f.sessions.cleanupExpired()).removed,0);await fs.stat(expired);
  await assert.rejects(f.sessions.create({mediaIds:[f.mediaId]},f.actor),{code:'SESSION_LIMIT'});
  await fs.writeFile(path.join(f.directory,'unknown'),'keep');
  await assert.rejects(f.sessions.cleanupExpired(),{code:'SESSION_STORAGE_SCAN_LIMIT'});
  await fs.unlink(path.join(f.directory,'unknown'));await fs.unlink(expired);
  assert.ok((await f.sessions.create({mediaIds:[f.mediaId]},f.actor)).sessionId);
});

test('session lifecycle settings reject unsafe bounds and wrong types',async()=>{
  for(const sessions of [{ttlMs:0},{maxEntries:0},{scanLimit:0},{maxEntries:10,scanLimit:9},{cleanupEnabled:'true'}])
    await assert.rejects(settings({overrides:{sessions}}),{code:'INVALID_CONFIGURATION'});
});
