import fs from 'node:fs/promises';import path from 'node:path';import {execFile,spawn} from 'node:child_process';import {promisify} from 'node:util';import {createHash,randomUUID} from 'node:crypto';import assert from 'node:assert/strict';import sharp from 'sharp';
import {RuntimeVersions} from '../backend/conversion/service/runtime-versions.mjs';import {NativeRuntimeSnapshots} from '../backend/conversion/service/runtime-snapshots.mjs';
const hash=b=>createHash('sha256').update(b).digest('hex'),run=promisify(execFile),report=path.resolve('build/reports/runtime-versions');await fs.mkdir(report,{recursive:true});
const result={testOnly:true,productionEnabled:false,passed:false,cases:[]};let manager,hold,closed;
try{
 const node=JSON.parse(await fs.readFile('build/node-sharp-lpac/runtime.json','utf8')),media=JSON.parse(await fs.readFile('build/ffmpeg-lpac/runtime.json','utf8'));
 const nodeManifest=JSON.parse(await fs.readFile('build/shared-node-lpac/launcher.json','utf8')),mediaManifest=JSON.parse(await fs.readFile('build/shared-ffmpeg-lpac/launcher.json','utf8'));
 for(const manifest of [nodeManifest,mediaManifest]){assert.equal(manifest.testOnly,true);for(const item of manifest.inputs)assert.equal(hash(await fs.readFile(item.path)),item.sha256);}
 const programs={node:nodeManifest.programs['registry-read'],ffmpeg:mediaManifest.programs['shared-test']},adapter=new NativeRuntimeSnapshots(programs);
 const sort=(a,b)=>a.name<b.name?-1:1;
 async function descriptor(runtime,kind){const directories=[];async function scan(dir,prefix=''){for(const entry of await fs.readdir(dir,{withFileTypes:true})){assert.ok(!entry.isSymbolicLink());if(entry.isDirectory()){const name=prefix+entry.name;directories.push(name);await scan(path.join(dir,entry.name),name+'/');}}}await scan(runtime.root);
  const files=[...runtime.inventory].sort((a,b)=>a.path<b.path?-1:1);const lines=kind==='ffmpeg'?files.map(x=>x.path+'\t'+x.bytes+'\t'+x.sha256+'\n'):[...directories.map(name=>({name,line:'D\t'+name+'\n'})),...files.map(x=>({name:x.path,line:'F\t'+x.path+'\t'+x.bytes+'\t'+x.sha256+'\n'}))].sort(sort).map(x=>x.line);
  return {kind,files,directories,nativeDigest:hash(Buffer.from(lines.join('')))};
 }
 const nodeDescriptor=await descriptor(node,'node'),ffDescriptor=await descriptor(media,'ffmpeg');
 const root=path.resolve('.tools/rv',randomUUID().slice(0,8)),jobs=path.resolve('build/acceptance/rv-'+randomUUID().slice(0,8));await fs.mkdir(jobs,{recursive:true});result.root=root;
 manager=await new RuntimeVersions(root,{maxBytes:1073741824,maxVersions:4}).init();
 const publish=(kind,runtime,descriptor)=>manager.publish({source:runtime.root,descriptor,prepare:(from,to)=>adapter.prepare(kind,from,to)});
 const a=await publish('node',node,nodeDescriptor);await manager.activate(a.id);const leaseA=await manager.acquire('node');
 // Approve a distinct synthetic revision without changing the installed/source runtime.
 const candidate=path.join(jobs,'candidate-b');await adapter.prepare('node',node.root,candidate);
 const changed='backend/conversion/util/image-worker.mjs';await fs.appendFile(path.join(candidate,changed),'\n// synthetic runtime generation B\n');const changedBytes=await fs.readFile(path.join(candidate,changed));
 const runtimeB={root:candidate,inventory:node.inventory.map(file=>file.path===changed?{...file,bytes:changedBytes.length,sha256:hash(changedBytes)}:file)},descriptorB=await descriptor(runtimeB,'node');assert.notEqual(descriptorB.nativeDigest,nodeDescriptor.nativeDigest);
 const b=await publish('node',runtimeB,descriptorB);const inspection=await manager.acquire('node');assert.equal(inspection.id,a.id);await inspection.release();
 await manager.activate(b.id);await manager.activate(a.id);const rollback=await manager.acquire('node');assert.equal(rollback.id,a.id);await rollback.release();await manager.activate(b.id);const leaseB=await manager.acquire('node');
 const input=path.join(jobs,'input.png');await sharp({create:{width:96,height:64,channels:3,background:'#1244cc'}}).png().toFile(input);const job=path.join(jobs,'preview.json');await fs.writeFile(job,JSON.stringify({operation:'preview',input:'@INPUT@',output:'@PREVIEW@',thumbnail:'@THUMBNAIL@',page:1,maxPixels:1000000,edge:64}));
 const args=(lease,target,mode='normal')=>[lease.path,input,target,job,'-',mode,'acceptance','shared',lease.nativeDigest];
 const execute=(exe,args)=>run(exe,args,{windowsHide:true,timeout:30000,maxBuffer:16384});
 const outputs=[path.join(jobs,'version-a'),path.join(jobs,'version-b')];const converted=await Promise.allSettled([execute(programs.node.executable,args(leaseA,outputs[0])),execute(programs.node.executable,args(leaseB,outputs[1]))]);for(const item of converted)assert.equal(item.status,'fulfilled',item.reason?.message);assert.equal(hash(await fs.readFile(path.join(outputs[0],'out/preview.png'))),hash(await fs.readFile(path.join(outputs[1],'out/preview.png'))));
 await assert.rejects(manager.retire(a.id),{code:'RUNTIME_IN_USE'});await assert.rejects(manager.retire(b.id),{code:'RUNTIME_ACTIVE'});result.cases.push({name:'publish-activate-rollback-and-two-generations',differentContentDigests:true,outputsMatched:true,oldLeasePreserved:true});
 const held=path.join(jobs,'held');hold=spawn(programs.node.executable,args(leaseA,held,'hold'),{windowsHide:true,stdio:['ignore','pipe','pipe']});hold.stdout.resume();hold.stderr.resume();closed=new Promise(resolve=>hold.once('close',resolve));
 let ready;const deadline=Date.now()+15000;while(Date.now()<deadline){try{ready=JSON.parse(await fs.readFile(path.join(held,'out/ready.json'),'utf8'));break;}catch(error){if(error.code!=='ENOENT')throw error;}await new Promise(resolve=>setTimeout(resolve,50));}assert.ok(ready);
 await leaseA.release();await leaseB.release();await manager.close();manager=await new RuntimeVersions(root).init();const blocked=await manager.retire(a.id);assert.equal(blocked.busy,true);assert.equal(blocked.state,'retiring');await manager.close();manager=await new RuntimeVersions(root).init();const live=await manager.acquire('node');assert.equal(live.id,b.id);await execute(programs.node.executable,args(live,path.join(jobs,'after-switch')));await live.release();result.cases.push({name:'native-pin-after-owner-reopen',busy:true,newVersionStillUsable:true});
 hold.kill('SIGKILL');await closed;hold=null;const recovery=await execute(programs.node.executable,['--recover',path.join(jobs,'.imagegate-lpac-state')]);assert.match(recovery.stdout,/recovered=1 busy=0/);
 assert.equal((await manager.retire(a.id)).state,'retired');await assert.rejects(fs.stat(leaseA.path),{code:'ENOENT'});assert.equal((await manager.retire(a.id)).state,'retired');await assert.rejects(manager.activate(a.id),{code:'RUNTIME_NOT_READY'});result.cases.push({name:'physical-node-reclamation',filesRemoved:node.inventory.length,retiredCannotReactivate:true});
 const c=await publish('ffmpeg',media,ffDescriptor);await manager.activate(c.id);const ffLease=await manager.acquire('ffmpeg'),wav=path.join(jobs,'input.wav');await execute(path.join(media.root,'ffmpeg.exe'),['-v','error','-nostdin','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','1','-c:a','pcm_s16le',wav]);
 const audio=path.join(jobs,'managed-audio');await execute(programs.ffmpeg.executable,[ffLease.path,wav,audio,'audio-wav','-','shared',ffLease.nativeDigest]);assert.ok((await fs.stat(path.join(audio,'out/playback'))).size>0);await ffLease.release();
 const d=await publish('ffmpeg',media,ffDescriptor);await manager.activate(d.id);assert.equal((await manager.retire(c.id)).state,'retired');await assert.rejects(fs.stat(ffLease.path),{code:'ENOENT'});const nodeAgain=await manager.acquire('node');assert.equal(nodeAgain.id,b.id);await nodeAgain.release();result.cases.push({name:'independent-ffmpeg-generation-switch-and-reclamation',filesRemoved:media.inventory.length,nodeSelectionPreserved:true});
 result.versions=await manager.list();result.passed=true;
}catch(error){result.error={message:error.message,stdout:error.stdout,stderr:error.stderr};process.exitCode=1;}
finally{if(hold){hold.kill('SIGKILL');await closed;}if(manager)await manager.close();await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify(result,null,2));
