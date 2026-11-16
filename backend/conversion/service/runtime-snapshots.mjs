import fs from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash} from 'node:crypto';
import {safeRuntimePath} from '../util/runtime-inventory.mjs';
import {ApiError,requireThat} from '../../system/util/errors.mjs';
// Internal trusted adapter; never selects an executable from an HTTP request.
export class NativeRuntimeSnapshots {
  constructor(programs){this.programs=programs;}
  async prepare(kind,source,target){
    const program=this.programs[kind];requireThat(program&&/^[a-f0-9]{64}$/.test(program.sha256),500,'RUNTIME_PREPARER_REQUIRED');
    await safeRuntimePath(program.executable);const stat=await fs.lstat(program.executable);requireThat(stat.isFile()&&stat.nlink===1&&stat.size>0&&stat.size<=16777216,500,'UNSAFE_RUNTIME_PREPARER');
    requireThat(createHash('sha256').update(await fs.readFile(program.executable)).digest('hex')===program.sha256,503,'RUNTIME_PREPARER_CHANGED');
    let execution;try{execution=await promisify(execFile)(program.executable,['--prepare',source,target],{windowsHide:true,shell:false,timeout:30000,maxBuffer:8192,env:{SystemRoot:process.env.SystemRoot||'',WINDIR:process.env.WINDIR||''}});}catch{throw new ApiError(503,'RUNTIME_PREPARATION_FAILED');}
    requireThat(!execution.stderr&&/^[a-f0-9]{64}$/.test(execution.stdout.trim()),500,'INVALID_RUNTIME_PREPARER_OUTPUT');return execution.stdout.trim();
  }
}
