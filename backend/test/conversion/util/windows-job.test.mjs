import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {fork,spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {settings} from '../../../system/config/settings.mjs';
import {PlaybackService} from '../../../playback/service/ffmpeg.mjs';
import {WindowsJobRunner} from '../../../conversion/util/windows-job.mjs';
import {WorkDirectories} from '../../../conversion/service/work-directories.mjs';

const native=process.env.SIV_NATIVE_TESTS==='1'&&process.platform==='win32';
async function fixture(t){const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-job-test-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));return root;}
async function until(action){for(let i=0;i<200;i++){if(await action())return;await new Promise(resolve=>setTimeout(resolve,25));}assert.fail('Job containment did not settle');}
const alive=pid=>{try{process.kill(pid,0);return true;}catch(error){if(error.code==='ESRCH')return false;throw error;}};

for(const mode of ['parent-crash','launcher-crash','service-close'])test('Windows Job contains probe on '+mode,{skip:!native},async t=>{
  const root=await fixture(t),record=path.join(root,'한글 공백 probe.json');
  const parent=fork(fileURLToPath(new URL('../../fixtures/job-parent.mjs',import.meta.url)),[record],{execArgv:[],windowsHide:true,stdio:['ignore','ignore','pipe','ipc']});
  t.after(async()=>{if(parent.exitCode===null&&parent.signalCode===null){const exit=once(parent,'exit');parent.kill('SIGKILL');await exit;}});
  const [{launcherPid}]=await once(parent,'message',{signal:AbortSignal.timeout(10000)});
  let probe;await until(async()=>{try{probe=JSON.parse(await fs.readFile(record,'utf8'));return true;}catch{return false;}});
  assert.equal(probe.jobConfirmed,true);assert.equal(probe.stdinIsNull,true);assert.equal(alive(probe.pid),true);
  if(mode==='parent-crash'){const exit=once(parent,'exit');parent.kill('SIGKILL');await exit;}
  else if(mode==='launcher-crash')parent.send('kill-launcher');
  else {const exit=once(parent,'exit');parent.send('close');await exit;}
  await until(()=>!alive(probe.pid));await until(()=>!alive(launcherPid));
});

test('Windows Job preserves exact arguments and refuses additional child processes',{skip:!native},async t=>{
  const root=await fixture(t),service=new PlaybackService(await settings({development:true}),{});t.after(()=>service.close());
  const args=['한글 공백','quote"inside','ends\\','a\\"b',''];
  const result=await service.run(process.execPath,['-e','console.log(JSON.stringify(process.argv.slice(1)))','--',...args]);assert.deepEqual(JSON.parse(result),args);
  const marker=path.join(root,'unexpected-child');
  const script=`try{const {spawn}=require('node:child_process');const c=spawn(process.execPath,['-e','require("node:fs").writeFileSync(process.argv[1],"bad")',process.argv[1]]);c.on('error',()=>console.log('blocked'));c.on('spawn',()=>console.log('unexpected'));}catch{console.log('blocked');}`;
  assert.match(await service.run(process.execPath,['-e',script,marker]),/blocked/);await assert.rejects(fs.stat(marker),{code:'ENOENT'});
});

test('Windows Job rejects a missing configuration and sticky launcher hash changes',{skip:!native},async t=>{
  const root=await fixture(t),config=await settings({development:true});
  await assert.rejects(new WindowsJobRunner({}).command(process.execPath,[]),{code:'PROCESS_JOB_CONFIGURATION_REQUIRED'});
  const copy=path.join(root,'launcher.exe'),original=await fs.readFile(config.playback.jobLauncher);await fs.writeFile(copy,original);
  const runner=new WindowsJobRunner({...config.playback,jobLauncher:copy});await runner.command(process.execPath,[]);
  await fs.appendFile(copy,'modified');await assert.rejects(runner.command(process.execPath,[]),{code:'PROCESS_JOB_CHANGED'});
  await fs.writeFile(copy,original);await assert.rejects(runner.command(process.execPath,[]),{code:'PROCESS_JOB_CHANGED'});
});

test('Windows Job refuses startup after parent pipe closes',{skip:!native},async t=>{
  const root=await fixture(t),config=await settings({development:true}),probe=JSON.parse(await fs.readFile('build/process-job/probe.json','utf8'));
  const record=path.join(root,'must-not-run.json');
  const command=await new WindowsJobRunner(config.playback).command(probe.executable,[record]);
  const child=spawn(command.executable,command.args,{windowsHide:true,stdio:['pipe','ignore','ignore']});
  const closed=once(child,'close');child.stdin.on('error',()=>{});child.stdin.end();assert.notEqual((await closed)[0],0);
  await assert.rejects(fs.stat(record),{code:'ENOENT'});
});

