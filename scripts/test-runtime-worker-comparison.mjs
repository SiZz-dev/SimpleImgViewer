import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import assert from 'node:assert/strict';
const output=path.resolve('build/reports/runtime-worker-comparison');await fs.mkdir(output,{recursive:true});
const result={passed:false,runs:[]};
try{
 for(const [trial,modes] of [['1',['main','main-fifo','thread1','thread2']],['2',['thread2','thread1','main-fifo','main']]])for(const mode of modes){
  const child=spawn(process.execPath,['scripts/test-runtime-worker-candidate.mjs'],{windowsHide:true,stdio:['ignore','ignore','pipe'],env:{...process.env,SIV_VERIFY_MODE:mode,SIV_VERIFY_TRIAL:trial}});let diagnostic='';child.stderr.on('data',bytes=>{if(diagnostic.length<4096)diagnostic+=bytes.toString();});const timer=setTimeout(()=>child.kill('SIGKILL'),120000);
  let code;try{[code]=await once(child,'exit');}finally{clearTimeout(timer);}assert.equal(code,0,diagnostic);
  const report=JSON.parse(await fs.readFile(path.join(output,trial+'-'+mode,'verification.json'),'utf8'));assert.equal(report.passed,true,report.error);result.runs.push(report);console.log(JSON.stringify({trial,mode,phases:report.phases.map(p=>({name:p.name,pageP95:p.pages?.p95Ms,healthP95:p.probes.health.p95Ms,bodyP95:p.probes.content.p95Ms,loopP99:p.eventLoop.p99Ms,rss:p.peakRssBytes}))}));
 }
 result.passed=true;
}catch(error){result.error=error.stack;process.exitCode=1;}
finally{await fs.writeFile(path.join(output,'verification.json'),JSON.stringify(result,null,2));}
