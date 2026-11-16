import fs from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import assert from 'node:assert/strict';
import {execFile,spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash,randomUUID} from 'node:crypto';
const run=promisify(execFile),hash=b=>createHash('sha256').update(b).digest('hex');
const report=path.resolve('build/reports/ffmpeg-lpac');await fs.mkdir(report,{recursive:true});
const result={testOnly:true,productionEnabled:false,distributionReady:false,passed:false,capabilities:0,cases:[]};let listener;
try{
  const runtime=JSON.parse(await fs.readFile('build/ffmpeg-lpac/runtime.json','utf8')),manifest=JSON.parse(await fs.readFile('build/ffmpeg-lpac/launcher.json','utf8'));
  assert.equal(runtime.testOnly,true);assert.equal(manifest.testOnly,true);
  assert.equal(hash(await fs.readFile('config/oss/native-downloads.windows-x64.json')),runtime.policySha256);
  assert.deepEqual((await fs.readdir(runtime.root)).sort(),runtime.inventory.map(item=>item.path).sort());
  for(const item of runtime.inventory){const file=path.join(runtime.root,item.path),stat=await fs.lstat(file);assert.ok(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1);assert.equal(hash(await fs.readFile(file)),item.sha256);}
  for(const item of manifest.inputs)assert.equal(hash(await fs.readFile(item.path)),item.sha256);
  for(const item of Object.values(manifest.programs))assert.equal(hash(await fs.readFile(item.executable)),item.sha256);
  const program=manifest.programs['zero-capability'].executable,registry=manifest.programs['registry-read'].executable,guard=manifest.programs['production-guard'].executable;
  const root=path.resolve('build/acceptance/fml-'+randomUUID().slice(0,8));await fs.mkdir(root,{recursive:true});result.root=root;
  const ffmpeg=path.join(runtime.root,'ffmpeg.exe'),ffprobe=path.join(runtime.root,'ffprobe.exe');
  const call=(exe,args,options={})=>run(exe,args,{windowsHide:true,timeout:30000,maxBuffer:8388608,...options});
  const args=(input,target,mode,extra='-')=>[runtime.root,input,target,mode,extra];
  const launch=(input,target,mode,extra='-')=>call(program,args(input,target,mode,extra));
  const diagnostic=execution=>{assert.match(execution.stdout,/truncated=0/);return execution.stdout.slice(execution.stdout.indexOf('diagnostics=')+12).trim();};
  // Parse stdout in full. Warnings remain private stderr evidence, not JSON text.
  result.probeWarnings=[];
  const probeFacts=execution=>{if(execution.stderr)result.probeWarnings.push(execution.stderr.trim());return JSON.parse(diagnostic(execution));};
  const fixtures=new Map();
  for(const format of ['mp4','mpeg','mp3','wav']){
    const video=['mp4','mpeg'].includes(format),input=path.join(root,'source.'+format),generation=['-v','error','-nostdin'];
    if(video)generation.push('-f','lavfi','-i','testsrc=size=160x96:rate=25');generation.push('-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','2');
    if(format==='mp4')generation.push('-c:v','mpeg4','-c:a','aac','-movflags','+faststart');
    if(format==='mpeg')generation.push('-c:v','mpeg2video','-c:a','mp2','-f','mpeg');
    if(format==='mp3')generation.push('-c:a','libmp3lame');if(format==='wav')generation.push('-c:a','pcm_s16le');generation.push(input);await call(ffmpeg,generation);
    const before=hash(await fs.readFile(input)),demux=format==='mp4'?'mov':format;
    const probe=probeFacts(await launch(input,path.join(root,format+'-probe'),'probe-'+demux));
    assert.ok(Number(probe.format.duration)>1.8&&Number(probe.format.duration)<2.5);
    const target=path.join(root,format+'-convert'),mode=(video?'video-':'audio-')+demux;
    await launch(input,target,mode);const output=path.join(target,'out','playback');
    const stat=await fs.lstat(output);assert.ok(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1&&stat.size>0&&stat.size<=33554432);
    const info=probeFacts(await launch(output,path.join(root,format+'-output-probe'),'probe-'+(video?'webm':'mp3')));
    assert.equal(info.streams.find(stream=>stream.codec_type===(video?'video':'audio')).codec_name,video?'vp8':'mp3');assert.ok(Math.abs(Number(info.format.duration)-Number(probe.format.duration))<1);
    const reference=path.join(root,format+'-reference'),conversion=['-hide_banner','-loglevel','error','-nostdin','-y','-max_alloc','268435456','-protocol_whitelist','file','-format_whitelist',demux];
    if(demux==='mov')conversion.push('-enable_drefs','0','-use_absolute_path','0');
    conversion.push('-threads','2','-i',input,'-map_metadata','-1','-sn','-dn');
    if(video)conversion.push('-map','0:v:0','-map','0:a:0?','-vf',"scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",'-c:v','libvpx','-deadline','realtime','-cpu-used','5','-b:v','1500k','-c:a','libopus','-f','webm');
    else conversion.push('-map','0:a:0','-vn','-c:a','libmp3lame','-b:a','192k','-f','mp3');
    conversion.push('-threads','2','-ac','2','-ar','48000','-t','7200','-fs','33554432',reference);await call(ffmpeg,conversion);
    const decoded=[];
    for(const kind of video?['video','audio']:['audio']){
      const decode=file=>call(ffmpeg,['-v','error','-nostdin','-i',file,...(kind==='video'?['-map','0:v:0','-pix_fmt','rgb24','-f','rawvideo']:['-map','0:a:0','-acodec','pcm_s16le','-f','s16le']),'pipe:1'],{encoding:'buffer'});
      const actual=(await decode(output)).stdout,expected=(await decode(reference)).stdout;assert.ok(actual.length>0);assert.equal(actual.length,expected.length);
      const exact=hash(actual)===hash(expected);let quality;
      if(kind==='video'){
        // Realtime VP8 with two threads can vary even between ordinary runs.
        // This tiny fixture still requires all 50 frames and tightly bounded
        // pixel differences per frame; audio remains byte-exact below.
        const stride=160*96*3;assert.equal(actual.length,50*stride);
        let sum=0,max=0,worstFrameMean=0;
        for(let offset=0;offset<actual.length;offset+=stride){let frameSum=0;for(let i=offset;i<offset+stride;i++){const difference=Math.abs(actual[i]-expected[i]);frameSum+=difference;max=Math.max(max,difference);}sum+=frameSum;worstFrameMean=Math.max(worstFrameMean,frameSum/stride);}
        quality={meanAbsoluteDifference:sum/actual.length,maxDifference:max,worstFrameMean};
        assert.ok(quality.meanAbsoluteDifference<=1&&max<=32&&worstFrameMean<=1,JSON.stringify({format,quality}));
      }else assert.equal(exact,true,format+'/'+kind);
      decoded.push({kind,bytes:actual.length,sha256:hash(actual),referenceSha256:hash(expected),exact,quality});
    }
    assert.equal(hash(await fs.readFile(input)),before);fixtures.set(format,input);result.cases.push({name:format,sourceProbed:true,converted:true,outputProbed:true,duration:Number(info.format.duration),decodedMatchesReference:decoded});
  }
  const input=fixtures.get('wav'),inputHash=hash(await fs.readFile(input)),forbidden=path.join(root,'private.wav');await fs.copyFile(input,forbidden);const outsideHash=hash(await fs.readFile(forbidden));
  for(const mode of ['outside-read','outside-write','runtime-write']){
    const target=path.join(root,mode);let output='';await assert.rejects(launch(input,target,mode,forbidden),error=>{output=(error.stdout||'')+(error.stderr||'');return error.code===22&&/Permission denied|Operation not permitted/i.test(output);});
    assert.equal(hash(await fs.readFile(forbidden)),outsideHash);assert.equal(hash(await fs.readFile(path.join(target,'bin','LICENSE.txt'))),runtime.inventory.find(item=>item.path==='LICENSE.txt').sha256);
    if(mode==='runtime-write'){const control=await fs.open(path.join(target,'bin','LICENSE.txt'),'r+');await control.close();}
    result.cases.push({name:mode,rejected:true,diagnostics:output});
  }
  let connections=0;listener=net.createServer(socket=>{connections++;socket.destroy();});await new Promise((resolve,reject)=>{listener.once('error',reject);listener.listen(0,'127.0.0.1',resolve);});
  const url='http://127.0.0.1:'+listener.address().port+'/synthetic';
  let zeroNetwork='';await assert.rejects(launch(input,path.join(root,'network-zero'),'network',url),error=>{zeroNetwork=(error.stdout||'')+(error.stderr||'');return error.code===22;});assert.equal(connections,0);
  result.cases.push({name:'zero-capability-network',rejected:true,connections,osConnectDenialProven:/Permission denied|10013/.test(zeroNetwork),diagnostics:zeroNetwork});
  result.registryReadControl=probeFacts(await call(registry,args(input,path.join(root,'registry-control'),'probe-wav')));
  for(const mode of ['network-whitelist','network']){
    let output='';await assert.rejects(call(registry,args(input,path.join(root,mode),mode,url)),error=>{output=(error.stdout||'')+(error.stderr||'');return error.code===22;});assert.equal(connections,0);
    if(mode==='network-whitelist')assert.match(output,/not on whitelist/i);
    else assert.match(output,/Permission denied|Operation not permitted|10013/i);
    result.cases.push({name:mode,capabilities:['registryRead'],rejected:true,connections,phase:mode==='network-whitelist'?'protocol-policy':'socket-connect',diagnostics:output});
  }
  for(const mode of ['probe-wav','audio-wav'])await assert.rejects(call(guard,args(input,path.join(root,'guard-'+mode),mode)),error=>error.code===23&&/stage=program/.test(error.stdout));
  result.cases.push({name:'production-guard',ffmpegRejected:true,ffprobeRejected:true});
  const target=path.join(root,'cancel'),child=spawn(program,args(input,target,'hold'),{windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.resume();child.stderr.resume();const closed=new Promise(resolve=>child.once('close',resolve));
  try{
    const deadline=Date.now()+15000;let writing=false;while(Date.now()<deadline){try{if((await fs.stat(path.join(target,'out','playback'))).size>1024){writing=true;break;}}catch(error){if(error.code!=='ENOENT')throw error;}await new Promise(resolve=>setTimeout(resolve,50));}assert.equal(writing,true,'Actual FFmpeg must be writing before cancellation');
    const found=JSON.parse((await call('powershell.exe',['-NoProfile','-Command',`Get-CimInstance Win32_Process -Filter 'ParentProcessId = ${Number(child.pid)}' | Select-Object ProcessId,ExecutablePath | ConvertTo-Json -Compress`])).stdout);
    const worker=(Array.isArray(found)?found:[found]).find(process=>process.ExecutablePath?.toLowerCase()===path.join(target,'bin','ffmpeg.exe').toLowerCase());assert.ok(worker);
    const state=path.join(root,'.imagegate-lpac-state');await assert.rejects(call(program,['--recover',state]),error=>error.code===25&&/busy=1/.test(error.stdout));
    child.kill('SIGKILL');await closed;let alive=true;const end=Date.now()+5000;while(Date.now()<end){try{process.kill(worker.ProcessId,0);}catch(error){if(error.code==='ESRCH'){alive=false;break;}throw error;}await new Promise(resolve=>setTimeout(resolve,50));}assert.equal(alive,false);
    assert.match((await call(program,['--recover',state])).stdout,/recovered=1 busy=0/);assert.equal((await fs.readdir(state)).filter(file=>file.endsWith('.lease')).length,0);
    assert.ok((await fs.stat(path.join(target,'out','playback'))).size>1024);
    result.cases.push({name:'active-conversion-kill-recovery',wasWriting:true,workerExited:true,profileRecovered:true,partialOutputPreserved:true});
  }finally{if(child.exitCode===null&&child.signalCode===null){child.kill('SIGKILL');await closed;}}
  await assert.rejects(launch(input,path.join(root,'timeout'),'timeout'),error=>error.code===23&&/stage=wall-timeout/.test(error.stdout));
  assert.equal((await fs.readdir(path.join(root,'.imagegate-lpac-state'))).filter(file=>file.endsWith('.lease')).length,0);result.cases.push({name:'wall-timeout',rejected:true,remainingLeases:0});
  assert.equal(hash(await fs.readFile(input)),inputHash);
  for(const item of runtime.inventory)assert.equal(hash(await fs.readFile(path.join(runtime.root,item.path))),item.sha256);
  result.runtime={files:runtime.inventory.length,bytes:runtime.inventory.reduce((n,item)=>n+item.bytes,0),moduleId:runtime.moduleId};result.passed=true;
}catch(error){result.error={message:error.message,stdout:error.stdout,stderr:error.stderr};process.exitCode=1;}
finally{if(listener)await new Promise(resolve=>listener.close(resolve));await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify({passed:result.passed,cases:result.cases.map(item=>item.name),error:result.error,report},null,2));
