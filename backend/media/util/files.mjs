import fs from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {requireThat} from '../../system/util/errors.mjs';
export async function regular(file,max=104857600){
  const stat=await fs.lstat(file);requireThat(stat.isFile()&&!stat.isSymbolicLink()&&stat.size>0&&stat.size<=max&&path.normalize(await fs.realpath(file))===path.normalize(file),404,'FILE_UNAVAILABLE');return stat;
}
export async function digest(file){const hash=createHash('sha256');for await(const b of createReadStream(file))hash.update(b);return hash.digest('hex');}
export async function atomicJson(file,value){const temp=file+'.'+randomUUID()+'.tmp';await fs.writeFile(temp,JSON.stringify(value),{flag:'wx',mode:0o600});await fs.rename(temp,file);}
export async function readJson(file,max=1048576){await regular(file,max);return JSON.parse(await fs.readFile(file,'utf8'));}
export async function privateRoot(root){await fs.mkdir(root,{recursive:true,mode:0o700});requireThat(path.normalize(await fs.realpath(root))===path.normalize(root),500,'UNSAFE_STORAGE');return root;}
export function detect(b){
  if(b.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))return ['image','image/png'];
  if(b[0]===255&&b[1]===216&&b[2]===255)return ['image','image/jpeg'];
  if(/^GIF8[79]a/.test(b.subarray(0,6).toString()))return ['image','image/gif'];
  if(b.subarray(0,2).toString()==='BM')return ['image','image/bmp'];
  if(b.subarray(0,4).equals(Buffer.from([73,73,42,0]))||b.subarray(0,4).equals(Buffer.from([77,77,0,42])))return ['image','image/tiff'];
  if(b.subarray(0,4).toString()==='RIFF'&&b.subarray(8,12).toString()==='WEBP')return ['image','image/webp'];
  if(b.subarray(0,4).toString()==='RIFF'&&b.subarray(8,12).toString()==='WAVE')return ['audio','audio/wav'];
  if(b.subarray(0,3).toString()==='ID3'||b[0]===255&&(b[1]&224)===224)return ['audio','audio/mpeg'];
  if(b.subarray(4,8).toString()==='ftyp'){
    const brand=b.subarray(8,12).toString();if(['heic','heix','hevc','hevx','mif1','msf1','avif'].includes(brand))return ['unsupported','image/heif'];
    return ['video','video/mp4'];
  }
  if(b[0]===0&&b[1]===0&&b[2]===1&&[0xba,0xb3].includes(b[3]))return ['video','video/mpeg'];
  return null;
}
