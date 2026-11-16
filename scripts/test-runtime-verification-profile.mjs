import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {parse,stringify} from 'yaml';
import sharp from 'sharp';
import {createApplication} from '../backend/system/controller/application.mjs';
import {verifyRuntimeTree} from '../backend/conversion/util/runtime-inventory.mjs';
const report=path.resolve('build/reports/runtime-verification-profile');await fs.mkdir(report,{recursive:true});
const result={passed:false,host:{platform:process.platform,release:os.release(),cpu:os.cpus()[0]?.model},samples:[],checks:[]};
const previous=process.env.SIMPLEIMGVIEWER_CONFIG;let application;
try{
 assert.ok(process.env.SIV_SPEED_YAML,'Set SIV_SPEED_YAML to an approved synthetic LPAC fixture YAML');
 const config=parse(await fs.readFile(process.env.SIV_SPEED_YAML,'utf8'));
 assert.equal(config.isolation.mode,'lpac');assert.equal(config.runtimeVersions.enabled,true);
 const root=path.resolve('build/acceptance/runtime-profile-'+randomUUID());await fs.mkdir(root,{recursive:true});
 config.server={host:'127.0.0.1',port:0};config.storage.root=path.join(root,'store');config.isolation.root=path.join(root,'pool');config.upload={...config.upload,minFreeBytes:0};
 for(const kind of ['image','media']){const manifest=JSON.parse(await fs.readFile('build/lpac-'+kind+'/launcher.json','utf8'));config.isolation[kind+'Launcher']=manifest.executable;config.isolation[kind+'LauncherSha256']=manifest.sha256;}
 const yaml=path.join(root,'fixture.yml');await fs.writeFile(yaml,stringify(config));process.env.SIMPLEIMGVIEWER_CONFIG=yaml;
 application=await createApplication({development:true});const address=await application.listen(),base='http://127.0.0.1:'+address.port;
 const bootstrap=await fetch(base+'/api/v1/viewer/bootstrap');assert.equal(bootstrap.status,200);const cookie=bootstrap.headers.get('set-cookie').split(';')[0],csrf=(await bootstrap.json()).csrf.token;
 const request=(url,options={})=>fetch(base+url,{...options,headers:{Cookie:cookie,...(options.method==='POST'?{'X-CSRF-TOKEN':csrf}:{}),...options.headers}});
 const png=await sharp({create:{width:800,height:500,channels:3,background:'#3685bc'}}).png().toBuffer(),form=new FormData();form.append('file',new Blob([png]),'synthetic.png');const uploaded=await request('/api/v1/media',{method:'POST',body:form});assert.equal(uploaded.status,201);const record=await uploaded.json(),url='/api/v1/media/'+record.mediaId+'/pages/1';
 const cold=await request(url);assert.equal(cold.status,200);const expected=await cold.json();let renders=0;
 const originalRun=application.images.run.bind(application.images);application.images.run=(...args)=>{renders++;return originalRun(...args);};
 // Compare bounded candidates with and without instrumentation; rotate execution order.
 const originalFont=application.runtimes.verifyNoteFont.bind(application.runtimes);let fontMs=0;
 application.runtimes.verifyNoteFont=async root=>{const started=performance.now();try{return await originalFont(root);}finally{fontMs+=performance.now()-started;}};
 for(const profiled of [true,false])for(let pair=0;pair<6;pair++){
  const candidates=[1,2,4];for(let shift=0;shift<pair%3;shift++)candidates.push(candidates.shift());if(pair%2)candidates.reverse();
  for(const fileConcurrency of candidates){
   let calls=0,treeStats,treeMs=0,expectedFiles,expectedBytes;fontMs=0;
   application.runtimes.versions.verifyTree=async(payload,descriptor)=>{const started=performance.now();calls++;expectedFiles=descriptor.files.length;expectedBytes=descriptor.bytes;try{return await verifyRuntimeTree(payload,descriptor,{fileConcurrency,observe:profiled?stats=>{treeStats=stats;}:undefined});}finally{treeMs+=performance.now()-started;}};
   const started=performance.now(),response=await request(url);assert.equal(response.status,200,await response.clone().text());assert.deepEqual(await response.json(),expected);const manifestMs=performance.now()-started;assert.equal(calls,1);if(profiled){assert.equal(treeStats.passed,true);assert.equal(treeStats.files,expectedFiles);assert.equal(treeStats.bytes,expectedBytes);}
   const content=await request(expected.source.url);assert.equal(content.status,200);assert.equal((await sharp(Buffer.from(await content.arrayBuffer())).metadata()).width,800);
   result.samples.push({profiled,pair,fileConcurrency,manifestMs,treeMs,fontMs,treeStats});
  }
 }
 result.concurrent=[];
 for(let pair=0;pair<4;pair++)for(const fileConcurrency of pair%2?[4,2,1]:[1,2,4]){
  application.runtimes.versions.verifyTree=(payload,descriptor)=>verifyRuntimeTree(payload,descriptor,{fileConcurrency});
  const start=performance.now(),leases=await Promise.allSettled(['node','ffmpeg'].map(kind=>application.runtimes.acquire(kind)));
  try{for(const lease of leases)if(lease.status==='rejected')throw lease.reason;result.concurrent.push({pair,fileConcurrency,wallMs:performance.now()-start});}finally{await Promise.all(leases.filter(lease=>lease.status==='fulfilled').map(lease=>lease.value.release()));}
 }
 assert.equal(renders,0);result.checks.push('every manifest fully rehashes the runtime','profile confirms the full descriptor byte/file count','identical metadata, zero warm renders and readable private PNG');
 const median=values=>{const sorted=values.toSorted((a,b)=>a-b);return Math.round((sorted[Math.floor((sorted.length-1)/2)]+sorted[Math.floor(sorted.length/2)])/2);};
 result.summary=[true,false].flatMap(profiled=>[1,2,4].map(fileConcurrency=>{const samples=result.samples.filter(row=>row.profiled===profiled&&row.fileConcurrency===fileConcurrency);return {profiled,fileConcurrency,samples:samples.length,manifestMs:median(samples.map(row=>row.manifestMs)),treeMs:median(samples.map(row=>row.treeMs)),fontMs:median(samples.map(row=>row.fontMs)),...(profiled?Object.fromEntries(['rootMs','inventoryMs','fileMetadataMs','readMs','hashMs'].map(key=>[key,median(samples.map(row=>row.treeStats[key]))])):{})};}));result.concurrentSummary=[1,2,4].map(fileConcurrency=>({fileConcurrency,samples:4,medianMs:median(result.concurrent.filter(row=>row.fileConcurrency===fileConcurrency).map(row=>row.wallMs))}));result.passed=true;
}catch(error){result.error=error.stack;process.exitCode=1;}
finally{try{await application?.close();}finally{if(previous===undefined)delete process.env.SIMPLEIMGVIEWER_CONFIG;else process.env.SIMPLEIMGVIEWER_CONFIG=previous;await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}}
console.log(JSON.stringify({...result,samples:undefined,concurrent:undefined},null,2));
