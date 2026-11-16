import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import {execFile,spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash,randomUUID} from 'node:crypto';
import sharp from 'sharp';
import {createApplication} from '../backend/system/controller/application.mjs';
const run=promisify(execFile),hash=b=>createHash('sha256').update(b).digest('hex');
const report=path.resolve('build/reports/node-image-job-lpac');await fs.mkdir(report,{recursive:true});
const result={testOnly:true,productionEnabled:false,passed:false,cases:[]};
let application;
try{
  const runtime=JSON.parse(await fs.readFile('build/node-sharp-lpac/runtime.json','utf8'));
  const manifest=JSON.parse(await fs.readFile('build/node-image-job-lpac/launcher.json','utf8'));
  assert.equal(runtime.testOnly,true);assert.equal(manifest.testOnly,true);
  assert.equal(hash(await fs.readFile('pnpm-lock.yaml')),runtime.lockfileSha256);
  async function entries(dir,prefix=''){const files=[];for(const entry of await fs.readdir(dir,{withFileTypes:true})){assert.ok(!entry.isSymbolicLink());const relative=prefix+entry.name;if(entry.isDirectory())files.push(...await entries(path.join(dir,entry.name),relative+'/'));else{assert.ok(entry.isFile());files.push(relative);}}return files;}
  assert.deepEqual((await entries(runtime.root)).sort(),runtime.inventory.map(item=>item.path).sort());
  for(const item of runtime.inventory){const stat=await fs.lstat(path.join(runtime.root,item.path));assert.ok(stat.isFile()&&stat.nlink===1);assert.equal(hash(await fs.readFile(path.join(runtime.root,item.path))),item.sha256);if(item.path.startsWith('backend/'))assert.equal(item.sha256,hash(await fs.readFile(item.path)));}
  for(const item of manifest.inputs)assert.equal(hash(await fs.readFile(item.path)),item.sha256);
  const program=manifest.programs['registry-read'];assert.equal(hash(await fs.readFile(program.executable)),program.sha256);
  const root=path.resolve('build/acceptance/njl-'+randomUUID().slice(0,8));await fs.mkdir(root,{recursive:true});result.root=root;
  const input=path.join(root,'source.tiff');
  const pixels=Buffer.alloc(128*160*3);for(let y=0;y<160;y++)for(let x=0;x<128;x++){let i=(y*128+x)*3;pixels[i]=y<80?240:20;pixels[i+1]=x;pixels[i+2]=y;}
  await sharp(pixels,{raw:{width:128,height:160,channels:3,pageHeight:80}}).tiff().toFile(input);
  assert.equal((await sharp(input).metadata()).pages,2);
  const stampBytes=await sharp({create:{width:24,height:16,channels:4,background:{r:12,g:200,b:30,alpha:.7}}}).png().toBuffer();
  application=await createApplication({development:true,overrides:{server:{host:'127.0.0.1',port:0},upload:{minFreeBytes:0},playback:{enabled:false},storage:{root:path.join(root,'store')}}});
  const address=await application.listen(),base='http://127.0.0.1:'+address.port;
  const bootstrap=await fetch(base+'/api/v1/viewer/bootstrap');assert.equal(bootstrap.status,200);
  const cookie=bootstrap.headers.get('set-cookie').split(';')[0],csrf=(await bootstrap.json()).csrf.token;
  const form=new FormData();form.append('file',new Blob([stampBytes]),'synthetic-upload.png');
  const uploaded=await fetch(base+'/api/v1/stamps',{method:'POST',headers:{Cookie:cookie,'X-CSRF-TOKEN':csrf},body:form});assert.equal(uploaded.status,201);
  const record=await uploaded.json(),stamp=path.join(application.store.root,'stamps',record.stampId+'.png');
  const served=await fetch(base+record.url,{headers:{Cookie:cookie}});assert.equal(served.status,200);assert.equal(hash(Buffer.from(await served.arrayBuffer())),hash(await fs.readFile(stamp)));
  result.stampUpload={status:201,width:record.width,height:record.height,servedContentMatched:true};
  const inputHash=hash(await fs.readFile(input)),stampHash=hash(await fs.readFile(stamp));
  const preview=page=>({operation:'preview',input:'@INPUT@',output:'@PREVIEW@',thumbnail:'@THUMBNAIL@',page,maxPixels:1000000,edge:96});
  const jobs=[['page-1',preview(1)],['page-2',preview(2)],['stamp-export',{operation:'export',input:'@INPUT@',output:'@OUTPUT@',page:2,maxPixels:1000000,brightness:120,rotation:90,crop:{left:2,top:2,width:100,height:60},items:[{type:'stamp',file:'@STAMP@',x:10,y:12,width:24,height:16}]}]];
  const bind=(job,work)=>JSON.parse(JSON.stringify(job).replaceAll('"@INPUT@"',JSON.stringify(input)).replaceAll('"@PREVIEW@"',JSON.stringify(path.join(work,'preview.png'))).replaceAll('"@THUMBNAIL@"',JSON.stringify(path.join(work,'thumbnail.png'))).replaceAll('"@OUTPUT@"',JSON.stringify(path.join(work,'export.png'))).replaceAll('"@STAMP@"',JSON.stringify(stamp)));
  function args(template,target,mode='normal'){return [runtime.root,input,target,template,stamp,mode,'acceptance'];}
  async function launch(template,target,mode='normal'){return run(program.executable,args(template,target,mode),{windowsHide:true,timeout:30000,maxBuffer:16384});}
  for(const [name,job] of jobs){
    const template=path.join(root,name+'.json'),target=path.join(root,name),reference=path.join(root,name+'-ref');await fs.mkdir(reference);
    await fs.writeFile(template,JSON.stringify(job));await fs.writeFile(path.join(reference,'job.json'),JSON.stringify(bind(job,reference)));
    const baseline=await run(process.execPath,['--preserve-symlinks','--preserve-symlinks-main',path.join(runtime.root,'image-job-entry.mjs'),path.join(reference,'job.json')],{windowsHide:true,maxBuffer:16384});assert.equal(JSON.parse(baseline.stdout).ok,true);
    const execution=await launch(template,target);const line=execution.stdout.slice(execution.stdout.indexOf('diagnostics=')+12).trim();const facts=JSON.parse(line);assert.equal(facts.ok,true);
    assert.deepEqual(facts,JSON.parse(baseline.stdout));
    const names=job.operation==='preview'?['preview.png','thumbnail.png']:['export.png'];
    for(const file of names)assert.equal(hash(await fs.readFile(path.join(target,'out',file))),hash(await fs.readFile(path.join(reference,file))));
    const bound=JSON.parse(await fs.readFile(path.join(target,'out','job.json'),'utf8'));assert.equal(bound.input,path.join(target,'input','source.cjs'));if(job.items)assert.equal(bound.items[0].file,path.join(target,'input','stamp.png'));
    assert.equal(hash(await fs.readFile(path.join(target,'input','stamp.png'))),stampHash);
    result.cases.push({name,facts,identicalOutputs:names,manifestInputStampWriteDenied:true});
  }
  assert.notEqual(hash(await fs.readFile(path.join(root,'page-1/out/preview.png'))),hash(await fs.readFile(path.join(root,'page-2/out/preview.png'))));
  // Invalid page and an original host path must fail closed, without publishing images.
  for(const [name,job] of [['invalid-page',preview(3)],['host-path',{...preview(1),input}],['host-stamp',{...jobs[2][1],items:[{...jobs[2][1].items[0],file:stamp}]}]]){
    const template=path.join(root,name+'.json'),target=path.join(root,name);await fs.writeFile(template,JSON.stringify(job));
    await assert.rejects(launch(template,target),error=>error.code===22&&error.stdout.includes('"ok":false'));
    assert.deepEqual((await fs.readdir(path.join(target,'out'))).sort(),['job.json']);result.cases.push({name,rejected:true});
  }
  const template=path.join(root,'hold.json');await fs.writeFile(template,JSON.stringify(preview(1)));
  const state=path.join(root,'.imagegate-lpac-state');
  async function ready(target){const end=Date.now()+20000;while(Date.now()<end){try{return JSON.parse(await fs.readFile(path.join(target,'out','ready.json'),'utf8'));}catch(error){if(error.code!=='ENOENT')throw error;}await new Promise(resolve=>setTimeout(resolve,50));}throw Error('Timed out waiting for deterministic worker gate');}
  const target=path.join(root,'cancel');const child=spawn(program.executable,args(template,target,'hold'),{windowsHide:true,stdio:['ignore','pipe','pipe']});
  const closed=new Promise(resolve=>child.once('close',resolve));let diagnostics='';child.stdout.on('data',b=>{diagnostics+=b;});child.stderr.on('data',b=>{diagnostics+=b;});
  try{
    const worker=await ready(target);
    await assert.rejects(run(program.executable,['--recover',state],{windowsHide:true}),error=>error.code===25&&/busy=1/.test(error.stdout));
    child.kill('SIGKILL');await closed;
    const deadline=Date.now()+5000;let alive=true;while(Date.now()<deadline){try{process.kill(worker.pid,0);}catch(error){if(error.code==='ESRCH'){alive=false;break;}throw error;}await new Promise(resolve=>setTimeout(resolve,50));}assert.equal(alive,false,'LPAC child must die when launcher Job handle closes');
    const recovery=await run(program.executable,['--recover',state],{windowsHide:true});assert.match(recovery.stdout,/recovered=1 busy=0/);
    assert.equal((await fs.readdir(state)).filter(f=>f.endsWith('.lease')).length,0);
    // Preserve work for controlled inspection; recovery removes profiles/leases, not arbitrary outputs.
    assert.deepEqual((await fs.readdir(path.join(target,'out'))).sort(),['job.json','ready.json']);
    await assert.rejects(launch(template,target),error=>error.code===23&&/stage=new-root/.test(error.stdout));
    result.cases.push({name:'launcher-kill-recovery',workerExited:true,activeRecoveryDenied:true,profileRecovered:true,workPreserved:true});
  }finally{if(child.exitCode===null){child.kill('SIGKILL');await closed;}}
  const timed=path.join(root,'timeout');await assert.rejects(launch(template,timed,'timeout'),error=>error.code===23&&/stage=wall-timeout/.test(error.stdout));
  assert.equal((await fs.readdir(state)).filter(f=>f.endsWith('.lease')).length,0);result.cases.push({name:'wall-timeout',rejected:true,remainingLeases:0});
  assert.equal(hash(await fs.readFile(input)),inputHash);assert.equal(hash(await fs.readFile(stamp)),stampHash);
  for(const item of runtime.inventory)assert.equal(hash(await fs.readFile(path.join(runtime.root,item.path))),item.sha256);
  result.passed=true;
}catch(error){result.error={message:error.message,stdout:error.stdout,stderr:error.stderr};process.exitCode=1;}
finally{if(application)await application.close();await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify(result,null,2));
