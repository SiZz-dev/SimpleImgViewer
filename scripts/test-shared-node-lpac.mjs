import fs from 'node:fs/promises';import path from 'node:path';import assert from 'node:assert/strict';
import {execFile,spawn} from 'node:child_process';import {promisify} from 'node:util';import {createHash,randomUUID} from 'node:crypto';
import sharp from 'sharp';import {createApplication} from '../backend/system/controller/application.mjs';
const hash=b=>createHash('sha256').update(b).digest('hex'),run=promisify(execFile),report=path.resolve('build/reports/shared-node-lpac');await fs.mkdir(report,{recursive:true});
const result={testOnly:true,productionEnabled:false,passed:false,cases:[],measurements:[]};let application;
try{
 const runtime=JSON.parse(await fs.readFile('build/node-sharp-lpac/runtime.json','utf8')),manifest=JSON.parse(await fs.readFile('build/shared-node-lpac/launcher.json','utf8'));
 assert.equal(runtime.testOnly,true);assert.equal(manifest.testOnly,true);assert.equal(hash(await fs.readFile('pnpm-lock.yaml')),runtime.lockfileSha256);
 for(const item of runtime.inventory){assert.equal(hash(await fs.readFile(path.join(runtime.root,item.path))),item.sha256);if(item.path.startsWith('backend/'))assert.equal(item.sha256,hash(await fs.readFile(item.path)));}
 for(const item of manifest.inputs)assert.equal(hash(await fs.readFile(item.path)),item.sha256);
 const program=manifest.programs['registry-read'];assert.equal(hash(await fs.readFile(program.executable)),program.sha256);
 const call=(args)=>run(program.executable,args,{windowsHide:true,timeout:30000,maxBuffer:16384});
 async function tree(root,prefix=''){let records=[];for(const item of await fs.readdir(path.join(root,prefix),{withFileTypes:true})){assert.ok(!item.isSymbolicLink());const name=prefix+item.name;if(item.isDirectory()){records.push({name,directory:true});records.push(...await tree(root,name+'/'));}else{assert.ok(item.isFile());records.push({name,directory:false});}}return records.sort((a,b)=>a.name<b.name?-1:1);}
 const entries=await tree(runtime.root);assert.deepEqual(entries.filter(x=>!x.directory).map(x=>x.name).sort(),runtime.inventory.map(x=>x.path).sort());
 const inventory=new Map(runtime.inventory.map(x=>[x.path,x]));
 const digestExpected=hash(Buffer.from(entries.map(x=>x.directory?'D\t'+x.name+'\n':'F\t'+x.name+'\t'+inventory.get(x.name).bytes+'\t'+inventory.get(x.name).sha256+'\n').join('')));
 const root=path.resolve('build/acceptance/snl-'+randomUUID().slice(0,8));await fs.mkdir(root,{recursive:true});result.root=root;
 const cache=path.join(root,'runtime'),start=performance.now(),digest=(await call(['--prepare',runtime.root,cache])).stdout.trim();assert.equal(digest,digestExpected);result.preparationMs=Math.round(performance.now()-start);result.runtimeBytes=runtime.inventory.reduce((n,x)=>n+x.bytes,0);result.runtimeFiles=runtime.inventory.length;result.runtimeDirectories=entries.filter(x=>x.directory).length;
 const input=path.join(root,'source.tiff'),pixels=Buffer.alloc(128*160*3);for(let y=0;y<160;y++)for(let x=0;x<128;x++){const i=(y*128+x)*3;pixels[i]=y<80?240:20;pixels[i+1]=x;pixels[i+2]=y;}
 await sharp(pixels,{raw:{width:128,height:160,channels:3,pageHeight:80}}).tiff().toFile(input);const inputHash=hash(await fs.readFile(input));
 const preview=page=>({operation:'preview',input:'@INPUT@',output:'@PREVIEW@',thumbnail:'@THUMBNAIL@',page,maxPixels:1000000,edge:96});
 const template=path.join(root,'preview.json');await fs.writeFile(template,JSON.stringify(preview(1)));
 const args=(target,job=template,mode='normal',reuse=true,stamp='-')=>[reuse?cache:runtime.root,input,target,job,stamp,mode,'acceptance',...(reuse?['shared',digest]:[])];
 const launch=(...parameters)=>call(args(...parameters));
 const facts=execution=>{assert.match(execution.stdout,/truncated=0/);return JSON.parse(execution.stdout.slice(execution.stdout.indexOf('diagnostics=')+12));};
 let baseline;
 for(let iteration=0;iteration<3;iteration++)for(const reuse of iteration%2?[true,false]:[false,true]){
  const target=path.join(root,(reuse?'shared-':'copy-')+iteration),start=performance.now();const meta=facts(await launch(target,template,'normal',reuse));const elapsedMs=Math.round(performance.now()-start);assert.equal(meta.pageCount,2);
  const actual=hash(await fs.readFile(path.join(target,'out/preview.png')));if(!baseline)baseline=actual;assert.equal(actual,baseline);
  const bin=await fs.readdir(path.join(target,'bin'));assert.equal(bin.includes('node.exe'),!reuse);assert.equal(bin.includes('node_modules'),!reuse);assert.equal(bin.includes('backend'),!reuse);
  result.measurements.push({reuse,iteration,elapsedMs,runtimeBytesCopied:reuse?0:result.runtimeBytes});
 }
 result.cases.push({name:'preview-copy-versus-reuse',iterations:3,pngHashesMatched:true});
 const second=path.join(root,'page2.json');await fs.writeFile(second,JSON.stringify(preview(2)));
 const parallel=[path.join(root,'parallel-1'),path.join(root,'parallel-2')];const requests=await Promise.allSettled([launch(parallel[0]),launch(parallel[1],second)]);for(const item of requests)assert.equal(item.status,'fulfilled',item.reason?.message);
 assert.notEqual(hash(await fs.readFile(path.join(parallel[0],'out/preview.png'))),hash(await fs.readFile(path.join(parallel[1],'out/preview.png'))));result.cases.push({name:'two-page-concurrent-previews',outputsDistinct:true});
 application=await createApplication({development:true,overrides:{server:{host:'127.0.0.1',port:0},upload:{minFreeBytes:0},playback:{enabled:false},storage:{root:path.join(root,'store')}}});
 const address=await application.listen(),base='http://127.0.0.1:'+address.port,bootstrap=await fetch(base+'/api/v1/viewer/bootstrap');assert.equal(bootstrap.status,200);const cookie=bootstrap.headers.get('set-cookie').split(';')[0],csrf=(await bootstrap.json()).csrf.token;
 const stampBytes=await sharp({create:{width:24,height:16,channels:4,background:'#00bb55'}}).png().toBuffer();const form=new FormData();form.append('file',new Blob([stampBytes]),'synthetic.png');
 const upload=await fetch(base+'/api/v1/stamps',{method:'POST',headers:{Cookie:cookie,'X-CSRF-TOKEN':csrf},body:form});assert.equal(upload.status,201);const uploaded=await upload.json(),stamp=path.join(application.store.root,'stamps',uploaded.stampId+'.png'),stampHash=hash(await fs.readFile(stamp));
 const edited={operation:'export',input:'@INPUT@',output:'@OUTPUT@',page:2,maxPixels:1000000,brightness:120,rotation:90,crop:{left:2,top:2,width:100,height:60},items:[{type:'stamp',file:'@STAMP@',x:10,y:12,width:24,height:16}]};const edit=path.join(root,'export.json');await fs.writeFile(edit,JSON.stringify(edited));
 const copy=path.join(root,'export-copy'),shared=path.join(root,'export-shared');const a=facts(await launch(copy,edit,'normal',false,stamp)),b=facts(await launch(shared,edit,'normal',true,stamp));assert.deepEqual(a,b);assert.equal(hash(await fs.readFile(path.join(copy,'out/export.png'))),hash(await fs.readFile(path.join(shared,'out/export.png'))));assert.equal(hash(await fs.readFile(stamp)),stampHash);result.cases.push({name:'actual-upload-stamp-export',status:201,hashesMatched:true});await application.close();application=null;
 const state=path.join(root,'.imagegate-lpac-state');
 async function ready(target){const end=Date.now()+15000;while(Date.now()<end){try{return JSON.parse(await fs.readFile(path.join(target,'out/ready.json'),'utf8'));}catch(error){if(error.code!=='ENOENT')throw error;}await new Promise(resolve=>setTimeout(resolve,50));}throw Error('Hold gate did not start');}
 const holders=[0,1].map(i=>{const target=path.join(root,'holder-'+i),child=spawn(program.executable,args(target,template,'hold'),{windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.resume();child.stderr.resume();return {target,child,closed:new Promise(resolve=>child.once('close',resolve))};});
 try{
  const pids=await Promise.all(holders.map(holder=>ready(holder.target)));assert.notEqual(pids[0].pid,pids[1].pid);
  for(const file of [path.join(cache,'LICENSE'),path.join(cache,'backend/media/util/files.mjs')])await assert.rejects(fs.open(file,'r+'),error=>['EPERM','EACCES','EBUSY'].includes(error.code));
  const directory=path.join(cache,'backend/media');await assert.rejects(fs.rename(directory,directory+'-moved'),error=>['EPERM','EACCES','EBUSY'].includes(error.code));
  const forbidden=path.join(root,'other-input.json');await fs.writeFile(forbidden,JSON.stringify({...preview(1),input:path.join(holders[0].target,'input/source.cjs')}));await assert.rejects(launch(path.join(root,'cross-input'),forbidden),error=>error.code===22&&/"ok":false/.test(error.stdout));
  const forbiddenJob=path.join(root,'other-output.json');await fs.writeFile(forbiddenJob,JSON.stringify({...preview(1),input:path.join(holders[0].target,'out/job.json')}));await assert.rejects(launch(path.join(root,'cross-output'),forbiddenJob),error=>error.code===22&&/"ok":false/.test(error.stdout));
  result.cases.push({name:'cross-job-input-output',rejected:true});
  holders[0].child.kill('SIGKILL');await holders[0].closed;await assert.rejects(call(['--recover',state]),error=>error.code===25&&/recovered=1 busy=1/.test(error.stdout));process.kill(pids[1].pid,0);
  await assert.rejects(fs.open(path.join(cache,'LICENSE'),'r+'),error=>['EPERM','EACCES','EBUSY'].includes(error.code));
  holders[1].child.kill('SIGKILL');await holders[1].closed;assert.match((await call(['--recover',state])).stdout,/recovered=1 busy=0/);const control=await fs.open(path.join(cache,'LICENSE'),'r+');await control.close();
  result.cases.push({name:'file-directory-pins-and-independent-cancellation',secondStayedAlive:true,allProfilesRecovered:true});
 }finally{await Promise.all(holders.map(async holder=>{if(holder.child.exitCode===null&&holder.child.signalCode===null){holder.child.kill('SIGKILL');await holder.closed;}}));}
 const worker=path.join(cache,'backend/conversion/util/image-worker.mjs'),original=await fs.readFile(worker);await fs.appendFile(worker,'\n// synthetic tamper');try{await assert.rejects(launch(path.join(root,'tamper')),error=>error.code===23&&/stage=shared-node-digest/.test(error.stdout));}finally{await fs.writeFile(worker,original);}result.cases.push({name:'changed-worker',rejected:true});
 const empty=path.join(cache,'backend/unrecorded');await fs.mkdir(empty);try{await assert.rejects(launch(path.join(root,'empty-directory')),error=>error.code===23&&/stage=shared-node-digest/.test(error.stdout));}finally{await fs.rmdir(empty);}result.cases.push({name:'unrecorded-empty-directory',rejected:true});
 const extra=path.join(cache,'node_modules/extra.js');await fs.writeFile(extra,'synthetic');try{await assert.rejects(launch(path.join(root,'extra')),error=>error.code===23&&/stage=shared-node-digest/.test(error.stdout));}finally{await fs.unlink(extra);}result.cases.push({name:'unrecorded-js',rejected:true});
 const linked=path.join(root,'license-link');await fs.link(path.join(cache,'LICENSE'),linked);try{await assert.rejects(launch(path.join(root,'linked')),error=>error.code===23&&/stage=regular-file/.test(error.stdout));}finally{await fs.unlink(linked);}result.cases.push({name:'hardlink',rejected:true});
 facts(await launch(path.join(root,'after-recovery')));assert.equal(hash(await fs.readFile(input)),inputHash);assert.equal((await fs.readdir(state)).filter(name=>name.endsWith('.lease')).length,0);
 for(const item of runtime.inventory)assert.equal(hash(await fs.readFile(path.join(cache,item.path))),item.sha256);assert.deepEqual(await tree(cache),entries);
 const median=values=>values.sort((a,b)=>a-b)[Math.floor(values.length/2)];result.mediansMs={copy:median(result.measurements.filter(x=>!x.reuse).map(x=>x.elapsedMs)),reuse:median(result.measurements.filter(x=>x.reuse).map(x=>x.elapsedMs))};result.passed=true;
}catch(error){result.error={message:error.message,stdout:error.stdout,stderr:error.stderr};process.exitCode=1;}
finally{if(application)await application.close();await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify(result,null,2));
