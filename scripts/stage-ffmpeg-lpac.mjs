// Local test-only runtime. No downloads or redistribution approval.
import fs from 'node:fs/promises';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash,randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const hash=b=>createHash('sha256').update(b).digest('hex');
assert.equal(process.platform,'win32');assert.equal(process.arch,'x64');
await promisify(execFile)('pwsh.exe',['-NoProfile','-File',path.resolve('scripts/install-native-modules.ps1'),'-VerifyOnly'],{windowsHide:true,timeout:60000,maxBuffer:1048576});
const policy=JSON.parse(await fs.readFile('config/oss/native-downloads.windows-x64.json','utf8'));
const module=policy.modules.find(item=>item.kind==='ffmpeg');
const installed=path.resolve('.tools/modules',module.id),receipt=JSON.parse(await fs.readFile(path.join(installed,'installation.json'),'utf8'));
assert.equal(receipt.archiveSha256,module.sha256);
const names=['ffmpeg.exe','ffprobe.exe','avcodec-62.dll','avdevice-62.dll','avfilter-11.dll','avformat-62.dll','avutil-60.dll','swresample-6.dll','swscale-9.dll','LICENSE.txt'];
const root=path.resolve('.tools/fml',randomUUID().slice(0,8));await fs.mkdir(root,{recursive:true});const inventory=[];
for(const name of names){
  const relative=name==='LICENSE.txt'?name:'bin/'+name,source=path.join(installed,relative),stat=await fs.lstat(source);
  assert.ok(stat.isFile()&&!stat.isSymbolicLink());const bytes=await fs.readFile(source),expected=receipt.files.find(item=>item.path===relative);
  assert.equal(hash(bytes),expected.sha256);assert.equal(bytes.length,expected.bytes);
  await fs.writeFile(path.join(root,name),bytes,{flag:'wx'});inventory.push({path:name,bytes:bytes.length,sha256:hash(bytes)});
}
const report={testOnly:true,productionEnabled:false,distributionReady:false,root,moduleId:module.id,archiveSha256:module.sha256,policySha256:hash(await fs.readFile('config/oss/native-downloads.windows-x64.json')),inventory};
await fs.mkdir('build/ffmpeg-lpac',{recursive:true});await fs.writeFile('build/ffmpeg-lpac/runtime.json',JSON.stringify(report,null,2));
console.log(JSON.stringify({root,files:inventory.length,bytes:inventory.reduce((n,item)=>n+item.bytes,0),testOnly:true}));
