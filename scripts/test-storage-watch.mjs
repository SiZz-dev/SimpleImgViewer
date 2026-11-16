import fs from 'node:fs/promises';import path from 'node:path';import assert from 'node:assert/strict';import {randomUUID,createHash} from 'node:crypto';import {execFile} from 'node:child_process';import {promisify} from 'node:util';
const manifest=JSON.parse(await fs.readFile('build/storage-watch/launcher.json','utf8')),report=path.resolve('build/reports/storage-watch');await fs.mkdir(report,{recursive:true});const result={passed:false,testOnly:true,cases:5,runs:[]};
try{
 for(const item of [...manifest.inputs,{path:manifest.executable,sha256:manifest.sha256}])assert.equal(createHash('sha256').update(await fs.readFile(item.path)).digest('hex'),item.sha256);
 for(let iteration=0;iteration<3;iteration++){const root=path.resolve('build/acceptance/watch-'+randomUUID().slice(0,8)),execution=await promisify(execFile)(manifest.executable,[root],{windowsHide:true,timeout:30000});assert.match(execution.stdout,/cases=5 churnScans=200 passed=true/);result.runs.push({root,churnScans:200});}result.passed=true;
}catch(error){result.error={message:error.message,code:error.code,stderr:error.stderr};process.exitCode=1;}
await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
