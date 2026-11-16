import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
const hash=b=>createHash('sha256').update(b).digest('hex');
const output=path.resolve('build/reports/node-licenses');await fs.mkdir(path.join(output,'texts'),{recursive:true});
const seen=new Set(),packages=[];
async function inspect(directory){
  const real=await fs.realpath(directory);if(seen.has(real))return;seen.add(real);
  let bytes,p;try{bytes=await fs.readFile(path.join(real,'package.json'));p=JSON.parse(bytes);}catch{return;}
  const notices=[];
  for(const entry of await fs.readdir(real,{withFileTypes:true})){
    if(!entry.isFile()||!/^(?:licen[cs]e|copying|notice|copyright)(?:[.\-_]|$)/i.test(entry.name))continue;
    const file=path.join(real,entry.name);if((await fs.stat(file)).size>2097152)throw Error('Notice size limit');const content=await fs.readFile(file),sha=hash(content);
    await fs.writeFile(path.join(output,'texts',sha+'.txt'),content);notices.push({file:entry.name,sha256:sha});
  }
  let license=typeof p.license==='string'?p.license:p.license?.type||p.licenses?.map(l=>l.type).join(' OR ')||'';
  if(!license&&notices.length){const content=await fs.readFile(path.join(output,'texts',notices[0].sha256+'.txt'),'utf8');if(content.includes('Permission is hereby granted, free of charge'))license='MIT';}
  // MPL source/distribution terms and BOTH terms of the native conjunction remain applicable.
  const allowed=new Set(['MIT','ISC','Apache-2.0','BSD-2-Clause','BSD-3-Clause','0BSD','CC0-1.0','Python-2.0','MPL-2.0','LGPL-2.1-or-later','LGPL-3.0-or-later','Apache-2.0 AND LGPL-3.0-or-later','(MIT OR CC0-1.0)','MIT OR CC0-1.0','(MIT AND Zlib)','Zlib']);
  packages.push({name:p.name,version:p.version,declaredLicense:license,primaryTermsIdentified:allowed.has(license),packageJsonSha256:hash(bytes),notices});
}
const store=path.resolve('node_modules/.pnpm');
for(const entry of await fs.readdir(store,{withFileTypes:true})){
  if(!entry.isDirectory()||entry.name==='node_modules')continue;
  const modules=path.join(store,entry.name,'node_modules');
  for(const child of await fs.readdir(modules,{withFileTypes:true}).catch(()=>[])){
    if(child.name.startsWith('@'))for(const name of await fs.readdir(path.join(modules,child.name)))await inspect(path.join(modules,child.name,name));
    else if(!child.name.startsWith('.'))await inspect(path.join(modules,child.name));
  }
}
packages.sort((a,b)=>(a.name+'@'+a.version).localeCompare(b.name+'@'+b.version));
const baseline=JSON.parse(await fs.readFile('config/oss/node-license-baseline.windows-x64.json','utf8'));
const lockfileSha256=hash(await fs.readFile('pnpm-lock.yaml'));
const baselineMatch=baseline.platform===process.platform+'-'+process.arch&&baseline.lockfileSha256===lockfileSha256&&JSON.stringify(baseline.packages)===JSON.stringify(packages);
const unknown=packages.filter(p=>!p.primaryTermsIdentified);const result={schemaVersion:1,scope:'installed npm package metadata and preserved root notices; not full native source/redistribution approval',
  passed:unknown.length===0&&baselineMatch,baselineMatch,distributionReady:false,lockfileSha256,packages,unknown:unknown.map(p=>p.name+'@'+p.version),remaining:['native bundled subcomponent source and NOTICE inventory','corresponding sources/replacement instructions and distribution review','CVE and production isolation acceptance']};
await fs.writeFile(path.join(output,'inventory.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({passed:result.passed,packages:packages.length,unknown:result.unknown,distributionReady:false}));
if(!result.passed)throw Error('Node dependency identity/license baseline mismatch; review required');
