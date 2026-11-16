import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import {execFile,spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash,randomUUID} from 'node:crypto';
const run=promisify(execFile),hash=b=>createHash('sha256').update(b).digest('hex');
const report=path.resolve('build/reports/shared-ffmpeg-lpac');await fs.mkdir(report,{recursive:true});
const result={testOnly:true,productionEnabled:false,passed:false,cases:[],measurements:[]};
try{
  const runtime=JSON.parse(await fs.readFile('build/ffmpeg-lpac/runtime.json','utf8')),manifest=JSON.parse(await fs.readFile('build/shared-ffmpeg-lpac/launcher.json','utf8'));
  assert.equal(runtime.testOnly,true);assert.equal(manifest.testOnly,true);
  for(const item of manifest.inputs)assert.equal(hash(await fs.readFile(item.path)),item.sha256);
  const program=manifest.programs['shared-test'];assert.equal(hash(await fs.readFile(program.executable)),program.sha256);
  for(const item of runtime.inventory)assert.equal(hash(await fs.readFile(path.join(runtime.root,item.path))),item.sha256);
  const call=(exe,args)=>run(exe,args,{windowsHide:true,timeout:30000,maxBuffer:1048576});
  const root=path.resolve('build/acceptance/shared-fml-'+randomUUID().slice(0,8));await fs.mkdir(root,{recursive:true});result.root=root;
  const cache=path.join(root,'runtime'),started=performance.now();
  const digest=(await call(program.executable,['--prepare',runtime.root,cache])).stdout.trim();assert.match(digest,/^[a-f0-9]{64}$/);
  const expected=hash(Buffer.from([...runtime.inventory].sort((a,b)=>a.path<b.path?-1:1).map(item=>item.path+'\t'+item.bytes+'\t'+item.sha256+'\n').join('')));assert.equal(digest,expected);
  result.preparationMs=Math.round(performance.now()-started);result.runtimeBytes=runtime.inventory.reduce((n,item)=>n+item.bytes,0);
  for(const item of runtime.inventory)assert.equal(hash(await fs.readFile(path.join(cache,item.path))),item.sha256);
  const ffmpeg=path.join(runtime.root,'ffmpeg.exe'),sources=[];
  for(const frequency of [440,880]){const input=path.join(root,'sine-'+frequency+'.wav');await call(ffmpeg,['-v','error','-nostdin','-f','lavfi','-i','sine=frequency='+frequency+':sample_rate=48000','-t','2','-c:a','pcm_s16le',input]);sources.push(input);}
  const args=(input,target,mode='audio-wav',extra='-',reuse=true)=>[reuse?cache:runtime.root,input,target,mode,extra,...(reuse?['shared',digest]:[])];
  const launch=(...parameters)=>call(program.executable,args(...parameters));
  const decode=async file=>(await run(ffmpeg,['-v','error','-nostdin','-i',file,'-map','0:a:0','-acodec','pcm_s16le','-f','s16le','pipe:1'],{windowsHide:true,encoding:'buffer',timeout:30000,maxBuffer:4194304})).stdout;
  let baseline;
  // Alternate modes to limit the effect of order/cache warming; report observations, not an SLA.
  for(let iteration=0;iteration<3;iteration++)for(const reuse of iteration%2?[true,false]:[false,true]){
    const target=path.join(root,(reuse?'shared-':'copy-')+iteration),start=performance.now();await launch(sources[0],target,'audio-wav','-',reuse);const elapsedMs=performance.now()-start;
    const pcm=await decode(path.join(target,'out/playback'));if(!baseline)baseline=hash(pcm);assert.equal(hash(pcm),baseline);
    const bin=await fs.readdir(path.join(target,'bin'));for(const item of runtime.inventory)assert.equal(bin.includes(item.path),!reuse);
    result.measurements.push({reuse,iteration,elapsedMs:Math.round(elapsedMs),runtimeBytesCopied:reuse?0:result.runtimeBytes});
  }
  result.cases.push({name:'sequential-equivalence',iterations:3,pcmMatched:true,sharedJobRuntimeCopies:0});
  const parallelStart=performance.now(),targets=sources.map((_,i)=>path.join(root,'parallel-'+i));
  const parallel=await Promise.allSettled(sources.map((input,i)=>launch(input,targets[i])));for(const item of parallel){assert.equal(item.status,'fulfilled',item.reason?.message);}
  const audio=await Promise.all(targets.map(target=>decode(path.join(target,'out/playback'))));assert.equal(hash(audio[0]),baseline);assert.notEqual(hash(audio[0]),hash(audio[1]));
  result.cases.push({name:'two-concurrent-distinct-inputs',elapsedMs:Math.round(performance.now()-parallelStart),bothCompleted:true,outputsDistinct:true});
  const hold=path.join(root,'hold'),child=spawn(program.executable,args(sources[0],hold,'hold'),{windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.resume();child.stderr.resume();const closed=new Promise(resolve=>child.once('close',resolve));
  try{
    const end=Date.now()+15000;let ready=false;while(Date.now()<end){try{if((await fs.stat(path.join(hold,'out/playback'))).size>1024){ready=true;break;}}catch(error){if(error.code!=='ENOENT')throw error;}await new Promise(resolve=>setTimeout(resolve,50));}assert.equal(ready,true);
    // Unloaded license is pinned too: this is not merely the EXE image-section lock.
    await assert.rejects(fs.open(path.join(cache,'LICENSE.txt'),'r+'),error=>['EACCES','EPERM','EBUSY'].includes(error.code));
    const marker=path.join(hold,'out','private.wav');await fs.copyFile(sources[1],marker);const markerHash=hash(await fs.readFile(marker));
    for(const [name,mode,extra] of [['other-input','outside-read',path.join(hold,'input/source.media')],['other-output','outside-read',marker],['other-output-write','outside-write',marker],['shared-runtime-write','runtime-write','-']]){
      await assert.rejects(launch(sources[0],path.join(root,name),mode,extra),error=>error.code===22&&/Permission denied|Operation not permitted/.test(error.stderr));
      result.cases.push({name,rejected:true});
    }
    assert.equal(hash(await fs.readFile(marker)),markerHash);
    const state=path.join(root,'.imagegate-lpac-state');await assert.rejects(call(program.executable,['--recover',state]),error=>error.code===25&&/busy=1/.test(error.stdout));
    child.kill('SIGKILL');await closed;let recovered=false;
    for(let i=0;i<50;i++){try{const recovery=await call(program.executable,['--recover',state]);if(/recovered=1 busy=0/.test(recovery.stdout)){recovered=true;break;}}catch(error){if(error.code!==25)throw error;}await new Promise(resolve=>setTimeout(resolve,50));}assert.equal(recovered,true);
    const control=await fs.open(path.join(cache,'LICENSE.txt'),'r+');await control.close();
    result.cases.push({name:'pin-lifetime-and-kill-recovery',ownerWriteDeniedWhileActive:true,ownerWriteAllowedAfterExit:true,profileRecovered:true});
  }finally{if(child.exitCode===null&&child.signalCode===null){child.kill('SIGKILL');await closed;}}
  // Same-user publisher changes are detected on the next admission; no copy fallback.
  const license=path.join(cache,'LICENSE.txt'),original=await fs.readFile(license);await fs.appendFile(license,'\nsynthetic tamper');
  try{await assert.rejects(launch(sources[0],path.join(root,'tamper')) ,error=>error.code===23&&/stage=shared-runtime-digest/.test(error.stdout));assert.equal(await fs.stat(path.join(root,'tamper')).then(()=>true,()=>false),false);}finally{await fs.writeFile(license,original);}
  result.cases.push({name:'tampered-runtime',rejectedBeforeJobCreation:true});
  const extra=path.join(cache,'extra.dll');await fs.writeFile(extra,'unrecognized');
  try{await assert.rejects(launch(sources[0],path.join(root,'extra')),error=>error.code===23&&/stage=shared-runtime-inventory/.test(error.stdout));}finally{await fs.unlink(extra);}
  result.cases.push({name:'unrecorded-runtime-file',rejected:true});
  const link=path.join(root,'license-hardlink');await fs.link(license,link);
  try{await assert.rejects(launch(sources[0],path.join(root,'hardlink')),error=>error.code===23&&/stage=regular-file/.test(error.stdout));}finally{await fs.unlink(link);}
  result.cases.push({name:'multiply-linked-runtime-file',rejected:true});
  // Both holders must exist at once; stopping one must leave the other's pin and Job alive.
  const holders=[0,1].map(i=>{const target=path.join(root,'holder-'+i),child=spawn(program.executable,args(sources[i],target,'hold'),{windowsHide:true,stdio:['ignore','pipe','pipe']});const holder={target,child,diagnostics:'',closed:new Promise(resolve=>child.once('close',resolve))};for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{holder.diagnostics=(holder.diagnostics+bytes).slice(0,16384);});return holder;});
  try{
    for(const holder of holders){const end=Date.now()+15000;let writing=false;while(Date.now()<end){try{if((await fs.stat(path.join(holder.target,'out/playback'))).size>1024){writing=true;break;}}catch(error){if(error.code!=='ENOENT')throw error;}await new Promise(resolve=>setTimeout(resolve,50));}assert.equal(writing,true);}
    const state=path.join(root,'.imagegate-lpac-state');await assert.rejects(call(program.executable,['--recover',state]),error=>error.code===25&&/busy=2/.test(error.stdout));
    holders[0].child.kill('SIGKILL');await holders[0].closed;
    await assert.rejects(call(program.executable,['--recover',state]),error=>error.code===25&&/recovered=1 busy=1/.test(error.stdout));
    assert.equal(holders[1].child.exitCode,null);assert.equal(holders[1].child.signalCode,null);
    const file=path.join(holders[1].target,'out/playback'),before=(await fs.stat(file)).size;
    const end=Date.now()+3000;let increased=false;while(Date.now()<end){if((await fs.stat(file)).size>before){increased=true;break;}await new Promise(resolve=>setTimeout(resolve,100));}assert.equal(increased,true,'Second FFmpeg must keep writing: '+holders[1].diagnostics);
    await assert.rejects(fs.open(license,'r+'),error=>['EACCES','EPERM','EBUSY'].includes(error.code));
    holders[1].child.kill('SIGKILL');await holders[1].closed;
    assert.match((await call(program.executable,['--recover',state])).stdout,/recovered=1 busy=0/);
    const writable=await fs.open(license,'r+');await writable.close();
    result.cases.push({name:'two-active-jobs-independent-cancellation',bothWriting:true,secondContinuedAfterFirstKill:true,pinHeldUntilLastExit:true});
  }finally{await Promise.all(holders.map(async holder=>{if(holder.child.exitCode===null&&holder.child.signalCode===null){holder.child.kill('SIGKILL');await holder.closed;}}));}
  const post=path.join(root,'after-recovery');await launch(sources[0],post);assert.equal(hash(await decode(path.join(post,'out/playback'))),baseline);
  const metadata=await launch(path.join(post,'out/playback'),path.join(root,'shared-probe'),'probe-mp3');
  const facts=JSON.parse(metadata.stdout.slice(metadata.stdout.indexOf('diagnostics=')+12));assert.equal(facts.streams[0].codec_name,'mp3');assert.ok(Number(facts.format.duration)>1.8);
  result.cases.push({name:'shared-ffprobe',codec:'mp3',stdoutJsonValid:true});
  const video=path.join(root,'blue.mp4');await call(ffmpeg,['-v','error','-nostdin','-f','lavfi','-i','color=c=blue:size=160x96:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','2','-c:v','mpeg4','-c:a','aac','-movflags','+faststart',video]);
  const videoBuffers=[];
  for(const reuse of [false,true]){
    const target=path.join(root,reuse?'video-shared':'video-copy');await launch(video,target,'video-mov','-',reuse);
    const output=path.join(target,'out/playback'),probed=await launch(output,path.join(root,reuse?'video-probe-shared':'video-probe-copy'),'probe-webm');
    const facts=JSON.parse(probed.stdout.slice(probed.stdout.indexOf('diagnostics=')+12));assert.equal(facts.streams.find(stream=>stream.codec_type==='video').codec_name,'vp8');assert.equal(facts.streams.find(stream=>stream.codec_type==='audio').codec_name,'opus');
    const pixels=(await run(ffmpeg,['-v','error','-nostdin','-i',output,'-map','0:v:0','-pix_fmt','rgb24','-f','rawvideo','pipe:1'],{windowsHide:true,encoding:'buffer',timeout:30000,maxBuffer:8388608})).stdout;assert.equal(pixels.length,50*160*96*3);videoBuffers.push(pixels);
  }
  let difference=0,maxDifference=0;for(let i=0;i<videoBuffers[0].length;i++){const delta=Math.abs(videoBuffers[0][i]-videoBuffers[1][i]);difference+=delta;maxDifference=Math.max(maxDifference,delta);}assert.ok(difference/videoBuffers[0].length<=1&&maxDifference<=32);
  result.cases.push({name:'shared-video-conversion',frames:50,width:160,height:96,meanDifference:difference/videoBuffers[0].length,maxDifference});
  assert.equal((await fs.readdir(path.join(root,'.imagegate-lpac-state'))).filter(file=>file.endsWith('.lease')).length,0);
  for(const item of runtime.inventory){assert.equal(hash(await fs.readFile(path.join(cache,item.path))),item.sha256);assert.equal(hash(await fs.readFile(path.join(runtime.root,item.path))),item.sha256);}
  const median=values=>values.sort((a,b)=>a-b)[Math.floor(values.length/2)];result.mediansMs={copy:median(result.measurements.filter(x=>!x.reuse).map(x=>x.elapsedMs)),reuse:median(result.measurements.filter(x=>x.reuse).map(x=>x.elapsedMs))};result.passed=true;
}catch(error){result.error={message:error.message,stdout:error.stdout,stderr:error.stderr};process.exitCode=1;}
finally{await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify(result,null,2));
