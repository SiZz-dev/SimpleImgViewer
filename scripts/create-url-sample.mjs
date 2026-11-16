import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import sharp from 'sharp';
import {safeRuntimePath} from '../backend/conversion/util/runtime-inventory.mjs';
// No host font lookup, Java, external converter, download or overwrite.
export async function createUrlSample(output='.runtime/development/url-files/TestFiles/sample.jpg'){
 const root=fileURLToPath(new URL('../',import.meta.url)),target=path.resolve(root,output);
 if(!['.jpg','.jpeg'].includes(path.extname(target).toLowerCase())||!['.runtime','build'].some(name=>target.startsWith(path.join(root,name)+path.sep)))throw Error('Sample must be a JPEG under workspace .runtime or build');
 const parent=path.dirname(target);await safeRuntimePath(parent,{missing:true});
 try{await fs.lstat(target);return {created:false,path:target};}catch(error){if(error.code!=='ENOENT')throw error;}
 await fs.mkdir(parent,{recursive:true});await safeRuntimePath(parent);
 const width=800,height=500,rgb=Buffer.alloc(width*height*3);
 for(let y=0;y<height;y++)for(let x=0;x<width;x++){const grid=x%40===0||y%40===0,band=x>=80&&x<720&&y>=90&&y<390;const color=band?[93,179,255]:grid?[39,53,74]:[19,26,39];const i=(y*width+x)*3;rgb[i]=color[0];rgb[i+1]=color[1];rgb[i+2]=color[2];}
 const bytes=await sharp(rgb,{raw:{width,height,channels:3}}).jpeg({quality:90}).toBuffer();let handle;
 try{handle=await fs.open(target,'wx',0o600);await handle.writeFile(bytes);}catch(error){if(error.code==='EEXIST')return {created:false,path:target};throw error;}finally{await handle?.close();}
 return {created:true,path:target};
}
if(process.argv[1]&&path.resolve(process.argv[1]).toLowerCase()===fileURLToPath(import.meta.url).toLowerCase())console.log(JSON.stringify(await createUrlSample(process.argv[2])));
