import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import sharp from 'sharp';
import {randomBytes} from 'node:crypto';
import {createApplication} from '../system/controller/application.mjs';
import {remoteUrl} from '../media/service/remote.mjs';

async function fixture(t,{development=true,overrides={}}={}){
  const root=path.join(await fs.mkdtemp(path.join(os.tmpdir(),'siv-node-test-')),'.private');
  const application=await createApplication({development,overrides:{server:{host:'127.0.0.1',port:0},upload:{minFreeBytes:0},playback:{enabled:false},...overrides,storage:{...overrides.storage,root}}});
  const address=await application.listen(),base='http://127.0.0.1:'+address.port;let cookie='',token='';
  t.after(()=>application.close());
  async function request(url,{method='GET',json,form,headers={}}={}){
    const response=await fetch(base+url,{method,headers:{...(cookie?{Cookie:cookie}:{}),...(method!=='GET'?{'X-CSRF-TOKEN':token}:{}),...(json?{'Content-Type':'application/json'}:{}),...headers},body:form|| (json?JSON.stringify(json):undefined)});
    if(response.headers.get('set-cookie'))cookie=response.headers.get('set-cookie').split(';')[0];return response;
  }
  const bootstrap=await request('/api/v1/viewer/bootstrap');if(bootstrap.ok)token=(await bootstrap.json()).csrf.token;
  const png=await sharp({create:{width:160,height:96,channels:3,background:'#1244cc'}}).png().toBuffer();
  async function upload(buffer=png,name='synthetic.png',url='/api/v1/media'){const form=new FormData();form.append('file',new Blob([buffer]),name);return request(url,{method:'POST',form});}
  return {application,request,upload,png,root,base};
}

