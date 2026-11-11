import {spawnSync} from 'node:child_process';
import {statSync,realpathSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const HELP='node scripts/test-proxy-config.mjs --proxy nginx|apache --executable ABS --config ABS --root ABS\nNative syntax check only; no server start, reload, install or runtime acceptance.';
export function parseArgs(args){
  if(args.length===1&&args[0]==='--help')return {help:true};
  const values={};const allowed=new Set(['--proxy','--executable','--config','--root']);
  for(let i=0;i<args.length;i++){
    const key=args[i];if(!allowed.has(key)||key in values||++i>=args.length)throw Error('INVALID_OPTIONS');values[key]=args[i];
  }
  if(!['nginx','apache'].includes(values['--proxy']))throw Error('INVALID_OPTIONS');
  for(const key of ['--executable','--config','--root']){if(!values[key]||!path.isAbsolute(values[key]))throw Error('INVALID_OPTIONS');values[key]=path.normalize(values[key]);}
  return {proxy:values['--proxy'],executable:values['--executable'],config:values['--config'],root:values['--root']};
}
export function checkProxy(options,run=spawnSync){
  const report={schemaVersion:1,check:'native-syntax-only',proxy:options.proxy,status:'UNAVAILABLE',nativeExitCode:null,runtimeVerified:false};
  try{
    for(const [name,directory] of [['executable',false],['config',false],['root',true]]){
      const p=options[name],s=statSync(p);if((directory?!s.isDirectory():!s.isFile())||realpathSync(p)!==p)return {code:4,report};
    }
    const args=options.proxy==='nginx'?['-t','-p',options.root+path.sep,'-c',options.config]:['-t','-d',options.root,'-f',options.config];
    const result=run(options.executable,args,{shell:false,windowsHide:true,cwd:options.root,encoding:'utf8',timeout:15000,killSignal:'SIGKILL',maxBuffer:65536});
    if(result.error){report.status=result.error.code==='ETIMEDOUT'?'TIMED_OUT':result.error.code==='ENOBUFS'?'OUTPUT_LIMIT':'UNAVAILABLE';return {code:report.status==='TIMED_OUT'?5:report.status==='OUTPUT_LIMIT'?6:4,report};}
    report.nativeExitCode=Number.isInteger(result.status)?result.status:null;
    report.status=result.status===0?'PASSED':'FAILED';return {code:result.status===0?0:3,report};
  }catch{ return {code:4,report}; }
}
export function main(args){
  try{const options=parseArgs(args);if(options.help){process.stdout.write(HELP+'\n');return 0;}const result=checkProxy(options);process.stdout.write(JSON.stringify(result.report,null,2)+'\n');return result.code;}
  catch{process.stderr.write('INVALID_OPTIONS: use --help\n');return 2;}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))process.exitCode=main(process.argv.slice(2));
