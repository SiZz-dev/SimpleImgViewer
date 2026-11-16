import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {randomUUID} from 'node:crypto';
import {UploadCapacity} from '../media/service/upload-capacity.mjs';
import {settings} from '../system/config/settings.mjs';

const config={storage:{maxOriginalBytes:1000,originalScanLimit:100},upload:{minFreeBytes:100}};
test('concurrent original reservations cannot spend the same remaining quota',async()=>{
  const capacity=new UploadCapacity(config,async()=>10000n);
  const results=await Promise.allSettled(['a','b'].map(key=>capacity.reserve(key,700,true)));
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);assert.equal(results.find(r=>r.status==='rejected').reason.code,'ORIGINAL_CAPACITY_REACHED');
  assert.equal(capacity.reserved,700);capacity.release('a');assert.equal(capacity.diskReserved,0);
  await capacity.reserve('b',700,true);capacity.commit('b',300);assert.equal(capacity.used,300);assert.equal(capacity.reserved,0);
  await capacity.reserve('c',700,true);assert.equal(capacity.reserved,700);capacity.release('b');capacity.release('c');assert.equal(capacity.diskReserved,0);
});

test('stamp and original inputs share one serialized disk reservation floor',async()=>{
  const capacity=new UploadCapacity(config,async()=>1000n);
  await capacity.reserve('original',600,true);
  await assert.rejects(capacity.reserve('stamp',400,false),{code:'INSUFFICIENT_STORAGE'});
  assert.equal(capacity.leases.size,1);capacity.release('original');
  await capacity.reserve('stamp',800,false);assert.equal(capacity.reserved,0);assert.equal(capacity.diskReserved,800);
  await assert.rejects(capacity.reserve('next',200,true),{code:'INSUFFICIENT_STORAGE'});capacity.release('stamp');
});

test('commit checks reservation ownership and actual size; failed reservations leave no charge',async()=>{
  const capacity=new UploadCapacity(config,async()=>10000n);
  await assert.rejects(capacity.reserve('too-large',1001,true),{code:'ORIGINAL_CAPACITY_REACHED'});assert.equal(capacity.leases.size,0);
  await capacity.reserve('a',700,true);assert.throws(()=>capacity.commit('a',701),{code:'INVALID_UPLOAD_RESERVATION'});
  capacity.commit('a',200);assert.throws(()=>capacity.commit('a',200),{code:'INVALID_UPLOAD_RESERVATION'});capacity.release('a');
  assert.equal(capacity.used,200);assert.equal(capacity.diskReserved,0);assert.equal(capacity.reserved,0);
});

async function fixture(t){const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-original-budget-'));await fs.mkdir(path.join(root,'originals'));t.after(()=>fs.rm(root,{recursive:true,force:true}));return root;}
async function source(root,bytes){const directory=path.join(root,'originals',randomUUID());await fs.mkdir(directory);await fs.writeFile(path.join(directory,'source'),Buffer.alloc(bytes));return directory;}

test('restart inventory counts orphan source bytes without deleting any originals',async t=>{
  const root=await fixture(t);await source(root,300);const orphan=await source(root,200);
  await fs.writeFile(path.join(orphan,'record.json.'+randomUUID()+'.tmp'),'partial metadata');
  const capacity=new UploadCapacity({...config,storage:{...config.storage,root}},async()=>10000n);
  assert.deepEqual(await capacity.init(),{entries:2,bytes:500});
  await assert.rejects(capacity.reserve('new',501,true),{code:'ORIGINAL_CAPACITY_REACHED'});await fs.stat(path.join(orphan,'source'));
});

test('original inventory fails closed on links, unknown data and scan limits',async t=>{
  const root=await fixture(t),directory=await source(root,200);
  await fs.writeFile(path.join(directory,'unknown'),'preserve');
  const capacity=new UploadCapacity({...config,storage:{...config.storage,root}},async()=>10000n);
  await assert.rejects(capacity.init(),{code:'ORIGINAL_STORAGE_UNAVAILABLE'});await fs.stat(path.join(directory,'unknown'));
  await fs.unlink(path.join(directory,'unknown'));
  const linked=path.join(root,'originals',randomUUID());await fs.symlink(directory,linked,process.platform==='win32'?'junction':'dir');
  await assert.rejects(capacity.init(),{code:'ORIGINAL_STORAGE_UNAVAILABLE'});await fs.unlink(linked);
  await source(root,100);const limited=new UploadCapacity({...config,storage:{...config.storage,root,originalScanLimit:1}},async()=>10000n);
  await assert.rejects(limited.init(),{code:'ORIGINAL_STORAGE_SCAN_LIMIT'});assert.equal(limited.used,0);
});

test('original quota settings reject zero and non-integral values',async()=>{
  for(const storage of [{maxOriginalBytes:0},{maxOriginalBytes:1.5},{originalScanLimit:0}])await assert.rejects(settings({overrides:{storage}}),{code:'INVALID_CONFIGURATION'});
});
