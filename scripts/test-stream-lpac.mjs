import fs from 'node:fs/promises';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash,randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const hash=b=>createHash('sha256').update(b).digest('hex'),run=promisify(execFile);
const report=path.resolve('build/reports/stream-lpac');await fs.mkdir(report,{recursive:true});
const result={testOnly:true,productionEnabled:false,passed:false,cases:[]};
try{
  const manifest=JSON.parse(await fs.readFile('build/stream-lpac/launcher.json','utf8'));assert.equal(manifest.testOnly,true);
  for(const item of manifest.inputs)assert.equal(hash(await fs.readFile(item.path)),item.sha256);
  assert.equal(hash(await fs.readFile(manifest.executable)),manifest.sha256);assert.equal(hash(await fs.readFile(manifest.worker)),manifest.workerSha256);
  const root=path.resolve('build/acceptance/streams-'+randomUUID().slice(0,8));await fs.mkdir(root,{recursive:true});const input=path.join(root,'input.bin');await fs.writeFile(input,'synthetic stream test');
  const expected=(length,seed)=>Buffer.from(Array.from({length},(_,i)=>(i+seed)%256));
  for(const [index,mode] of [...Array(10).fill('tail'),'empty','boundary','stdout-over','stderr-over','flood','nonzero'].entries()){
    const start=performance.now(),execution=await run(manifest.executable,[manifest.runtime,input,path.join(root,mode+'-'+index),mode],{windowsHide:true,timeout:30000,maxBuffer:131072});
    assert.equal(execution.stderr,'');const facts=JSON.parse(execution.stdout),out=Buffer.from(facts.stdoutHex,'hex'),err=Buffer.from(facts.stderrHex,'hex');assert.ok(out.length<=8192&&err.length<=8192);
    if(['tail','empty','boundary','nonzero'].includes(mode)){
      const length=mode==='empty'?0:mode==='boundary'?8192:128;assert.deepEqual(out,expected(length,11));assert.deepEqual(err,expected(length,37));assert.equal(facts.truncated,false);assert.equal(facts.stdoutTruncated,false);assert.equal(facts.stderrTruncated,false);assert.equal(facts.exit,mode==='nonzero'?7:0);
    }else{
      assert.equal(facts.truncated,true);assert.equal(facts.exit,24);
      if(mode==='stdout-over'){assert.equal(facts.stdoutTruncated,true);assert.equal(facts.stderrTruncated,false);assert.equal(out.length,8192);}
      if(mode==='stderr-over'){assert.equal(facts.stderrTruncated,true);assert.equal(facts.stdoutTruncated,false);assert.equal(err.length,8192);}
      assert.deepEqual(out,expected(out.length,11));assert.deepEqual(err,expected(err.length,37));
    }
    assert.equal((await fs.readdir(path.join(root,'.imagegate-lpac-state'))).filter(name=>name.endsWith('.lease')).length,0);
    result.cases.push({mode,exit:facts.exit,stdoutBytes:out.length,stderrBytes:err.length,truncated:facts.truncated,elapsedMs:Math.round(performance.now()-start)});
  }
  result.passed=true;result.root=root;
}catch(error){result.error={message:error.message,stdout:error.stdout,stderr:error.stderr};process.exitCode=1;}
finally{await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify(result,null,2));
