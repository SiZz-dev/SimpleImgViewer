import path from 'node:path';
import {regular,digest} from '../../media/util/files.mjs';
import {ApiError,requireThat,integer} from '../../system/util/errors.mjs';

export class WindowsJobRunner {
  constructor(config){this.config=config;this.invalid=false;}
  async command(executable,args,work=null,runtimeUse=null){
    requireThat(process.platform==='win32'&&process.arch==='x64',503,'PROCESS_ISOLATION_UNAVAILABLE');
    requireThat(!this.invalid,503,'PROCESS_JOB_CHANGED');
    const {jobLauncher,jobLauncherSha256}=this.config;
    requireThat(typeof jobLauncher==='string'&&path.isAbsolute(jobLauncher)&&/^[a-f0-9]{64}$/.test(jobLauncherSha256||''),503,'PROCESS_JOB_CONFIGURATION_REQUIRED');
    requireThat(path.isAbsolute(executable)&&Array.isArray(args)&&args.every(a=>typeof a==='string'&&!a.includes('\0')),500,'INVALID_PROCESS_ARGUMENTS');
    try{await regular(jobLauncher,16777216);requireThat(await digest(jobLauncher)===jobLauncherSha256,503,'PROCESS_JOB_CHANGED');}
    catch{this.invalid=true;throw new ApiError(503,'PROCESS_JOB_CHANGED');}
    requireThat(work===null||typeof work==='string'&&path.isAbsolute(work),500,'INVALID_WORK_DIRECTORY');
    requireThat(runtimeUse===null||typeof runtimeUse==='string'&&path.isAbsolute(runtimeUse)&&path.basename(runtimeUse)==='runtime.use',500,'INVALID_RUNTIME_USE');
    const {jobMemoryMiB,jobCpuSeconds,jobCpuRatePercent}=this.config;
    requireThat(integer(jobMemoryMiB,64,4096)&&integer(jobCpuSeconds,1,600)&&integer(jobCpuRatePercent,1,100),503,'PROCESS_JOB_CONFIGURATION_REQUIRED');
    return {executable:jobLauncher,args:['--memory-mib',String(jobMemoryMiB),'--cpu-seconds',String(jobCpuSeconds),'--cpu-rate',String(jobCpuRatePercent),...(work?['--work',work]:[]),...(runtimeUse?['--runtime-use',runtimeUse]:[]),'--',executable,...args]};
  }
}
