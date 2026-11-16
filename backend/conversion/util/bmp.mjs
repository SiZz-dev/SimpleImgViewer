import fs from 'node:fs/promises';
// Deliberately bounded BI_RGB 24/32-bit subset; no optional external loader.
export async function bmpRaster(file,maxPixels){
  const handle=await fs.open(file,'r');try{
    const header=Buffer.alloc(54);if((await handle.read(header,0,54,0)).bytesRead!==54||header.toString('ascii',0,2)!=='BM')return null;
    const offset=header.readUInt32LE(10),dib=header.readUInt32LE(14),width=header.readInt32LE(18),encodedHeight=header.readInt32LE(22),height=Math.abs(encodedHeight),bits=header.readUInt16LE(28);
    if(dib<40||dib>1048576||offset<14+dib||width<1||height<1||width*height>maxPixels||header.readUInt16LE(26)!==1||![24,32].includes(bits)||header.readUInt32LE(30)!==0)throw Error('Unsupported BMP profile');
    const stride=Math.ceil(width*bits/32)*4;if(offset+stride*height>(await handle.stat()).size)throw Error('Invalid BMP');
    const data=Buffer.alloc(width*height*3),row=Buffer.alloc(stride);
    for(let y=0;y<height;y++){
      if((await handle.read(row,0,stride,offset+(encodedHeight>0?height-1-y:y)*stride)).bytesRead!==stride)throw Error('Truncated BMP');
      for(let x=0;x<width;x++){const source=x*bits/8,target=(y*width+x)*3;data[target]=row[source+2];data[target+1]=row[source+1];data[target+2]=row[source];}
    }
    return {data,raw:{width,height,channels:3}};
  }finally{await handle.close();}
}
