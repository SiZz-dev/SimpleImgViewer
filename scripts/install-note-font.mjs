import fs from 'node:fs/promises';import path from 'node:path';import {createHash} from 'node:crypto';import assert from 'node:assert/strict';
const hash=(data,kind='sha256')=>createHash(kind).update(data).digest('hex'),policy=JSON.parse(await fs.readFile('config/oss/note-font.windows-x64.json','utf8')),root=path.resolve('.tools/fonts',policy.id);
assert.ok(process.argv.slice(2).every(arg=>['--download','--verify-only'].includes(arg)));await fs.mkdir(root,{recursive:true});assert.equal(await fs.realpath(root),root);
for(const file of policy.files){const target=path.join(root,file.name);let bytes;
 try{const stat=await fs.lstat(target);assert.ok(stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1&&stat.size===file.bytes);bytes=await fs.readFile(target);}catch(error){if(error.code!=='ENOENT'||!process.argv.includes('--download'))throw error;
  const response=await fetch('https://raw.githubusercontent.com/notofonts/noto-cjk/'+policy.commit+'/'+file.path,{redirect:'error',signal:AbortSignal.timeout(60000)});assert.equal(response.status,200);const parts=[];let length=0;for await(const part of response.body){length+=part.length;assert.ok(length<=file.bytes);parts.push(part);}bytes=Buffer.concat(parts);
  assert.equal(bytes.length,file.bytes);assert.equal(hash(bytes),file.sha256);assert.equal(hash(Buffer.concat([Buffer.from('blob '+bytes.length+'\0'),bytes]),'sha1'),file.gitBlobSha1);await fs.writeFile(target,bytes,{flag:'wx'});
 }
 assert.equal(bytes.length,file.bytes);assert.equal(hash(bytes),file.sha256);assert.equal(hash(Buffer.concat([Buffer.from('blob '+bytes.length+'\0'),bytes]),'sha1'),file.gitBlobSha1);
}
console.log(JSON.stringify({verified:true,id:policy.id,root,license:policy.license,files:policy.files.length,distributionReady:false}));
