import fs from 'node:fs/promises';
import path from 'node:path';
import {PlaybackService} from '../../playback/service/ffmpeg.mjs';
import {settings} from '../../system/config/settings.mjs';
const config=await settings({development:true}),playback=new PlaybackService(config,{});
let executable,args;
if(process.argv[3]==='ffmpeg'){
  await playback.init();executable=config.playback.ffmpeg;
  args=['-hide_banner','-loglevel','error','-nostdin','-y','-re','-f','lavfi','-i','color=size=160x96:rate=10','-t','120',
    ...(process.argv[4]?['-c:v','mpeg2video','-f','mpegts',path.join(process.argv[4],'playback')]:['-f','null','-'])];
}else{
  const probe=JSON.parse(await fs.readFile('build/process-job/probe.json','utf8'));executable=probe.executable;args=[process.argv[2]];
}
const operation=playback.run(executable,args,120000,process.argv[4]||null);
operation.catch(()=>{});
const timer=setInterval(()=>{
  const child=[...playback.children][0];if(child?.pid){clearInterval(timer);process.send({launcherPid:child.pid});}
},10);
process.on('message',async message=>{
  if(message==='kill-launcher'){[...playback.children][0]?.kill('SIGKILL');}
  if(message==='close'){await playback.close();process.exit(0);}
});