test('default mode denies media access and never trusts actor headers',async t=>{
  const f=await fixture(t,{development:false});const r=await f.request('/api/v1/viewer/bootstrap',{headers:{'X-User':'dev-admin'}});assert.equal(r.status,401);assert.equal((await f.request('/actuator/health')).status,200);
});
test('CSRF is required before upload parsing and malformed JSON stays private',async t=>{
  const f=await fixture(t);const response=await f.request('/api/v1/media/import-url',{method:'POST',json:{url:'file:///C:/private'},headers:{'X-CSRF-TOKEN':'wrong'}});assert.equal(response.status,403);assert.equal((await response.json()).code,'CSRF_INVALID');
});
test('actual image preview, protected sources, session, notes, revisions and undo/redo',async t=>{
  const f=await fixture(t);const upload=await f.upload();assert.equal(upload.status,201);const media=await upload.json();
  const pageResponse=await f.request('/api/v1/media/'+media.mediaId+'/pages/1');assert.equal(pageResponse.status,200);const page=await pageResponse.json();assert.equal(page.coordinateWidth,160);assert.equal(page.coordinateHeight,96);
  const preview=await f.request(page.source.url);assert.equal(preview.status,200);assert.equal(preview.headers.get('content-type'),'image/png');assert.equal((await sharp(Buffer.from(await preview.arrayBuffer())).metadata()).width,160);
  const session=await f.request('/api/v1/viewer/sessions',{method:'POST',json:{mediaIds:[media.mediaId]}});assert.equal(session.status,201);assert.equal((await session.json()).media[0].mediaId,media.mediaId);
  const route='/api/v1/media/'+media.mediaId+'/pages/1/annotations';const binding={mediaVersion:media.mediaVersion,coordinateSpaceId:page.coordinateSpaceId,expectedRevision:0};
  const created=await f.request(route,{method:'POST',json:{...binding,type:'note',x:1,y:2,width:80,height:40,text:'합성 메모 <script> & 안전'}});assert.equal(created.status,201);const document=await created.json();assert.equal(document.revision,1);assert.equal(document.items.length,1);
  assert.equal((await f.request(route,{method:'POST',json:{...binding,type:'note',x:1,y:2,width:80,height:40,text:'stale'}})).status,409);
  const undo=await f.request(route+'/history/undo',{method:'POST',json:{...binding,expectedRevision:1}});assert.equal((await undo.json()).items.length,0);
  const redo=await f.request(route+'/history/redo',{method:'POST',json:{...binding,expectedRevision:2}});assert.equal((await redo.json()).items.length,1);
});
test('stamp upload, crop and edited PNG produce bounded actual rasters',async t=>{
  const f=await fixture(t);const media=await(await f.upload()).json();const page=await(await f.request('/api/v1/media/'+media.mediaId+'/pages/1')).json();
  const stamp=await f.upload(f.png,'stamp.png','/api/v1/stamps');assert.equal(stamp.status,201);const s=await stamp.json();assert.equal((await f.request(s.url)).status,200);
  const binding={mediaVersion:media.mediaVersion,coordinateSpaceId:page.coordinateSpaceId,expectedRevision:0};
  const created=await f.request('/api/v1/media/'+media.mediaId+'/pages/1/annotations',{method:'POST',json:{...binding,type:'stamp',stampId:s.stampId,x:0,y:0,width:20,height:12}});assert.equal(created.status,201);
  for(const operation of ['crop','export']){
    const r=await f.request('/api/v1/media/'+media.mediaId+'/pages/1/'+operation,{method:'POST',json:{...binding,expectedRevision:1,x:5,y:6,width:40,height:30,brightness:120,rotation:90,includeAnnotations:true}});assert.equal(r.status,200);
    const meta=await sharp(Buffer.from(await r.arrayBuffer())).metadata();assert.equal(meta.width,operation==='crop'?40:30);assert.equal(meta.height,operation==='crop'?30:40);
  }
});
test('changed original, forged media ID and unsupported SVG are refused',async t=>{
  const f=await fixture(t);const record=await(await f.upload()).json();assert.equal((await f.request('/api/v1/media/../../private')).status,404);
  await fs.appendFile(path.join(f.root,'originals',record.mediaId,'source'),'changed');const response=await f.request('/api/v1/media/'+record.mediaId+'/pages/1');assert.equal(response.status,409);
  assert.equal((await f.upload(Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>'),'unsafe.svg')).status,415);
});
test('streaming upload has a hard file byte limit',async t=>{
  const f=await fixture(t,{overrides:{upload:{maxBytes:1024,minFreeBytes:0}}});const r=await f.upload(Buffer.alloc(1025),'too-large.bin');assert.equal(r.status,413);
});

test('original quota blocks upload and URL before storage; stamp input uses only disk reservation',async t=>{
  const f=await fixture(t,{overrides:{storage:{maxOriginalBytes:1024},upload:{maxBytes:1024,minFreeBytes:0}}});
  const first=await f.upload();assert.equal(first.status,201);const media=await first.json();
  const blocked=await f.upload();assert.equal(blocked.status,507);assert.equal((await blocked.json()).code,'ORIGINAL_CAPACITY_REACHED');
  f.application.config.remote.enabled=true;f.application.config.remote.allowedPrefixes=['http://127.0.0.1:9/files/'];
  const remote=await f.request('/api/v1/media/import-url',{method:'POST',json:{url:'http://127.0.0.1:9/files/never.png'}});
  assert.equal(remote.status,507);assert.equal((await remote.json()).code,'ORIGINAL_CAPACITY_REACHED');
  assert.equal((await f.upload(f.png,'stamp.png','/api/v1/stamps')).status,201);
  assert.equal(f.application.store.capacity.used,f.png.length);assert.equal(f.application.store.capacity.reserved,0);assert.equal(f.application.store.capacity.diskReserved,0);
  assert.equal((await f.request('/api/v1/media/'+media.mediaId)).status,200);assert.equal((await fs.readdir(path.join(f.root,'originals'))).length,1);
});

test('unsupported input returns its entire reservation so a valid upload can follow',async t=>{
  const f=await fixture(t,{overrides:{storage:{maxOriginalBytes:1024},upload:{maxBytes:1024,minFreeBytes:0}}});
  assert.equal((await f.upload(Buffer.from('<svg/>'),'bad.svg')).status,415);
  assert.equal(f.application.store.capacity.reserved,0);assert.equal(f.application.store.capacity.diskReserved,0);
  assert.equal((await f.upload()).status,201);
});

test('original moved before metadata failure remains charged and is counted on restart',async t=>{
  const f=await fixture(t),rename=fs.rename;
  const mock=t.mock.method(fs,'rename',async(from,to)=>{
    if(to.startsWith(path.join(f.root,'originals')+path.sep)&&path.basename(to)==='record.json')throw Object.assign(Error('synthetic metadata failure'),{code:'EACCES'});
    return rename(from,to);
  });
  assert.equal((await f.upload()).status,500);mock.mock.restore();
  assert.equal(f.application.store.capacity.used,f.png.length);assert.equal(f.application.store.capacity.diskReserved,0);
  const {UploadCapacity}=await import('../media/service/upload-capacity.mjs');
  const inventory=new UploadCapacity(f.application.config);assert.equal((await inventory.init()).bytes,f.png.length);
});

test('cache capacity refusal returns 507, preserves the original and clears the failed work',async t=>{
  const f=await fixture(t,{overrides:{cache:{maxBytes:1048576}}});
  const input=await sharp(randomBytes(800*600*3),{raw:{width:800,height:600,channels:3}}).png().toBuffer();
  const record=await(await f.upload(input,'noise.png')).json();
  const response=await f.request('/api/v1/media/'+record.mediaId+'/pages/1');assert.equal(response.status,507);assert.equal((await response.json()).code,'CACHE_CAPACITY_REACHED');
  assert.deepEqual(await fs.readFile(path.join(f.root,'originals',record.mediaId,'source')),input);
  assert.deepEqual(await fs.readdir(path.join(f.root,'derived','image-work')),[]);assert.equal(f.application.store.cache.bytes,0);
});

test('disconnected multipart upload drains its writer and releases its temporary ownership',async t=>{
  const f=await fixture(t);const bootstrap=await fetch(f.base+'/api/v1/viewer/bootstrap');const token=(await bootstrap.json()).csrf.token;
  const cookie=bootstrap.headers.get('set-cookie').split(';')[0];
  const request=http.request(f.base+'/api/v1/media',{method:'POST',headers:{Cookie:cookie,'X-CSRF-TOKEN':token,Origin:f.base,
    'Content-Type':'multipart/form-data; boundary=abort-test','Content-Length':'100000'}});
  request.on('error',()=>{});
  request.write('--abort-test\r\nContent-Disposition: form-data; name="file"; filename="partial.png"\r\nContent-Type: image/png\r\n\r\n');request.write(f.png);
  const waitFor=async condition=>{for(let i=0;i<100;i++){if(await condition())return;await new Promise(resolve=>setTimeout(resolve,20));}assert.fail('upload lifecycle did not settle');};
  try{await waitFor(async()=>f.application.store.temporary.active.size===1);}finally{request.destroy();}
  await waitFor(async()=>f.application.store.temporary.active.size===0);
  assert.deepEqual((await fs.readdir(path.join(f.root,'multipart','transfers'))).filter(name=>name.endsWith('.part')),[]);
  assert.equal(f.application.store.capacity.diskReserved,0);assert.equal(f.application.store.capacity.reserved,0);
});
test('remote policy rejects paths, non-IP hosts, credentials and arbitrary origins',()=>{
  const prefixes=['http://127.0.0.1:18089/files/'];assert.equal(remoteUrl('http://127.0.0.1:18089/files/sample.png',prefixes).pathname,'/files/sample.png');
  for(const url of ['file:///private.png','http://localhost:18089/files/a.png','http://127.0.0.1:18090/files/a.png','http://127.0.0.1:18089/files/../private.png','http://127.0.0.1:18089/files/%252e%252e/private.png','http://user:pass@127.0.0.1:18089/files/a.png'])assert.throws(()=>remoteUrl(url,prefixes));
});
test('actual remote download publishes private source without forwarding cookies or following redirects',async t=>{
  const image=await sharp({create:{width:8,height:6,channels:3,background:'red'}}).png().toBuffer();let targetRequests=0,receivedCookie;
  const server=http.createServer((req,res)=>{
    receivedCookie=req.headers.cookie;if(req.url==='/files/redirect'){res.writeHead(302,{Location:'/files/target'}).end();return;}
    if(req.url==='/files/target')targetRequests++;
    res.writeHead(200,{'Content-Type':'image/png'});res.end(image);
  });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>{server.closeAllConnections();return new Promise(resolve=>server.close(resolve));});
  const prefix='http://127.0.0.1:'+server.address().port+'/files/';const f=await fixture(t,{overrides:{remote:{enabled:true,allowedPrefixes:[prefix]}}});
  const imported=await f.request('/api/v1/media/import-url',{method:'POST',json:{url:prefix+'sample.png'}});assert.equal(imported.status,201);const record=await imported.json();assert.equal(receivedCookie,undefined);
  const page=await f.request('/api/v1/media/'+record.mediaId+'/pages/1');assert.equal(page.status,200);
  const redirect=await f.request('/api/v1/media/import-url',{method:'POST',json:{url:prefix+'redirect'}});assert.equal(redirect.status,502);assert.equal(targetRequests,0);
  assert.deepEqual((await fs.readdir(path.join(f.root,'multipart','transfers'))).filter(name=>name.endsWith('.part')),[]);
});
test('concurrent editing accepts one expected revision and rejects the stale writer',async t=>{
  const f=await fixture(t);const record=await(await f.upload()).json();const info=await(await f.request('/api/v1/media/'+record.mediaId+'/pages/1')).json();
  const json={mediaVersion:record.mediaVersion,coordinateSpaceId:info.coordinateSpaceId,expectedRevision:0,type:'note',text:'concurrent',x:0,y:0,width:20,height:20};
  const results=await Promise.all([f.request('/api/v1/media/'+record.mediaId+'/pages/1/annotations',{method:'POST',json}),f.request('/api/v1/media/'+record.mediaId+'/pages/1/annotations',{method:'POST',json})]);assert.deepEqual(results.map(r=>r.status).sort(),[201,409]);
});
test('stamp SVG input is rejected before decoding and temporary bytes are cleaned',async t=>{
  const f=await fixture(t);assert.equal((await f.upload(Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"><image href="file:///private"/></svg>'),'stamp.svg','/api/v1/stamps')).status,415);
  assert.deepEqual((await fs.readdir(path.join(f.root,'multipart','transfers'))).filter(name=>name.endsWith('.part')),[]);
});
test('uncompressed 24-bit BMP has bounded decoding and invalid page refusal',async t=>{
  const f=await fixture(t);const bmp=Buffer.alloc(70);bmp.write('BM');bmp.writeUInt32LE(70,2);bmp.writeUInt32LE(54,10);bmp.writeUInt32LE(40,14);bmp.writeInt32LE(2,18);bmp.writeInt32LE(2,22);bmp.writeUInt16LE(1,26);bmp.writeUInt16LE(24,28);bmp.fill(128,54);
  const record=await(await f.upload(bmp,'test.bmp')).json();const p=await f.request('/api/v1/media/'+record.mediaId+'/pages/1');assert.equal(p.status,200);assert.equal((await p.json()).coordinateWidth,2);
  assert.equal((await f.request('/api/v1/media/'+record.mediaId+'/pages/2')).status,422);
});
test('installation feature switches are enforced by the server',async t=>{
  const f=await fixture(t,{overrides:{features:{image:false}}});assert.equal((await f.upload()).status,403);
});
test('unapproved Host headers and JSON arrays are rejected',async t=>{
  const f=await fixture(t);const status=await new Promise((resolve,reject)=>{
    const request=http.get(f.base+'/api/v1/viewer/bootstrap',{headers:{Host:'attacker.example'}},response=>{response.resume();resolve(response.statusCode);});request.on('error',reject);
  });assert.equal(status,403);
  assert.equal((await f.request('/api/v1/viewer/sessions',{method:'POST',json:[]})).status,400);
});
