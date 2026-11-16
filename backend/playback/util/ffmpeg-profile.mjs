import path from 'node:path';
import {requireThat} from '../../system/util/errors.mjs';

// Shared by the Job transport and the internal LPAC transport. Callers
// select a profile; file names and codec/protocol switches are never raw argv.
export function ffmpegProfile({operation,format,input,output,tool='ffmpeg'}){
  requireThat(['ffmpeg','ffprobe'].includes(tool),500,'INVALID_MEDIA_PROFILE');
  if(['version','license'].includes(operation)){
    requireThat(format===undefined&&input===undefined&&output===undefined,500,'INVALID_MEDIA_PROFILE');
    return {program:tool+'.exe',args:[operation==='version'?'-version':'-L']};
  }
  requireThat(typeof input==='string'&&path.isAbsolute(input)&&!input.includes('\0'),500,'INVALID_MEDIA_PROFILE');
  const references=format==='mov'?['-enable_drefs','0','-use_absolute_path','0']:[];
  if(['probe-input','probe-output'].includes(operation)){
    requireThat(tool==='ffprobe'&&output===undefined&&(operation==='probe-input'?['mov','mpeg','wav','mp3']:['matroska,webm','mp3']).includes(format),500,'INVALID_MEDIA_PROFILE');
    return {program:'ffprobe.exe',args:['-v','error','-max_alloc','268435456','-protocol_whitelist','file','-format_whitelist',format,...references,'-show_streams','-show_format','-of','json',input]};
  }
  requireThat(tool==='ffmpeg'&&['video','audio'].includes(operation)&&typeof output==='string'&&path.isAbsolute(output)&&!output.includes('\0')&&path.basename(output)==='playback'&&path.normalize(input)!==path.normalize(output)
    &&(operation==='video'?['mov','mpeg']:['wav','mp3']).includes(format),500,'INVALID_MEDIA_PROFILE');
  const args=['-hide_banner','-loglevel','error','-nostdin','-max_alloc','268435456','-protocol_whitelist','file','-format_whitelist',format,...references,'-threads','2','-i',input,'-map_metadata','-1','-sn','-dn'];
  if(operation==='video')args.push('-map','0:v:0','-map','0:a:0?','-vf',"scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",'-c:v','libvpx','-deadline','realtime','-cpu-used','5','-b:v','1500k','-c:a','libopus','-f','webm');
  else args.push('-map','0:a:0','-vn','-c:a','libmp3lame','-b:a','192k','-f','mp3');
  args.push('-threads','2','-ac','2','-ar','48000','-t','7200','-fs','268435456',output);
  return {program:'ffmpeg.exe',args};
}