test('Windows Job enforces committed memory and CPU rate settings',{skip:!native},async t=>{
  const config=await settings({development:true,overrides:{playback:{enabled:false,jobMemoryMiB:64,jobCpuSeconds:10,jobCpuRatePercent:25}}});
  const service=new PlaybackService(config,{});t.after(()=>service.close());
  const probe=JSON.parse(await fs.readFile('build/process-job/probe.json','utf8'));
  const result=JSON.parse(await service.run(probe.executable,['--memory','unused']));
  assert.equal(result.denied,true);assert.equal(result.memoryMiB,64);assert.equal(result.cpuRate,2500);assert.ok(result.allocatedBytes>0&&result.allocatedBytes<64*1024*1024);
});

test('Windows Job terminates a CPU-bound process before the wall timeout',{skip:!native},async t=>{
  const root=await fixture(t),record=path.join(root,'cpu.json');
  const config=await settings({development:true,overrides:{playback:{enabled:false,jobCpuSeconds:1}}});
  const service=new PlaybackService(config,{});t.after(()=>service.close());
  const probe=JSON.parse(await fs.readFile('build/process-job/probe.json','utf8'));
  await assert.rejects(service.run(probe.executable,['--cpu',record],20000),{code:'INVALID_MEDIA'});
  const processInfo=JSON.parse(await fs.readFile(record,'utf8'));assert.equal(processInfo.cpuSeconds,1);assert.equal(processInfo.cpuRate,10000);assert.equal(alive(processInfo.pid),false);assert.equal(service.children.size,0);
});

test('resource settings reject disabled or excessive limits',async()=>{
  for(const playback of [{jobMemoryMiB:0},{jobMemoryMiB:4097},{jobCpuSeconds:0},{jobCpuSeconds:601},{jobCpuRatePercent:0},{jobCpuRatePercent:101}])
    await assert.rejects(settings({overrides:{playback}}),{code:'INVALID_CONFIGURATION'});
});

test('actual FFmpeg protects its work until parent crash and recovery blocks delayed execution',{skip:!native},async t=>{
  const root=await fixture(t),policy={recoveryEnabled:true,minAgeMs:60000,scanLimit:100};
  const workManager=new WorkDirectories(root,policy,'playback');await workManager.init();const work=await workManager.create();
  const parent=fork(fileURLToPath(new URL('../../fixtures/job-parent.mjs',import.meta.url)),[path.join(root,'unused'),'ffmpeg',work],{execArgv:[],windowsHide:true,stdio:['ignore','ignore','pipe','ipc']});
  t.after(async()=>{if(parent.exitCode===null&&parent.signalCode===null){const exited=once(parent,'exit');parent.kill('SIGKILL');await exited;}});
  const [{launcherPid}]=await once(parent,'message',{signal:AbortSignal.timeout(15000)});
  assert.ok(Number.isSafeInteger(launcherPid)&&launcherPid>0);
  const script=`Get-CimInstance Win32_Process -Filter 'ParentProcessId = ${launcherPid}' | Select-Object ProcessId,ExecutablePath | ConvertTo-Json -Compress`;
  const {stdout}=await promisify(execFile)('powershell.exe',['-NoProfile','-Command',script],{windowsHide:true,timeout:10000});
  const processes=JSON.parse(stdout.trim()),config=await settings({development:true});
  const matches=(Array.isArray(processes)?processes:[processes]).filter(p=>typeof p.ExecutablePath==='string'&&path.resolve(p.ExecutablePath).toLowerCase()===path.resolve(config.playback.ffmpeg).toLowerCase());
  assert.equal(matches.length,1,JSON.stringify(processes));const child=matches[0];
  assert.equal(alive(child.ProcessId),true);
  await until(async()=>{try{await fs.stat(path.join(work,'playback'));return true;}catch{return false;}});
  const old=new Date(Date.now()-120000);await fs.utimes(work,old,old);
  const recovery=new WorkDirectories(root,policy,'playback'),protectedWork=await recovery.init();
  assert.equal(protectedWork.recovered,0);assert.equal(protectedWork.busy+protectedWork.preserved,1);await fs.stat(path.join(work,'playback'));
  const exited=once(parent,'exit');parent.kill('SIGKILL');await exited;
  await until(()=>!alive(child.ProcessId));await until(()=>!alive(launcherPid));
  assert.equal((await recovery.init()).recovered,1);await assert.rejects(fs.stat(path.join(work,'playback')),{code:'ENOENT'});
  const service=new PlaybackService(config,{});
  try{await assert.rejects(service.run(config.playback.ffmpeg,['-version'],30000,work),{code:'INVALID_MEDIA'});}finally{await service.close();}
  await fs.stat(path.join(work,'retired'));await workManager.release(work);await workManager.close();
});
