import path from 'node:path';
import {LpacImageTransport} from './lpac-image-transport.mjs';
import {LpacMediaTransport} from '../../playback/service/lpac-media-transport.mjs';
import {requireThat} from '../../system/util/errors.mjs';
// Server-owned fixed selection only; never accepts a transport from an HTTP request.
export async function createLpacTransports(config,runtimes){
 if(config.isolation.mode!=='lpac')return {image:null,media:null};
 requireThat(runtimes,503,'RUNTIME_REFERENCE_REQUIRED');
 const p=config.isolation,recovery={enabled:p.recoveryEnabled,minAgeMs:p.recoveryMinAgeMs,intervalMs:p.recoveryIntervalMs,scanLimit:p.scanLimit};let image,media;
 try{
  const node=await runtimes.acquire('node');try{requireThat(!config.features.image||!config.features.annotations||!config.features.exportEdited||node.noteFont,503,'LPAC_NOTE_FONT_REQUIRED');}finally{await node.release();}
  image=new LpacImageTransport({root:path.join(p.root,'image'),launcher:p.imageLauncher,sha256:p.imageLauncherSha256,processing:config.processing,maxEntries:p.maxEntries,recovery});
  const imageCaps=await image.control(['--capabilities']);let info;try{info=JSON.parse(imageCaps.stdout);}catch{}requireThat(info?.protocol===1&&info.profile==='bound-image-v1'&&info.recovery===true&&info.noteFonts===true,503,'LPAC_PROFILE_REJECTED');await image.init();
  if(config.playback.enabled){
   media=new LpacMediaTransport({root:path.join(p.root,'media'),launcher:p.mediaLauncher,sha256:p.mediaLauncherSha256,processing:config.playback,maxEntries:p.maxEntries,recovery});
   const mediaCaps=await media.control(['--capabilities']);let info;try{info=JSON.parse(mediaCaps.stdout);}catch{}requireThat(info?.protocol===1&&info.profile==='bound-media-v1'&&info.recovery===true,503,'LPAC_PROFILE_REJECTED');await media.init();
  }
  return {image,media};
 }catch(error){await Promise.all([image?.close(),media?.close()]);throw error;}
}
