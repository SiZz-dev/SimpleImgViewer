import {requireThat,integer} from '../../system/util/errors.mjs';

export function encodeImageLpacRequest(binding,runtime,work,limits){
  requireThat(runtime?.kind==='node'&&/^[a-f0-9]{64}$/.test(runtime.identity||''),503,'RUNTIME_REFERENCE_REQUIRED');
  requireThat(integer(limits.timeoutMs,100,120000)&&integer(limits.jobMemoryMiB,64,4096)&&integer(limits.jobCpuSeconds,1,600)&&integer(limits.jobCpuRatePercent,1,100),503,'INVALID_LPAC_LIMITS');
  const parts=[Buffer.from('SIVLPAC1')];
  const number=value=>{const bytes=Buffer.alloc(4);bytes.writeUInt32LE(value);parts.push(bytes);};
  const text=value=>{requireThat(typeof value==='string'&&value.length>0&&!value.includes('\0'),500,'INVALID_LPAC_REQUEST');const bytes=Buffer.from(value);number(bytes.length);parts.push(bytes);};
  number(binding.document.operation==='preview'?1:2);for(const value of [limits.timeoutMs,limits.jobMemoryMiB,limits.jobCpuSeconds,limits.jobCpuRatePercent])number(value);
  for(const value of [runtime.path,runtime.identity,runtime.useFile,work,binding.root,binding.inputs[0].source,JSON.stringify(binding.document)])text(value);
  number(binding.inputs.length-1);for(const input of binding.inputs.slice(1))text(input.source);
  const bytes=Buffer.concat(parts);requireThat(bytes.length<=1048576,413,'JOB_TOO_LARGE');return bytes;
}
