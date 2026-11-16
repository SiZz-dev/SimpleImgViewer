// SPDX-License-Identifier: BSD-3-Clause
// Synthetic acceptance worker; never executes the input image as code.
const fs=require('node:fs');
const path=require('node:path');
const sharp=require('sharp');
sharp.cache(false);sharp.concurrency(1);
const [input,out,forbidden]=process.argv.slice(2);
const base=()=>sharp(input,{limitInputPixels:1000000,failOn:'warning'});
async function main(){
  const metadata=await base().metadata();
  await base().rotate().resize(96,96,{fit:'inside',withoutEnlargement:true}).png().toFile(path.join(out,'preview.png'));
  await base().resize(32,32,{fit:'inside'}).png().toFile(path.join(out,'thumbnail.png'));
  await base().extract({left:8,top:8,width:32,height:24}).png().toFile(path.join(out,'crop.png'));
  const stamp=await base().resize(12,12,{fit:'fill'}).png().toBuffer();
  await base().rotate(90).modulate({brightness:1.15}).composite([{input:stamp,left:3,top:3}]).png().toFile(path.join(out,'edited.png'));
  const denied={};
  for(const [name,action] of Object.entries({
    inputWrite:()=>{const fd=fs.openSync(input,'r+');fs.closeSync(fd);},
    runtimeWrite:()=>{const fd=fs.openSync(path.join(__dirname,'LICENSE'),'r+');fs.closeSync(fd);},
    outsideRead:()=>fs.readFileSync(forbidden),
    outsideWrite:()=>{const fd=fs.openSync(forbidden,'r+');fs.closeSync(fd);}
  })) {try{action();denied[name]=false;}catch(error){denied[name]=['EACCES','EPERM'].includes(error.code);}}
  const result={passed:Object.values(denied).every(Boolean),denied,width:metadata.width,height:metadata.height,pages:metadata.pages||1,sharp:sharp.versions.sharp,vips:sharp.versions.vips};
  fs.writeFileSync(path.join(out,'probe.json'),JSON.stringify(result));
  console.log(JSON.stringify(result));if(!result.passed)process.exitCode=1;
}
main().catch(error=>{console.error(error.stack);process.exitCode=1;});
