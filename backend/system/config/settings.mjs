import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {parse} from 'yaml';
import {requireThat,integer} from '../util/errors.mjs';
export async function settings({development=false,overrides={}}={}){
  let supplied={};
  try{supplied=parse(await fs.readFile(process.env.SIMPLEIMGVIEWER_CONFIG||'config/simpleimgviewer.yml','utf8'),{uniqueKeys:true,maxAliasCount:0})||{};}catch(e){if(e.code!=='ENOENT')throw e;}
  const defaults={server:{host:'127.0.0.1',port:8080,allowedHosts:['localhost','127.0.0.1','[::1]','192.168.0.86']},storage:{root:'.runtime/node/media',maxOriginalBytes:10737418240,originalScanLimit:10000},upload:{maxBytes:104857600,minFreeBytes:536870912},
    processing:{concurrency:2,timeoutMs:30000,maxPixels:16000000,previewEdge:1600,jobLauncher:'',jobLauncherSha256:'',jobMemoryMiB:512,jobCpuSeconds:30,jobCpuRatePercent:100},
    runtimeVersions:{enabled:false,root:'.runtime/node/runtimes',maxBytes:1073741824,maxVersions:10},
    isolation:{mode:'windows-job',root:'.runtime/node/lpac',imageLauncher:'',imageLauncherSha256:'',mediaLauncher:'',mediaLauncherSha256:'',maxEntries:40,recoveryEnabled:true,recoveryMinAgeMs:60000,recoveryIntervalMs:60000,scanLimit:200},
    sessions:{cleanupEnabled:true,ttlMs:1800000,maxEntries:1000,scanLimit:10000},
    temporary:{recoveryEnabled:true,minAgeMs:3600000,scanLimit:10000},
    cache:{cleanupOnStartup:true,maxBytes:2147483648,maxAgeMs:604800000,scanLimit:10000},
    features:{image:true,zoom:true,rotation:true,brightness:true,magnifier:true,annotations:true,stamps:true,stampUpload:true,cropAndSave:true,exportEdited:true,video:true,audio:true,videoSeek:true,audioSeek:true,videoPlaybackSpeed:true,audioPlaybackSpeed:true},
    remote:{enabled:development,allowedPrefixes:['http://192.168.0.86:8080/viewer/resources/TestFiles/'],timeoutMs:30000},
    playback:{enabled:false,ffmpeg:'',ffprobe:'',ffmpegSha256:'',ffprobeSha256:'',jobLauncher:'',jobLauncherSha256:'',jobMemoryMiB:1024,jobCpuSeconds:120,jobCpuRatePercent:100},development};
  const result={...defaults};
  for(const source of [supplied,overrides])for(const [key,value] of Object.entries(source)){
    if(key==='runtimeVersions')requireThat(value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).every(name=>['enabled','root','maxBytes','maxVersions'].includes(name)),400,'INVALID_CONFIGURATION');
    if(key==='isolation')requireThat(value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).every(name=>Object.hasOwn(defaults.isolation,name)),400,'INVALID_CONFIGURATION');
    requireThat(key in defaults,400,'INVALID_CONFIGURATION');result[key]=typeof defaults[key]==='object'?{...result[key],...value}:value;
  }
  result.development=development; // YAML or a client header cannot enable automatic login.
  requireThat(integer(result.processing.jobMemoryMiB,64,4096)&&integer(result.processing.jobCpuSeconds,1,600)
    &&integer(result.processing.jobCpuRatePercent,1,100),400,'INVALID_CONFIGURATION');
  requireThat(integer(result.playback.jobMemoryMiB,64,4096)&&integer(result.playback.jobCpuSeconds,1,600)
    &&integer(result.playback.jobCpuRatePercent,1,100),400,'INVALID_CONFIGURATION');
  requireThat(integer(result.storage.maxOriginalBytes,1024,2**40)&&integer(result.storage.originalScanLimit,1,1000000),400,'INVALID_CONFIGURATION');
  requireThat(typeof result.cache.cleanupOnStartup==='boolean'&&integer(result.cache.maxBytes,1048576,2**40)
    &&integer(result.cache.maxAgeMs,60000,31536000000)&&integer(result.cache.scanLimit,100,100000),400,'INVALID_CONFIGURATION');
  requireThat(typeof result.temporary.recoveryEnabled==='boolean'&&integer(result.temporary.minAgeMs,60000,604800000)
    &&integer(result.temporary.scanLimit,100,100000),400,'INVALID_CONFIGURATION');
  requireThat(typeof result.sessions.cleanupEnabled==='boolean'&&integer(result.sessions.ttlMs,60000,86400000)
    &&integer(result.sessions.maxEntries,1,10000)&&integer(result.sessions.scanLimit,result.sessions.maxEntries,100000),400,'INVALID_CONFIGURATION');
  requireThat(Object.values(result.features).every(value=>typeof value==='boolean')&&typeof result.playback.enabled==='boolean'&&typeof result.remote.enabled==='boolean',400,'INVALID_CONFIGURATION');
  if(development&&!supplied.playback&&!overrides.playback){
    try{const native=parse(await fs.readFile('build/native-modules/imagegate.native.yml','utf8'));const p=native.imagegate.playback;
      result.playback={...result.playback,enabled:p.enabled,ffmpeg:p.ffmpeg,ffprobe:p.ffprobe,ffmpegSha256:p['ffmpeg-sha256'],ffprobeSha256:p['ffprobe-sha256']};
    }catch(e){if(e.code!=='ENOENT')throw e;}
  }
  if(development&&result.isolation.mode==='windows-job'&&(!result.playback.jobLauncher||!result.processing.jobLauncher)){
    try{
      const manifest=JSON.parse(await fs.readFile('build/process-job/launcher.json','utf8'));
      const sourceSha256=createHash('sha256').update(await fs.readFile('native/process-job/main.cpp')).digest('hex');
      requireThat(manifest.schemaVersion===1&&manifest.platform==='win32-x64'&&manifest.sourceSha256===sourceSha256,500,'PROCESS_JOB_BUILD_STALE');
      for(const feature of ['playback','processing'])if(!result[feature].jobLauncher){result[feature].jobLauncher=manifest.executable;result[feature].jobLauncherSha256=manifest.sha256;}
    }catch(e){if(e.code!=='ENOENT')throw e;}
  }
  requireThat(integer(result.server.port,0,65535)&&integer(result.upload.maxBytes,1024,104857600)&&integer(result.upload.minFreeBytes,0,2**40)
    &&integer(result.processing.concurrency,1,4)&&integer(result.processing.timeoutMs,1000,120000)&&integer(result.processing.maxPixels,1,40000000)
    &&integer(result.processing.previewEdge,64,4096)&&integer(result.remote.timeoutMs,1000,120000),400,'INVALID_CONFIGURATION');
  requireThat(Array.isArray(result.remote.allowedPrefixes)&&result.remote.allowedPrefixes.length<=16,400,'INVALID_CONFIGURATION');
  requireThat(Array.isArray(result.server.allowedHosts)&&result.server.allowedHosts.length>0&&result.server.allowedHosts.length<=32&&result.server.allowedHosts.every(host=>typeof host==='string'&&/^[a-z0-9.:[\]-]{1,253}$/i.test(host)),400,'INVALID_CONFIGURATION');
  requireThat(typeof result.runtimeVersions.enabled==='boolean'&&typeof result.runtimeVersions.root==='string'&&result.runtimeVersions.root.length>0&&integer(result.runtimeVersions.maxBytes,1048576,2**40)&&integer(result.runtimeVersions.maxVersions,1,1000),400,'INVALID_CONFIGURATION');
  result.runtimeVersions.root=path.resolve(result.runtimeVersions.root);
  if(result.runtimeVersions.enabled)for(const forbidden of [path.resolve(result.storage.root),path.resolve('dist')])requireThat(result.runtimeVersions.root!==forbidden&&!result.runtimeVersions.root.startsWith(forbidden+path.sep)&&!forbidden.startsWith(result.runtimeVersions.root+path.sep),400,'INVALID_RUNTIME_ROOT');
  result.storage.root=path.resolve(result.storage.root);
  const p=result.isolation;requireThat(['windows-job','lpac'].includes(p.mode)&&typeof p.root==='string'&&p.root.length>0&&integer(p.maxEntries,3,200)&&typeof p.recoveryEnabled==='boolean'&&integer(p.recoveryMinAgeMs,0,86400000)&&(p.recoveryIntervalMs===0||integer(p.recoveryIntervalMs,1000,3600000))&&integer(p.scanLimit,p.maxEntries+2,10000),400,'INVALID_CONFIGURATION');p.root=path.resolve(p.root);
  if(p.mode==='lpac'){
    requireThat(result.runtimeVersions.enabled,400,'LPAC_REQUIRES_MANAGED_RUNTIMES');
    const inside=(a,b)=>{const relative=path.relative(a,b);return relative===''||relative!=='..'&&!relative.startsWith('..'+path.sep)&&!path.isAbsolute(relative);};
    for(const forbidden of [result.storage.root,result.runtimeVersions.root,path.resolve('dist')])requireThat(!inside(forbidden,p.root)&&!inside(p.root,forbidden),400,'INVALID_LPAC_ROOT');
    for(const kind of result.playback.enabled?['image','media']:['image']){const file=p[kind+'Launcher'];requireThat(typeof file==='string'&&path.isAbsolute(file)&&/^[a-f0-9]{64}$/.test(p[kind+'LauncherSha256']||''),400,'LPAC_LAUNCHER_CONFIGURATION_REQUIRED');for(const forbidden of [p.root,result.storage.root,result.runtimeVersions.root,path.resolve('dist')])requireThat(!inside(forbidden,path.resolve(file)),400,'INVALID_LPAC_LAUNCHER_PATH');}
  }
  return result;
}
