// Test-only immutable copy of the installed Windows x64 dependency closure.
import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {createHash,randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url),hash=b=>createHash('sha256').update(b).digest('hex');
assert.equal(process.platform,'win32');assert.equal(process.arch,'x64');
assert.equal(hash(await fs.readFile('.tools/runtimes/node-24.22.1/node.exe')),'d702daa815eabbff763c7d3808e235cf031cf2ca70211b56847bc72f51e240e5','Verify official Node ZIP/installed bytes first');
const root=path.resolve('.tools/nsl',randomUUID().slice(0,8));await fs.mkdir(root,{recursive:true});
const inventory=[],packages=[];
async function copy(source,relative){
  const stat=await fs.lstat(source);assert.ok(stat.isFile()&&!stat.isSymbolicLink());
  const bytes=await fs.readFile(source);assert.ok(bytes.length>0);
  const target=path.join(root,relative);await fs.mkdir(path.dirname(target),{recursive:true});await fs.writeFile(target,bytes,{flag:'wx'});
  inventory.push({path:relative.replaceAll('\\','/'),bytes:bytes.length,sha256:hash(bytes)});
}
async function tree(source,relative){
  for(const entry of await fs.readdir(source,{withFileTypes:true})){
    assert.ok(!entry.isSymbolicLink(),'No package links in staged closure');
    if(entry.isDirectory())await tree(path.join(source,entry.name),path.join(relative,entry.name));
    else if(/\.(cjs|mjs|js|json|node|dll|md)$/i.test(entry.name)||/^(LICENSE|NOTICE)/i.test(entry.name))await copy(path.join(source,entry.name),path.join(relative,entry.name));
  }
}
const sharpManifest=path.resolve(path.dirname(await fs.realpath(require.resolve('sharp'))),'../package.json');
const sharpRequire=createRequire(sharpManifest);
for(const [name,version] of [['sharp','0.35.5'],['@img/colour','1.1.0'],['detect-libc','2.1.2'],['semver','7.8.6'],['@img/sharp-win32-x64','0.35.5']]){
  let source;
  if(name==='sharp')source=path.dirname(await fs.realpath(sharpManifest));
  else {let entry=await fs.realpath(sharpRequire.resolve(name==='@img/sharp-win32-x64'?name+'/sharp.node':name));source=path.dirname(entry);while(true){try{const pkg=JSON.parse(await fs.readFile(path.join(source,'package.json'),'utf8'));if(pkg.name===name)break;}catch{}const parent=path.dirname(source);assert.notEqual(parent,source);source=parent;}}
  const pkg=JSON.parse(await fs.readFile(path.join(source,'package.json'),'utf8'));assert.equal(pkg.version,version);
  packages.push({name,version,license:pkg.license});await tree(source,path.join('node_modules',name));
}
await copy(path.resolve('.tools/runtimes/node-24.22.1/node.exe'),'node.exe');
await copy(path.resolve('.tools/runtimes/node-24.22.1/LICENSE'),'LICENSE');
await copy(path.resolve('native/process-job/tests/sharp-worker.cjs'),'sharp-worker.cjs');
for(const file of ['backend/conversion/util/image-worker.mjs','backend/conversion/util/bmp.mjs','backend/media/util/files.mjs','backend/system/util/errors.mjs'])await copy(path.resolve(file),file);
await copy(path.resolve('native/process-job/tests/image-job-entry.mjs'),'image-job-entry.mjs');
if(process.argv.includes('--fonts')){
  const policy=JSON.parse(await fs.readFile('config/oss/note-font.windows-x64.json','utf8'));
  for(const item of policy.files){const source=path.resolve('.tools/fonts',policy.id,item.name);assert.equal(hash(await fs.readFile(source)),item.sha256);await copy(source,'fonts/'+item.name);}
  await copy(path.resolve('config/oss/note-fonts.conf'),'fonts/fonts.conf');await copy(path.resolve('config/oss/note-font-NOTICE.txt'),'fonts/NOTICE.txt');
  const profile=Buffer.from(JSON.stringify({schemaVersion:1,id:policy.id,family:policy.family,license:policy.license,source:policy.source}));await fs.writeFile(path.join(root,'fonts/profile.json'),profile,{flag:'wx'});inventory.push({path:'fonts/profile.json',bytes:profile.length,sha256:hash(profile)});
}
const packageBytes=Buffer.from('{"name":"simpleimgviewer-sharp-lpac-test","private":true,"type":"commonjs"}\n');
await fs.writeFile(path.join(root,'package.json'),packageBytes,{flag:'wx'});
inventory.push({path:'package.json',bytes:packageBytes.length,sha256:hash(packageBytes)});
const report={testOnly:true,productionEnabled:false,root,packages,inventory,lockfileSha256:hash(await fs.readFile('pnpm-lock.yaml'))};
const reportRoot=process.argv.includes('--fonts')?'build/node-sharp-font-lpac':'build/node-sharp-lpac';await fs.mkdir(reportRoot,{recursive:true});await fs.writeFile(path.join(reportRoot,'runtime.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({root,packages,files:inventory.length,bytes:inventory.reduce((sum,f)=>sum+f.bytes,0)}));
