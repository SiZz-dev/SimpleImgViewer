import {requireThat,integer} from '../../system/util/errors.mjs';
export function encodeMediaLpacRequest(binding,runtime,work,limits){
 requireThat(runtime?.kind==='ffmpeg'&&/^[a-f0-9]{64}$/.test(runtime.identity||''),503,'RUNTIME_REFERENCE_REQUIRED');
 requireThat(integer(limits.timeoutMs,100,120000)&&integer(limits.jobMemoryMiB,64,4096)&&integer(limits.jobCpuSeconds,1,600)&&integer(limits.jobCpuRatePercent,1,100),503,'INVALID_LPAC_LIMITS');
 const operations=['version','license','probe-input','probe-output','video','audio'],formats=[null,'mov','mpeg','wav','mp3','matroska,webm'],op=operations.indexOf(binding.operation),format=formats.indexOf(binding.format);requireThat(op>=0&&format>=0,500,'INVALID_MEDIA_PROFILE');
 const parts=[Buffer.from('SIVLPAC1')],number=value=>{const bytes=Buffer.alloc(4);bytes.writeUInt32LE(value);parts.push(bytes);},text=value=>{requireThat(typeof value==='string'&&value.length>0&&!value.includes('\0'),500,'INVALID_LPAC_REQUEST');const bytes=Buffer.from(value);number(bytes.length);parts.push(bytes);};
 for(const value of [op,binding.program==='ffprobe.exe'?1:0,format,limits.timeoutMs,limits.jobMemoryMiB,limits.jobCpuSeconds,limits.jobCpuRatePercent])number(value);
 for(const value of [runtime.path,runtime.identity,runtime.useFile,work,binding.root,...binding.inputs.map(input=>input.source)])text(value);
 const bytes=Buffer.concat(parts);requireThat(bytes.length<=1048576,413,'JOB_TOO_LARGE');return bytes;
}
