import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync,rmdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {parseArgs,checkProxy} from './test-proxy-config.mjs';

test('only explicit absolute paths and syntax-only arguments are accepted',()=>{
  for(const args of [[],['--proxy','nginx','--reload'],['--proxy','other'],['--proxy','nginx','--proxy','apache'],['--proxy','nginx','--executable','relative','--config','relative','--root','relative']])assert.throws(()=>parseArgs(args));
  assert.deepEqual(parseArgs(['--help']),{help:true});
});
test('missing executable is unavailable and never spawns anything',()=>{
  let called=false;const result=checkProxy({proxy:'nginx',executable:path.join(tmpdir(),'imagegate-no-proxy-'+Date.now()),config:'unused',root:'unused'},()=>{called=true;});assert.equal(called,false);assert.equal(result.code,4);assert.equal(result.report.runtimeVerified,false);
});
test('native invocation disables shell, is bounded, and never starts or reloads servers',()=>{
  const root=mkdtempSync(path.join(tmpdir(),'imagegate-proxy-test-'));
  try{
    const executable=path.join(root,'proxy tool.exe'),config=path.join(root,'config with spaces.conf');writeFileSync(executable,'fixture');writeFileSync(config,'fixture');
    for(const proxy of ['nginx','apache']){
      const result=checkProxy({proxy,executable,config,root},(exe,args,options)=>{
        assert.equal(exe,executable);assert.deepEqual(args,proxy==='nginx'?['-t','-p',root+path.sep,'-c',config]:['-t','-d',root,'-f',config]);assert.equal(options.shell,false);assert.equal(options.windowsHide,true);assert.equal(options.timeout,15000);assert.equal(options.killSignal,'SIGKILL');assert.equal(options.maxBuffer,65536);return {status:0,stdout:'private certificate path',stderr:'secret'};
      });assert.equal(result.code,0);assert.equal(result.report.runtimeVerified,false);assert.ok(!JSON.stringify(result.report).includes('secret'));
    }
    for(const [result,code,status] of [[{status:1},3,'FAILED'],[{error:{code:'ETIMEDOUT'}},5,'TIMED_OUT'],[{error:{code:'ENOBUFS'}},6,'OUTPUT_LIMIT']]){
      const checked=checkProxy({proxy:'nginx',executable,config,root},()=>result);assert.equal(checked.code,code);assert.equal(checked.report.status,status);
    }
  }finally{rmSync(path.join(root,'proxy tool.exe'),{force:true});rmSync(path.join(root,'config with spaces.conf'),{force:true});rmdirSync(root);}
});
