// SPDX-License-Identifier: BSD-3-Clause
const fs=require('node:fs'),path=require('node:path'),net=require('node:net');
const [out,forbidden,port]=process.argv.slice(2),bin=path.dirname(process.execPath);
const attempt=action=>{try{action();return 'ALLOWED';}catch(error){return error.code;}};
const result={node:process.version,inputRead:attempt(()=>fs.readFileSync(__filename)),
  inputWrite:attempt(()=>{const fd=fs.openSync(__filename,'r+');fs.closeSync(fd);}),
  outsideRead:attempt(()=>fs.readFileSync(forbidden)),outsideWrite:attempt(()=>fs.writeFileSync(forbidden,'changed')),
  runtimeWrite:attempt(()=>{const fd=fs.openSync(path.join(bin,'LICENSE'),'r+');fs.closeSync(fd);}),
  allAppsRead:attempt(()=>fs.readFileSync(path.join(bin,'all-app.check'))),
  restrictedAppsRead:attempt(()=>fs.readFileSync(path.join(bin,'restricted-app.check')))};
const socket=net.connect({host:'127.0.0.1',port:Number(port)});let done=false;
function finish(network){if(done)return;done=true;result.network=network;socket.destroy();
  result.outputWrite=attempt(()=>fs.writeFileSync(path.join(out,'probe.json'),'synthetic output check'));
  const allowed=['EACCES','EPERM'];const pass=result.inputRead==='ALLOWED'&&result.restrictedAppsRead==='ALLOWED'
    &&['inputWrite','outsideRead','outsideWrite','runtimeWrite','allAppsRead'].every(key=>allowed.includes(result[key]))
    &&allowed.includes(network)&&result.outputWrite==='ALLOWED';
  const final={...result,passed:pass};if(result.outputWrite==='ALLOWED')fs.writeFileSync(path.join(out,'probe.json'),JSON.stringify(final));
  console.log(JSON.stringify(final));process.exitCode=pass?0:9;
}
socket.once('connect',()=>finish('ALLOWED'));socket.once('error',error=>finish(error.code));socket.setTimeout(2000,()=>finish('TIMEOUT'));
