import fs from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash,randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';

const report=path.resolve('build/reports/node-lpac');await fs.mkdir(report,{recursive:true});
const result={testOnly:true,productionEnabled:false,passed:false,status:'CHECKING',cases:[]};
await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));
const manifest=JSON.parse(await fs.readFile('build/node-lpac/launcher.json','utf8'));
assert.equal(manifest.testOnly,true);
for(const input of manifest.inputs)assert.equal(createHash('sha256').update(await fs.readFile(input.path)).digest('hex'),input.sha256,'Rebuild the test launcher after changing its inputs');
const node=path.resolve('.tools/runtimes/node-24.22.1/node.exe'),nodeHash=createHash('sha256').update(await fs.readFile(node)).digest('hex');
const runtime=path.resolve('.tools/node-lpac-runtime',nodeHash);await fs.mkdir(runtime,{recursive:true});
try{await fs.copyFile(node,path.join(runtime,'node.exe'),fs.constants.COPYFILE_EXCL);}catch(error){if(error.code!=='EEXIST')throw error;}
assert.equal(createHash('sha256').update(await fs.readFile(path.join(runtime,'node.exe'))).digest('hex'),nodeHash);
const license=await fs.readFile(path.resolve('.tools/runtimes/node-24.22.1/LICENSE'));await fs.writeFile(path.join(runtime,'LICENSE'),license);
const root=path.resolve('build/acceptance/node-lpac-'+randomUUID());await fs.mkdir(root,{recursive:true});
const forbidden=path.join(root,'forbidden.txt');await fs.writeFile(forbidden,'synthetic private marker');
let connections=0;const listener=net.createServer(socket=>{connections++;socket.destroy();});
await new Promise((resolve,reject)=>{listener.once('error',reject);listener.listen(0,'127.0.0.1',resolve);});
try{
  for(const [mode,program] of Object.entries(manifest.programs)){
    assert.equal(createHash('sha256').update(await fs.readFile(program.executable)).digest('hex'),program.sha256);
    const target=path.join(root,mode),before=connections;
    let output='',exit=0;
    try{const command=await promisify(execFile)(program.executable,[runtime,path.resolve('native/process-job/tests/node-lpac-probe.cjs'),target,forbidden,String(listener.address().port)],{windowsHide:true,timeout:30000,maxBuffer:16384});output=command.stdout+command.stderr;}
    catch(error){exit=error.code;output=(error.stdout||'')+(error.stderr||'');}
    const line=output.trim().split(/\r?\n/).find(line=>line.includes('diagnostics={'));
    let checks=null;
    const probeFile=path.join(target,'out','probe.json');
    try{const stat=await fs.lstat(probeFile);assert.ok(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1&&stat.size<=8192);assert.equal(await fs.realpath(probeFile),probeFile);checks=JSON.parse(await fs.readFile(probeFile,'utf8'));}catch(error){if(error.code!=='ENOENT')throw error;}
    if(line&&checks)assert.deepEqual(JSON.parse(line.slice(line.indexOf('diagnostics=')+12)),checks);
    const item={mode,exit,output,checks,connections:connections-before};
    if(mode==='production-guard'){assert.equal(exit,23);assert.match(output,/stage=program/);assert.equal(item.connections,0);item.guardPassed=true;}
    if(checks){assert.equal(exit,0);assert.equal(checks.passed,true,JSON.stringify(item));assert.equal(item.connections,0);
      // Same unloaded file is writable by the owner outside LPAC. Its denial
      // above cannot be explained by a loaded EXE/DLL section lock.
      const control=await fs.open(path.join(target,'bin','LICENSE'),'r+');await control.close();item.runtimeOwnerWriteControl=true;
    }
    result.cases.push(item);assert.equal(await fs.readFile(forbidden,'utf8'),'synthetic private marker');
  }
  const leases=await fs.readdir(path.join(root,'.imagegate-lpac-state'));assert.equal(leases.filter(name=>name.endsWith('.lease')).length,0);
  result.passed=result.cases.some(item=>item.exit===0&&item.checks?.passed&&item.connections===0)&&result.cases.some(item=>item.guardPassed);
  result.status=result.passed?'NODE_CORE_LPAC_PROBE_PASSED':'NODE_LPAC_COMPATIBILITY_BLOCKED';
  result.root=root;result.nodeSha256=nodeHash;result.remaining=['sharp/addon/module runtime staging','actual image and FFmpeg LPAC execution','production adapter and profile recovery acceptance'];
}finally{await new Promise(resolve=>listener.close(resolve));await fs.writeFile(path.join(report,'verification.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify(result,null,2));
if(!result.passed)process.exitCode=1;
