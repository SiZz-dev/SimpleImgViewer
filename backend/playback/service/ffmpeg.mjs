import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {digest,regular,readJson,atomicJson} from '../../media/util/files.mjs';
import {ApiError,requireThat} from '../../system/util/errors.mjs';
import {WindowsJobRunner} from '../../conversion/util/windows-job.mjs';
import {ffmpegProfile} from '../util/ffmpeg-profile.mjs';
export class PlaybackService {
  constructor(config,store,runtimes=null,transport=null){this.transport=transport;this.config=config;this.store=store;this.runtimes=runtimes;this.active=0;this.running=new Map();this.children=new Set();this.operations=new Set();this.approvals=new Map();this.baseline=[];this.invalid=false;this.closed=false;this.jobRunner=new WindowsJobRunner(config.playback);}
  withRuntime(action){requireThat(!this.closed,503,'PROCESSING_CLOSING');const operation=(async()=>{const runtime=this.runtimes?await this.runtimes.acquire('ffmpeg'):null;try{requireThat(!this.closed,503,'PROCESSING_CLOSING');return await action(runtime);}finally{if(runtime)await runtime.release();}})();this.operations.add(operation);operation.then(()=>this.operations.delete(operation),()=>this.operations.delete(operation));return operation;}
  async approveManaged(runtime){
    if(!this.approvals.has(runtime.identity))this.approvals.set(runtime.identity,(async()=>{
      const version=await this.runProfile({operation:'version'},30000,null,runtime),probe=await this.runProfile({operation:'version',tool:'ffprobe'},30000,null,runtime),license=await this.runProfile({operation:'license'},30000,null,runtime),probeLicense=await this.runProfile({operation:'license',tool:'ffprobe'},30000,null,runtime);
      requireThat(!/--enable-(gpl|nonfree)/.test(version+probe)&&version.includes('--enable-shared')&&probe.includes('--enable-shared')&&/GNU Lesser General Public License/.test(license)&&/GNU Lesser General Public License/.test(probeLicense)&&version.match(/^ffmpeg version (\S+)/)?.[1]===probe.match(/^ffprobe version (\S+)/)?.[1]&&['libvpx','libopus','libmp3lame'].every(codec=>version.includes('--enable-'+codec)),500,'FFMPEG_BUILD_REJECTED');
    })());return this.approvals.get(runtime.identity);
  }
  async init(){
    if(!this.config.playback.enabled)return;
    if(this.runtimes)return this.withRuntime(runtime=>this.approveManaged(runtime));
    const p=this.config.playback;
    for(const [file,expected] of [[p.ffmpeg,p.ffmpegSha256],[p.ffprobe,p.ffprobeSha256]]){
      requireThat(path.isAbsolute(file)&&/^[a-f0-9]{64}$/.test(expected),500,'FFMPEG_CONFIGURATION_REQUIRED');await regular(file,268435456);requireThat(await digest(file)===expected,500,'FFMPEG_HASH_MISMATCH');
    }
    requireThat(path.dirname(p.ffmpeg)===path.dirname(p.ffprobe),500,'FFMPEG_CONFIGURATION_REQUIRED');
    for(const name of (await fs.readdir(path.dirname(p.ffmpeg))).filter(n=>/\.(exe|dll)$/i.test(n))){const file=path.join(path.dirname(p.ffmpeg),name);await regular(file,268435456);this.baseline.push([file,await digest(file)]);}
    requireThat(this.baseline.length<=256,500,'FFMPEG_BUILD_REJECTED');
    const version=await this.run(p.ffmpeg,['-version']);const license=await this.run(p.ffmpeg,['-L']);const probeVersion=await this.run(p.ffprobe,['-version']);const probeLicense=await this.run(p.ffprobe,['-L']);
    requireThat(!/--enable-(gpl|nonfree)/.test(version+probeVersion)&&version.includes('--enable-shared')&&probeVersion.includes('--enable-shared')&&/GNU Lesser General Public License/.test(license)&&/GNU Lesser General Public License/.test(probeLicense)
      &&version.match(/^ffmpeg version (\S+)/)?.[1]===probeVersion.match(/^ffprobe version (\S+)/)?.[1]&&['libvpx','libopus','libmp3lame'].every(codec=>version.includes('--enable-'+codec)),500,'FFMPEG_BUILD_REJECTED');
    this.identity=createHash('sha256').update(JSON.stringify(this.baseline)).digest('hex');
  }
  async verify(runtime=null){
    if(this.runtimes){requireThat(runtime?.kind==='ffmpeg',503,'RUNTIME_REFERENCE_REQUIRED');await runtime.verify();return;}
    requireThat(!this.invalid,503,'FFMPEG_CHANGED');
    try{
      const files=(await fs.readdir(path.dirname(this.config.playback.ffmpeg))).filter(n=>/\.(exe|dll)$/i.test(n)).sort();
      requireThat(JSON.stringify(files)===JSON.stringify(this.baseline.map(([file])=>path.basename(file)).sort()),503,'FFMPEG_CHANGED');
      for(const [file,hash] of this.baseline){await regular(file,268435456);requireThat(await digest(file)===hash,503,'FFMPEG_CHANGED');}
    }catch{this.invalid=true;throw new ApiError(503,'FFMPEG_CHANGED');}
  }
  async runProfile(profile,timeout=30000,work=null,runtime=null){
    requireThat(!this.closed,503,'PROCESSING_CLOSING');const command=ffmpegProfile(profile);
    if(!this.transport)return this.run((runtime||this.config.playback)[profile.tool||'ffmpeg'],command.args,timeout,work,runtime);
    requireThat(runtime?.kind==='ffmpeg',503,'RUNTIME_REFERENCE_REQUIRED');
    const owned=!work;if(owned)work=await this.store.playbackWork.create();
    try{requireThat(!this.closed,503,'PROCESSING_CLOSING');return await this.transport.run({profile,work,timeout},{runtime});}finally{if(owned)await this.store.playbackWork.release(work);}
  }
  async run(executable,args,timeout=30000,work=null,runtime=null){
    requireThat(!this.transport,503,'LPAC_PROFILE_REQUIRED');
    requireThat(!this.closed,503,'PROCESSING_CLOSING');
    requireThat(!this.runtimes||runtime?.kind==='ffmpeg'&&[runtime.ffmpeg,runtime.ffprobe].includes(executable),503,'RUNTIME_REFERENCE_REQUIRED');
    const command=await this.jobRunner.command(executable,args,work,runtime?.useFile||null);requireThat(!this.closed,503,'PROCESSING_CLOSING');
    return new Promise((resolve,reject)=>{
      const child=spawn(command.executable,command.args,{windowsHide:true,shell:false,stdio:['pipe','pipe','pipe'],env:{SystemRoot:process.env.SystemRoot||'',WINDIR:process.env.WINDIR||'',TEMP:process.env.TEMP||'',TMP:process.env.TMP||''}});
      child.stdin.on('error',()=>{}); // Kept open as a liveness pipe; never passed to the media process.
      this.children.add(child);let output='',errorBytes=0,oversize=false,timedOut=false,failed=false;
      child.stdout.on('data',b=>{if(Buffer.byteLength(output)+b.length>1048576){oversize=true;child.kill('SIGKILL');}else output+=b.toString();});
      child.stderr.on('data',b=>{errorBytes+=b.length;if(errorBytes>1048576){oversize=true;child.kill('SIGKILL');}});
      const timer=setTimeout(()=>{timedOut=true;child.kill('SIGKILL');},timeout);timer.unref();
      child.once('error',()=>{failed=true;});
      child.once('close',code=>{clearTimeout(timer);this.children.delete(child);if(runtime&&code===125)return reject(new ApiError(503,'RUNTIME_EXECUTION_REJECTED'));code===0&&!oversize&&!timedOut&&!failed?resolve(output):reject(new ApiError(failed?503:timedOut?504:422,failed?'FFMPEG_UNAVAILABLE':timedOut?'PLAYBACK_TIMEOUT':'INVALID_MEDIA'));});
    });
  }
  async prepare(record,actor=null){return this.withRuntime(runtime=>this.prepareWithRuntime(record,runtime,actor));}
  async prepareWithRuntime(record,runtime,actor){
    requireThat(this.config.playback.enabled,403,'PLAYBACK_DISABLED');
    const key=record.mediaVersion+':'+(runtime?.identity||this.identity);
    if(this.running.has(key))return this.running.get(key);
    requireThat(this.active<2,429,'PLAYBACK_QUEUE_FULL');this.active++;
    const operation=this.convert(record,runtime,actor).finally(()=>{this.active--;this.running.delete(key);});this.running.set(key,operation);return operation;
  }
  async convert(record,runtime,actor){
    await this.store.verify(record);await this.verify(runtime);if(runtime)await this.approveManaged(runtime);
    const identity=runtime?.identity||this.identity;
    const sourceId=createHash('sha256').update(record.sha256+record.mediaVersion+identity+':node-playback-v2').digest('hex');
    const dir=path.join(this.store.root,'derived','playback-'+sourceId);
    try{const info=await readJson(path.join(dir,'facts.json'));requireThat(info.sourceId===sourceId&&info.mediaVersion===record.mediaVersion&&info.runtimeIdentity===identity&&info.originalSha256===record.sha256&&info.state==='ready'&&info.durationSeconds>0&&info.durationSeconds<=7200,409,'PLAYBACK_CHANGED');await regular(path.join(dir,'playback'),268435456);await this.store.cache.touch(dir);return info;}catch(e){if(e.code!=='ENOENT'&&e.status!==404)throw e;}
    const p=runtime||this.config.playback;const input=this.store.file(record);const formats=record.originalMimeType==='video/mp4'?'mov':record.originalMimeType==='video/mpeg'?'mpeg':record.originalMimeType==='audio/wav'?'wav':'mp3';
    const probe=JSON.parse(await this.runProfile({operation:'probe-input',tool:'ffprobe',format:formats,input},30000,null,runtime));
    const duration=Number(probe.format?.duration);requireThat(duration>0&&duration<=7200&&probe.streams?.length<=32,422,'INVALID_MEDIA');
    const video=record.mediaType==='video';const stream=probe.streams.find(s=>s.codec_type==='video'&&!s.disposition?.attached_pic);
    if(video)requireThat(stream&&stream.width>0&&stream.height>0&&stream.width*stream.height<=8294400,422,'INVALID_MEDIA');
    if(video){const [n,d]=String(stream.avg_frame_rate).split('/').map(Number);requireThat(n>0&&d>0&&n/d<=60,422,'INVALID_MEDIA');}
    for(const audio of probe.streams.filter(s=>s.codec_type==='audio'))requireThat(Number(audio.channels)<=8&&Number(audio.sample_rate)<=192000,422,'INVALID_MEDIA');
    const work=await this.store.playbackWork.create();
    try{
      const profile={operation:video?'video':'audio',format:formats,input,output:path.join(work,'playback')};
      await this.runProfile(profile,120000,work,runtime);await this.verify(runtime);await this.store.verify(record);
      const stat=await regular(path.join(work,'playback'),268435456);
      const outputProbe=JSON.parse(await this.runProfile({operation:'probe-output',tool:'ffprobe',format:video?'matroska,webm':'mp3',input:path.join(work,'playback')},30000,work,runtime));
      const actualDuration=Number(outputProbe.format?.duration),actualVideo=outputProbe.streams.find(s=>s.codec_type==='video'),actualAudio=outputProbe.streams.find(s=>s.codec_type==='audio');
      requireThat(actualDuration>0&&Math.abs(actualDuration-duration)<1&&(video?actualVideo?.codec_name==='vp8'&&actualVideo.width<=1920&&actualVideo.height<=1080:actualAudio?.codec_name==='mp3'),422,'INVALID_PLAYBACK_OUTPUT');
      const info={state:'ready',mediaVersion:record.mediaVersion,sourceId,runtimeIdentity:identity,originalSha256:record.sha256,durationSeconds:actualDuration,sizeBytes:stat.size,mimeType:video?'video/webm':'audio/mpeg',videoCodec:video?'vp8':null,audioCodec:actualAudio?.codec_name||null,width:video?actualVideo.width:null,height:video?actualVideo.height:null,url:'/api/v1/media/'+record.mediaId+'/playback/sources/'+sourceId+'/content'};
      await atomicJson(path.join(work,'facts.json'),info);await this.store.playbackWork.publish(work,dir,this.store.cache,async()=>{requireThat(this.config.playback.enabled,403,'PLAYBACK_DISABLED');if(actor){const current=await this.store.require(record.mediaId,actor);requireThat(current.mediaVersion===record.mediaVersion,409,'MEDIA_CHANGED');}await this.store.verify(record);await this.verify(runtime);});return info;
    }finally{await this.store.playbackWork.release(work);}
  }
  file(info){return path.join(this.store.root,'derived','playback-'+info.sourceId,'playback');}
  async source(record,sourceId){requireThat(/^[a-f0-9]{64}$/.test(sourceId||''),404,'SOURCE_NOT_FOUND');await this.store.verify(record);const directory=path.join(this.store.root,'derived','playback-'+sourceId),info=await readJson(path.join(directory,'facts.json'));requireThat(info.sourceId===sourceId&&info.mediaVersion===record.mediaVersion&&info.originalSha256===record.sha256&&info.state==='ready'&&info.mimeType===(record.mediaType==='video'?'video/webm':'audio/mpeg')&&/^[a-f0-9]{64}$/.test(info.runtimeIdentity||'')&&createHash('sha256').update(record.sha256+record.mediaVersion+info.runtimeIdentity+':node-playback-v2').digest('hex')===sourceId,404,'SOURCE_NOT_FOUND');const stat=await regular(this.file(info),268435456);requireThat(stat.size===info.sizeBytes,409,'PLAYBACK_CHANGED');await this.store.cache.touch(directory);return info;}
  async close(){this.closed=true;await Promise.all([this.transport?.close(),...([...this.children].map(child=>new Promise(resolve=>{child.once('close',resolve);child.kill('SIGKILL');})))]);await Promise.allSettled([...this.running.values(),...this.operations]);}
}
