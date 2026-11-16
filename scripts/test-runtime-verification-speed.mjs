import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {parse,stringify} from 'yaml';
import sharp from 'sharp';
import {createApplication} from '../backend/system/controller/application.mjs';
import {verifyRuntimeTree} from '../backend/conversion/util/runtime-inventory.mjs';
const report=path.resolve('build/reports/runtime-verification-speed');await fs.mkdir(report,{recursive:true});
const result={passed:false,host:{platform:process.platform,release:os.release(),cpu:os.cpus()[0]?.model},samples:[],checks:[]};
const previous=process.env.SIMPLEIMGVIEWER_CONFIG;let application;
try{
 assert.ok(process.env.SIV_SPEED_YAML,'Set SIV_SPEED_YAML to an approved synthetic LPAC fixture YAML');
 const config=parse(await fs.readFile(process.env.SIV_SPEED_YAML,'utf8'));
 assert.equal(config.isolation.mode,'lpac');assert.equal(config.runtimeVersions.enabled,true);
 const root=path.resolve('build/acceptance/runtime-speed-'+randomUUID());await fs.mkdir(root,{recursive:true});
 config.server={host:'127.0.0.1',port:0};config.storage.root=path.join(root,'store');config.isolation.root=path.join(root,'pool');config.upload={...config.upload,minFreeBytes:0};
 for(const kind of ['image','media']){const manifest=JSON.parse(await fs.readFile('build/lpac-'+kind+'/launcher.json','utf8'));config.isolation[kind+'Launcher']=manifest.executable;config.isolation[kind+'LauncherSha256']=manifest.sha256;}
 const yaml=path.join(root,'fixture.yml');await fs.writeFile(yaml,stringify(config));process.env.SIMPLEIMGVIEWER_CONFIG=yaml;
 application=await createApplication({development:true});const address=await application.listen(),base='http://127.0.0.1:'+address.port;
 const bootstrap=await fetch(base+'/api/v1/viewer/bootstrap');assert.equal(bootstrap.status,200);const cookie=bootstrap.headers.get('set-cookie').split(';')[0],csrf=(await bootstrap.json()).csrf.token;
 const request=(url,options={})=>fetch(base+url,{...options,headers:{Cookie:cookie,...(options.method==='POST'?{'X-CSRF-TOKEN':csrf}:{}),...options.headers}});
 const png=await sharp({create:{width:800,height:500,channels:3,background:'#3685bc'}}).png().toBuffer(),form=new FormData();form.append('file',new Blob([png]),'synthetic.png');const uploaded=await request('/api/v1/media',{method:'POST',body:form});assert.equal(uploaded.status,201);const record=await uploaded.json(),url='/api/v1/media/'+record.mediaId+'/pages/1';
 const cold=await request(url);assert.equal(cold.status,200);const expected=await cold.json();let renders=0;
 const originalRun=application.images.run.bind(application.images);application.images.run=(...args)=>{renders++;return originalRun(...args);};
 // Interleave/reverse pairs to reduce fixed mode-order bias. Full hashes always run.
 let reads=0,hashMs=0;
 for(let pair=0;pair<6;pair++)for(const readChunkBytes of pair%2?[1048576,65536]:[65536,1048576]){
  reads=0;hashMs=0;application.runtimes.versions.verifyTree=async(payload,descriptor)=>{const started=performance.now();reads++;try{return await verifyRuntimeTree(payload,descriptor,{readChunkBytes,fileConcurrency:1});}finally{hashMs+=performance.now()-started;}};
  const started=performance.now(),response=await request(url);assert.equal(response.status,200,await response.clone().text());assert.deepEqual(await response.json(),expected);const manifestMs=performance.now()-started;assert.equal(reads,1);
  const contentStart=performance.now(),content=await request(expected.source.url);assert.equal(content.status,200);const bytes=Buffer.from(await content.arrayBuffer());assert.equal((await sharp(bytes).metadata()).width,800);
  result.samples.push({pair,readChunkBytes,manifestMs,hashMs,contentMs:performance.now()-contentStart});
 }
 assert.equal(renders,0);result.checks.push('every warm manifest performs a full runtime tree hash','identical page metadata across read sizes','zero warm decoder calls','cached private PNG remains readable');
 const summarize=values=>{const sorted=values.toSorted((a,b)=>a-b);return {samples:sorted.length,medianMs:Math.round((sorted[Math.floor((sorted.length-1)/2)]+sorted[Math.floor(sorted.length/2)])/2),maxMs:Math.round(sorted.at(-1))};};
 result.summary=[65536,1048576].map(readChunkBytes=>{const samples=result.samples.filter(row=>row.readChunkBytes===readChunkBytes);return {readChunkBytes,manifest:summarize(samples.map(row=>row.manifestMs)),hash:summarize(samples.map(row=>row.hashMs)),content:summarize(samples.map(row=>row.contentMs))};});result.passed=true;
}catch(error){result.error=error.stack;process.exitCode=1;}
finally{try{await application?.close();}finally{if(previous===undefined)delete process.env.SIMPLEIMGVIEWER_CONFIG;else process.env.SIMPLEIMGVIEWER_CONFIG=previous;await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}}
console.log(JSON.stringify(result,null,2));
