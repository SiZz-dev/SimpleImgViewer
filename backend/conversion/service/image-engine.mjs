import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import fs from 'node:fs/promises';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {WindowsJobRunner} from '../util/windows-job.mjs';
import {ApiError,requireThat} from '../../system/util/errors.mjs';
export class ImageEngine {
  constructor(config,runtimes=null,transport=null){this.config=config;this.runtimes=runtimes;this.transport=transport;this.active=0;this.children=new Set();this.operations=new Set();this.closed=false;
    this.runner=new WindowsJobRunner(config.processing);
    this.identity=createHash('sha256').update(readFileSync(new URL('../util/image-worker.mjs',import.meta.url))).update(readFileSync(new URL('../util/work-lease.mjs',import.meta.url))).update(readFileSync(new URL('../util/bmp.mjs',import.meta.url))).update(readFileSync('pnpm-lock.yaml')).digest('hex');
  }
  withRuntime(action){requireThat(!this.closed,503,'PROCESSING_CLOSING');const operation=(async()=>{const runtime=this.runtimes?await this.runtimes.acquire('node'):null;try{requireThat(!this.closed,503,'PROCESSING_CLOSING');return await action(runtime);}finally{if(runtime)await runtime.release();}})();this.operations.add(operation);operation.then(()=>this.operations.delete(operation),()=>this.operations.delete(operation));return operation;}
  identityFor(runtime){return runtime?.identity||this.identity;}
  async run(job,options={}){
    if(!Object.hasOwn(options,'runtime'))return this.withRuntime(runtime=>this.run(job,{runtime}));
    const runtime=options.runtime;requireThat(!this.runtimes||runtime?.kind==='node',503,'RUNTIME_REFERENCE_REQUIRED');
    requireThat(job&&typeof job.output==='string'&&path.isAbsolute(job.output),500,'INVALID_WORK_DIRECTORY');
    requireThat(!this.closed,503,'PROCESSING_CLOSING');requireThat(this.active<this.config.processing.concurrency,429,'PREVIEW_QUEUE_FULL');this.active++;
    if(this.transport){try{return await this.transport.run(job,{runtime});}finally{this.active--;}}
    const work=path.dirname(job.output),manifest=path.join(work,'job.json');let owned=false;
    try{
      const command=await this.runner.command(runtime?.executable||process.execPath,['--max-old-space-size=128',runtime?.worker||fileURLToPath(new URL('../util/image-worker.mjs',import.meta.url)),manifest],work,runtime?.useFile||null);
      requireThat(!this.closed,503,'PROCESSING_CLOSING');
      const content=JSON.stringify({...job,maxPixels:Math.min(job.maxPixels||this.config.processing.maxPixels,this.config.processing.maxPixels)});
      requireThat(Buffer.byteLength(content)<=1048576,413,'JOB_TOO_LARGE');
      await fs.writeFile(manifest,content,{flag:'wx',mode:0o600});owned=true;
      requireThat(!this.closed,503,'PROCESSING_CLOSING');
      return await new Promise((resolve,reject)=>{
      const child=spawn(command.executable,command.args,{
        windowsHide:true,shell:false,stdio:['pipe','pipe','pipe'],
        env:{SystemRoot:process.env.SystemRoot||'',WINDIR:process.env.WINDIR||'',TEMP:process.env.TEMP||'',TMP:process.env.TMP||''}});
      child.stdin.on('error',()=>{});
      this.children.add(child);let output='',errorBytes=0;let timedOut=false,failed=false,oversize=false;
      const timer=setTimeout(()=>{timedOut=true;child.kill('SIGKILL');},this.config.processing.timeoutMs);timer.unref();
      child.stdout.on('data',bytes=>{if(Buffer.byteLength(output)+bytes.length>8192){oversize=true;child.kill('SIGKILL');}else output+=bytes.toString();});
      child.stderr.on('data',bytes=>{errorBytes+=bytes.length;if(errorBytes>8192){oversize=true;child.kill('SIGKILL');}});
      child.once('error',()=>{failed=true;});
      child.once('close',async code=>{clearTimeout(timer);this.children.delete(child);
        if(timedOut)return reject(new ApiError(504,'CONVERSION_TIMEOUT'));
        if(runtime&&code===125)return reject(new ApiError(503,'RUNTIME_EXECUTION_REJECTED'));
        let result;try{result=JSON.parse(output);}catch{}
        if(failed||oversize||code!==0||!result?.ok)return reject(new ApiError(422,'CONVERSION_UNSUPPORTED_PROFILE'));
        try{requireThat((await fs.stat(job.output)).size<=33554432,413,'OUTPUT_TOO_LARGE');resolve(result);}catch(e){reject(e);}
      });
    });}finally{try{if(owned)await fs.unlink(manifest).catch(error=>{if(error.code!=='ENOENT')throw error;});}finally{this.active--;}}
  }
  async close(){this.closed=true;await Promise.all([this.transport?.close(),...([...this.children].map(child=>new Promise(resolve=>{child.once('close',resolve);child.kill('SIGKILL');})))]);await Promise.allSettled([...this.operations]);}
}
