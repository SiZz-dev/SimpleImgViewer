import fs from 'node:fs/promises';import path from 'node:path';import {execFile} from 'node:child_process';import {promisify} from 'node:util';import {createHash,randomUUID} from 'node:crypto';import assert from 'node:assert/strict';
const node=process.argv.includes('--node'),folder=node?'shared-node-lpac':'shared-ffmpeg-lpac';
const report=path.resolve('build/reports/'+folder+'/acl.json'),hash=b=>createHash('sha256').update(b).digest('hex');
const result={testOnly:true,productionEnabled:false,passed:false};
try{
 const shared=JSON.parse(await fs.readFile('build/reports/'+folder+'/verification.json','utf8'));assert.equal(shared.passed,true);
 const manifest=JSON.parse(await fs.readFile('build/stream-lpac/launcher.json','utf8'));assert.equal(manifest.testOnly,true);
 for(const item of manifest.inputs)assert.equal(hash(await fs.readFile(item.path)),item.sha256);
 assert.equal(hash(await fs.readFile(manifest.executable)),manifest.sha256);assert.equal(hash(await fs.readFile(manifest.worker)),manifest.workerSha256);
 const root=path.resolve('build/acceptance/shared-acl-'+randomUUID().slice(0,8));await fs.mkdir(root,{recursive:true});const input=path.join(root,'input.bin');await fs.writeFile(input,'synthetic ACL probe');
 const targets=node?[
  {name:'LICENSE',file:path.join(shared.root,'runtime/LICENSE')},{name:'nested-js',file:path.join(shared.root,'runtime/backend/media/util/files.mjs')},
  {name:'nested-directory',file:path.join(shared.root,'runtime/backend/media'),directory:true},
  {name:'private-input',file:path.join(shared.root,'holder-0/input/source.cjs'),private:true},
  {name:'private-output',file:path.join(shared.root,'holder-0/out/job.json'),private:true}
 ]:[{name:'LICENSE',file:path.join(shared.root,'runtime/LICENSE.txt')}];result.cases=[];
 for(const target of targets){
  const before=target.directory?null:hash(await fs.readFile(target.file));
  if(!target.directory){const owner=await fs.open(target.file,'r+');await owner.close();}
  const execution=await promisify(execFile)(manifest.executable,[manifest.runtime,input,path.join(root,'job-'+result.cases.length),(target.private?'private:':'snapshot:')+target.file],{windowsHide:true,timeout:30000,maxBuffer:131072});
  assert.equal(execution.stderr,'');const facts=JSON.parse(execution.stdout);assert.equal(facts.exit,0);assert.equal(facts.truncated,false);
  const access=JSON.parse(Buffer.from(facts.stdoutHex,'hex').toString('utf8'));assert.deepEqual(access,{read:target.private?5:0,write:5,delete:5,dacl:5,owner:5});assert.equal(facts.stderrHex,'');
  if(!target.directory)assert.equal(hash(await fs.readFile(target.file)),before);result.cases.push({name:target.name,access});
 }
 assert.equal((await fs.readdir(path.join(root,'.imagegate-lpac-state'))).filter(file=>file.endsWith('.lease')).length,0);
 result.passed=true;result.access=result.cases[0].access;result.ownerWriteControl=true;result.runtimePinsActive=false;
}catch(error){result.error={message:error.message,stdout:error.stdout,stderr:error.stderr};process.exitCode=1;}
finally{await fs.writeFile(report,JSON.stringify(result,null,2));}
console.log(JSON.stringify(result));
