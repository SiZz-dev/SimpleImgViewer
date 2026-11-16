import fs from 'node:fs/promises';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash,randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import sharp from 'sharp';
const hash=b=>createHash('sha256').update(b).digest('hex'),run=promisify(execFile);
const report=path.resolve('build/reports/node-sharp-lpac');await fs.mkdir(report,{recursive:true});
const result={testOnly:true,productionEnabled:false,passed:false,cases:[]};
try{
  const runtime=JSON.parse(await fs.readFile('build/node-sharp-lpac/runtime.json','utf8'));
  const launcher=JSON.parse(await fs.readFile('build/node-sharp-lpac/launcher.json','utf8'));
  assert.equal(runtime.testOnly,true);assert.equal(launcher.testOnly,true);
  assert.equal(hash(await fs.readFile('pnpm-lock.yaml')),runtime.lockfileSha256);
  async function files(directory,prefix=''){
    const found=[];
    for(const entry of await fs.readdir(directory,{withFileTypes:true})){
      assert.ok(!entry.isSymbolicLink());const relative=prefix+entry.name;
      if(entry.isDirectory())found.push(...await files(path.join(directory,entry.name),relative+'/'));
      else {assert.ok(entry.isFile());found.push(relative);}
    }
    return found;
  }
  assert.deepEqual((await files(runtime.root)).sort(),runtime.inventory.map(item=>item.path).sort(),'Reject unrecorded runtime files');
  for(const item of runtime.inventory){const file=path.join(runtime.root,item.path),stat=await fs.lstat(file);assert.ok(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1);assert.equal(hash(await fs.readFile(file)),item.sha256);}
  assert.equal(hash(await fs.readFile(path.join(runtime.root,'sharp-worker.cjs'))),hash(await fs.readFile('native/process-job/tests/sharp-worker.cjs')));
  for(const item of launcher.inputs)assert.equal(hash(await fs.readFile(item.path)),item.sha256,'Rebuild changed source');
  const program=launcher.programs['registry-read'];assert.equal(hash(await fs.readFile(program.executable)),program.sha256);
  const root=path.resolve('build/acceptance/nsl-'+randomUUID().slice(0,8));await fs.mkdir(root,{recursive:true});result.root=root;
  const forbidden=path.join(root,'forbidden.txt');await fs.writeFile(forbidden,'private synthetic marker');
  const pixels=Buffer.alloc(128*80*3);for(let y=0;y<80;y++)for(let x=0;x<128;x++){const i=(y*128+x)*3;pixels[i]=x*2;pixels[i+1]=y*3;pixels[i+2]=(x+y)%256;}
  const source=()=>sharp(pixels,{raw:{width:128,height:80,channels:3}});
  for(const format of ['png','jpeg','webp','gif','tiff']){
    const input=path.join(root,'input.'+format);await source().toFormat(format).toFile(input);
    const originalHash=hash(await fs.readFile(input)),target=path.join(root,format),reference=path.join(root,format+'-reference');await fs.mkdir(reference);
    // Reference uses the identical trusted worker outside LPAC; only image bytes are compared.
    await run(process.execPath,['--preserve-symlinks','--preserve-symlinks-main',path.join(runtime.root,'sharp-worker.cjs'),input,reference,forbidden],{windowsHide:true,maxBuffer:16384}).catch(error=>{assert.equal(error.code,1);assert.equal(JSON.parse(error.stdout).passed,false,'Reference worker must finish conversion');});
    const start=performance.now();const execution=await run(program.executable,[runtime.root,input,target,forbidden],{windowsHide:true,timeout:30000,maxBuffer:16384});
    const checks=JSON.parse(await fs.readFile(path.join(target,'out','probe.json'),'utf8'));assert.equal(checks.passed,true,execution.stdout);assert.equal(checks.sharp,'0.35.5');
    const images=[];
    for(const name of ['preview.png','thumbnail.png','crop.png','edited.png']){
      const file=path.join(target,'out',name),stat=await fs.lstat(file);assert.ok(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1&&stat.size<=33554432);
      const actual=await fs.readFile(file),expected=await fs.readFile(path.join(reference,name));assert.equal(hash(actual),hash(expected),format+'/'+name);
      const metadata=await sharp(actual).metadata();images.push({name,width:metadata.width,height:metadata.height,sha256:hash(actual)});
    }
    assert.equal(hash(await fs.readFile(input)),originalHash);assert.equal(await fs.readFile(forbidden,'utf8'),'private synthetic marker');
    for(const item of runtime.inventory)assert.equal(hash(await fs.readFile(path.join(target,'bin',item.path))),item.sha256);
    const ownerControl=await fs.open(path.join(target,'bin','LICENSE'),'r+');await ownerControl.close();
    result.cases.push({format,elapsedMs:Math.round(performance.now()-start),checks,images,runtimeOwnerWriteControl:true});
  }
  const leases=await fs.readdir(path.join(root,'.imagegate-lpac-state'));assert.equal(leases.filter(name=>name.endsWith('.lease')).length,0);
  result.runtime={packages:runtime.packages,files:runtime.inventory.length,bytes:runtime.inventory.reduce((n,item)=>n+item.bytes,0)};
  result.remaining=['Production job JSON and uploaded stamp binding','Multipage and cancellation/recovery acceptance','FFmpeg LPAC acceptance','Runtime reuse and concurrency performance'];
  result.passed=true;
}catch(error){result.error={message:error.message,stdout:error.stdout,stderr:error.stderr};process.exitCode=1;}
finally{await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify({passed:result.passed,testOnly:true,productionEnabled:false,cases:result.cases.map(item=>({format:item.format,elapsedMs:item.elapsedMs,images:item.images.length,denied:item.checks.denied})),error:result.error,report}));
