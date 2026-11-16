import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import assert from 'node:assert/strict';
import {fork} from 'node:child_process';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {monitorEventLoopDelay,performance} from 'node:perf_hooks';
import {parse,stringify} from 'yaml';
import sharp from 'sharp';
import {createApplication} from '../backend/system/controller/application.mjs';
import {RuntimeVerifierPool} from '../backend/conversion/service/runtime-verifier-pool.mjs';
const mode=process.env.SIV_VERIFY_MODE,trial=process.env.SIV_VERIFY_TRIAL;assert.ok(['main','main-fifo','thread1','thread2'].includes(mode));assert.ok(['1','2'].includes(trial));
const output=path.resolve('build/reports/runtime-worker-comparison',trial+'-'+mode);await fs.mkdir(output,{recursive:true});
const result={passed:false,mode,trial,host:{platform:process.platform,release:os.release(),cpu:os.cpus()[0]?.model},phases:[]};
const previous=process.env.SIMPLEIMGVIEWER_CONFIG;let application,client,monitor,memoryTimer,pool;let stopMedia=true,mediaWork;
const summary=values=>{const sorted=values.toSorted((a,b)=>a-b);assert.ok(sorted.length);return {samples:sorted.length,medianMs:+((sorted[Math.floor((sorted.length-1)/2)]+sorted[Math.floor(sorted.length/2)])/2).toFixed(2),p95Ms:+sorted[Math.ceil(sorted.length*.95)-1].toFixed(2),maxMs:+sorted.at(-1).toFixed(2)};};
try{
 assert.ok(process.env.SIV_SPEED_YAML,'Set SIV_SPEED_YAML to an approved synthetic LPAC fixture');const config=parse(await fs.readFile(process.env.SIV_SPEED_YAML,'utf8'));assert.equal(config.isolation.mode,'lpac');assert.equal(config.runtimeVersions.enabled,true);assert.equal(config.playback.enabled,true);
 const root=path.resolve('build/acceptance/runtime-responsive-'+randomUUID());await fs.mkdir(root,{recursive:true});config.server={host:'127.0.0.1',port:0};config.storage.root=path.join(root,'store');config.isolation.root=path.join(root,'pool');config.upload={...config.upload,minFreeBytes:0};
 for(const kind of ['image','media']){const manifest=JSON.parse(await fs.readFile('build/lpac-'+kind+'/launcher.json','utf8'));config.isolation[kind+'Launcher']=manifest.executable;config.isolation[kind+'LauncherSha256']=manifest.sha256;}
 const yaml=path.join(root,'fixture.yml');await fs.writeFile(yaml,stringify(config));process.env.SIMPLEIMGVIEWER_CONFIG=yaml;application=await createApplication({development:true});const address=await application.listen(),base='http://127.0.0.1:'+address.port;
 if(mode==='main-fifo'){const direct=application.runtimes.versions.verifyTree.bind(application.runtimes.versions);let tail=Promise.resolve(),pending=0;application.runtimes.versions.verifyTree=(...args)=>{assert.ok(pending<9);pending++;const checking=tail.then(()=>direct(...args));tail=checking.catch(()=>{});return checking.finally(()=>{pending--;});};}
 if(mode!=='main'&&mode!=='main-fifo'){pool=new RuntimeVerifierPool({threads:mode==='thread1'?1:2});application.runtimes.versions.verifyTree=pool.verify.bind(pool);}
 const bootstrap=await fetch(base+'/api/v1/viewer/bootstrap'),cookie=bootstrap.headers.get('set-cookie').split(';')[0],csrf=(await bootstrap.json()).csrf.token;
 const form=new FormData();form.append('file',new Blob([await sharp({create:{width:800,height:500,channels:3,background:'#528bc7'}}).png().toBuffer()]),'synthetic.png');const upload=await fetch(base+'/api/v1/media',{method:'POST',headers:{Cookie:cookie,'X-CSRF-TOKEN':csrf},body:form});assert.equal(upload.status,201);const record=await upload.json(),page='/api/v1/media/'+record.mediaId+'/pages/1';const cold=await fetch(base+page,{headers:{Cookie:cookie}});assert.equal(cold.status,200);const info=await cold.json();
 const warmedLeases=await Promise.allSettled(['node','ffmpeg'].map(kind=>application.runtimes.acquire(kind)));try{for(const lease of warmedLeases)if(lease.status==='rejected')throw lease.reason;}finally{await Promise.all(warmedLeases.filter(lease=>lease.status==='fulfilled').map(lease=>lease.value.release()));}
 let renders=0;const run=application.images.run.bind(application.images);application.images.run=(...args)=>{renders++;return run(...args);};
 const proof=application.runtimes.versions.verifyTree.bind(application.runtimes.versions);let checks={node:0,ffmpeg:0};application.runtimes.versions.verifyTree=(payload,descriptor)=>{checks[descriptor.kind]++;return proof(payload,descriptor);};
 client=fork(fileURLToPath(new URL('../backend/test/conversion/fixtures/runtime-http-load.mjs',import.meta.url)),[],{execArgv:[],windowsHide:true,stdio:['ignore','ignore','pipe','ipc']});client.stderr.resume();const [ready]=await once(client,'message',{signal:AbortSignal.timeout(10000)});assert.equal(ready.ready,true);
 const warmed=once(client,'message',{signal:AbortSignal.timeout(10000)});client.send({base,cookie,page,source:info.source.url,load:false,durationMs:150});assert.equal((await warmed)[0].passed,true);
 for(const name of ['idle','image','mixed']){
  checks={node:0,ffmpeg:0};let mediaCount=0;stopMedia=false;
  if(name==='mixed'){mediaWork=(async()=>{while(!stopMedia){const lease=await application.runtimes.acquire('ffmpeg');try{mediaCount++;}finally{await lease.release();}}})();mediaWork.catch(()=>{});}
  monitor=monitorEventLoopDelay({resolution:5});monitor.enable();const utilization=performance.eventLoopUtilization(),started=performance.now();let peakRss=process.memoryUsage().rss,peakWorkers=pool?.slots.size||0,peakPending=0,peakQueued=0;memoryTimer=setInterval(()=>{peakRss=Math.max(peakRss,process.memoryUsage().rss);peakWorkers=Math.max(peakWorkers,pool?.slots.size||0);peakPending=Math.max(peakPending,pool?.pending.size||0);peakQueued=Math.max(peakQueued,pool?.queue.length||0);},50);
  const completed=once(client,'message',{signal:AbortSignal.timeout(20000)});client.send({base,cookie,page,source:info.source.url,load:name!=='idle',durationMs:name==='idle'?1500:5000});let observed;
  try{[observed]=await completed;assert.equal(observed.passed,true,observed.error);}finally{stopMedia=true;await mediaWork;mediaWork=null;clearInterval(memoryTimer);memoryTimer=null;monitor.disable();}
  const phase={name,wallMs:Math.round(performance.now()-started),proofs:{...checks},mediaAcquires:mediaCount,eventLoop:{p95Ms:+(monitor.percentile(95)/1e6).toFixed(2),p99Ms:+(monitor.percentile(99)/1e6).toFixed(2),maxMs:+(monitor.max/1e6).toFixed(2),utilization:+performance.eventLoopUtilization(utilization).utilization.toFixed(3)},peakRssBytes:peakRss,peakWorkers,peakPending,peakQueued,probes:Object.fromEntries(['health','bootstrap','content'].map(kind=>[kind,summary(observed.probes.filter(row=>row.kind===kind).map(row=>row.ms))])),securityChecks:observed.securityChecks,...(observed.pages.length?{pages:summary(observed.pages)}:{}),raw:observed};
  if(name==='idle')assert.deepEqual(checks,{node:0,ffmpeg:0});if(name!=='idle'){assert.ok(checks.node>=2);assert.ok(observed.pages.length>=6);}if(name==='mixed')assert.ok(checks.ffmpeg>=2&&mediaCount>=2);assert.ok(observed.securityChecks>=3);result.phases.push(phase);
 }
 assert.equal(renders,0);result.checks=['separate HTTP client process','repeated full runtime proofs under three-way cache page requests','zero warm decoder invocations','forged media/source and missing CSRF remain denied','all background runtime references drained'];result.passed=true;
}catch(error){result.error=error.stack;process.exitCode=1;}
finally{stopMedia=true;await mediaWork?.catch(()=>{});clearInterval(memoryTimer);monitor?.disable();if(client){const ended=client.exitCode!==null||client.signalCode!==null?Promise.resolve():once(client,'exit');client.kill('SIGKILL');await ended;}await pool?.close();if(pool){assert.equal(pool.slots.size,0);assert.equal(pool.pending.size,0);}await application?.close();if(previous===undefined)delete process.env.SIMPLEIMGVIEWER_CONFIG;else process.env.SIMPLEIMGVIEWER_CONFIG=previous;await fs.writeFile(path.join(output,'verification.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify({...result,phases:result.phases.map(({raw,...phase})=>phase)},null,2));
