import sharp from 'sharp';
import {bmpRaster} from './bmp.mjs';
import fs from 'node:fs/promises';
import path from 'node:path';
import {readJson} from '../../media/util/files.mjs';
sharp.cache(false);sharp.concurrency(1);
const escape=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]));
async function main(){
  try{
    const manifest=process.argv[2];if(!manifest||path.basename(manifest)!=='job.json'||!path.isAbsolute(manifest))throw Error('job');
    const work=path.dirname(manifest);
    try{await fs.lstat(path.join(work,'retired'));throw Error('retired-work');}catch(error){if(error.code!=='ENOENT')throw error;}
    const job=await readJson(manifest);
    if(path.dirname(job.output)!==work||!['preview.png','export.png'].includes(path.basename(job.output))
      ||job.operation==='preview'&&job.thumbnail!==path.join(work,'thumbnail.png'))throw Error('output');
    const options={limitInputPixels:job.maxPixels,failOn:'warning',page:(job.page||1)-1,pages:1};
    const handle=await fs.open(job.input,'r'),signature=Buffer.alloc(2);try{await handle.read(signature,0,2,0);}finally{await handle.close();}
    const bmp=signature.toString()==='BM'?await bmpRaster(job.input,job.maxPixels):null;
    const open=()=>bmp?sharp(bmp.data,{raw:bmp.raw,limitInputPixels:job.maxPixels}):sharp(job.input,options);
    const meta=await open().metadata();
    if((job.page||1)>(meta.pages||1))throw Error('page');
    if(!['png','jpeg','gif','raw','tiff','webp'].includes(meta.format)||!meta.width||!meta.height)throw Error('unsupported');
    const swapped=[5,6,7,8].includes(meta.orientation);const width=swapped?meta.height:meta.width,height=swapped?meta.width:meta.height;
    if(width*height>job.maxPixels)throw Error('pixels');
    if(job.operation==='preview'){
      const output=await open().rotate().resize(job.edge,job.edge,{fit:'inside',withoutEnlargement:true}).png().toFile(job.output);
      await sharp(job.output,{limitInputPixels:job.maxPixels}).resize(256,256,{fit:'inside',withoutEnlargement:true}).png().toFile(job.thumbnail);
      console.log(JSON.stringify({ok:true,width,height,pageCount:meta.pages||1,previewWidth:output.width,previewHeight:output.height,orientation:meta.orientation||1}));
    }else if(job.operation==='export'){
      const base=await open().rotate().ensureAlpha().raw().toBuffer({resolveWithObject:true});
      let image=sharp(base.data,{raw:{width:base.info.width,height:base.info.height,channels:base.info.channels}});
      if(job.brightness!==100)image=image.modulate({brightness:job.brightness/100});
      const layers=[];
      for(const item of job.items||[]){
        const w=Math.max(1,Math.min(Math.round(item.width),base.info.width-Math.floor(item.x))),h=Math.max(1,Math.min(Math.round(item.height),base.info.height-Math.floor(item.y)));let input;
        if(item.type==='stamp')input=await sharp(item.file,{limitInputPixels:4194304}).resize(w,h,{fit:'fill'}).png().toBuffer();
        else{
          if(process.env.SIV_LPAC==='1'&&process.env.SIV_NOTE_FONT!=='noto-sans-cjk-kr-2.004')throw Error('note-font');
          const family=process.env.SIV_NOTE_FONT==='noto-sans-cjk-kr-2.004'?'Noto Sans CJK KR':'sans-serif';
          const lines=String(item.text||'').split('\n').slice(0,20);
          input=Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><rect width="100%" height="100%" fill="#fff1a8" fill-opacity=".92"/>${lines.map((line,i)=>`<text x="6" y="${20+i*19}" font-family="${family}" font-size="16" fill="#18202b">${escape(line)}</text>`).join('')}</svg>`);
        }
        layers.push({input,left:Math.floor(item.x),top:Math.floor(item.y)});
      }
      if(layers.length)image=image.composite(layers);
      // Materialize operations before crop/rotation so annotation coordinates remain original upright pixels.
      const composed=await image.png().toBuffer();image=sharp(composed,{limitInputPixels:job.maxPixels});
      if(job.crop)image=image.extract(job.crop);
      if(job.rotation)image=image.rotate(job.rotation,{background:{r:0,g:0,b:0,alpha:0}});
      const output=await image.png().toFile(job.output);console.log(JSON.stringify({ok:true,width:output.width,height:output.height}));
    }else throw Error('operation');
  }catch{console.log(JSON.stringify({ok:false}));process.exitCode=1;}
}
await main();
