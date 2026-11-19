// Usability test server (UX renewal step 5, docs/UX_USABILITY_TEST.md).
// Starts the viewer on its own port with a throwaway storage root and writes sample files for the tasks,
// so participants never touch .runtime/node/media or the 8080 development server.
//   node scripts/usability-test-server.mjs [--port 18200] [--keep]
// Each run starts empty (participants open the files themselves). Ctrl+C stops the server and deletes the
// temporary folder unless --keep is given.
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
import {buildWeb} from './build-web.mjs';
import {createApplication} from '../backend/system/controller/application.mjs';

const args=process.argv.slice(2);
const option=name=>{const i=args.indexOf(name);return i>=0?args[i+1]:undefined;};
const port=Number(option('--port')||18200),keep=args.includes('--keep');
if(!Number.isInteger(port)||port<1024||port>65535||port===8080)throw new Error('--port must be 1024-65535 and not 8080');

const sharp=createRequire(import.meta.url)('sharp');
const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-usability-'));
const storage=path.join(root,'store'),samples=path.join(root,'samples');
await fs.mkdir(samples,{recursive:true});

const svg=body=>Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" ${body}</svg>`);
// T1-T5: a scanned-document look so "top left" / "bottom right" are easy to point at without text.
const lines=Array.from({length:18},(_,i)=>`<rect x="140" y="${190+i*34}" width="${i%5===4?520:900}" height="12" rx="6" fill="#c9ced6"/>`).join('');
await sharp(svg(`width="1200" height="900"><rect width="1200" height="900" fill="#e8ebef"/><rect x="80" y="60" width="1040" height="780" fill="#ffffff"/><rect x="140" y="110" width="420" height="34" rx="6" fill="#4a5568"/>${lines}<rect x="820" y="660" width="220" height="120" fill="none" stroke="#d64545" stroke-width="6"/>`)).png().toFile(path.join(samples,'document.png'));
// T4: a transparent stamp.
await sharp(svg(`width="240" height="240"><circle cx="120" cy="120" r="104" fill="none" stroke="#c62828" stroke-width="14"/><path d="M70 124 L106 160 L176 88" fill="none" stroke="#c62828" stroke-width="18" stroke-linecap="round" stroke-linejoin="round"/>`)).png().toFile(path.join(samples,'stamp-check.png'));
// T7/T8: a two-page file.
const frame=color=>sharp({create:{width:800,height:600,channels:3,background:color}}).png().toBuffer();
await sharp([await frame('#2e7d32'),await frame('#1565c0')],{join:{animated:true}}).gif().toFile(path.join(samples,'two-pages.gif'));
// Optional: playback fixtures made by the playback tests, if present on this PC.
for(const name of ['input.mp4','input.wav'])await fs.copyFile(path.join('build/playback-fixtures',name),path.join(samples,name)).catch(()=>{});

await buildWeb();
const app=await createApplication({development:true,overrides:{server:{host:'127.0.0.1',port,allowedHosts:['127.0.0.1','localhost']},storage:{root:storage}}});
await app.listen();
console.log(`\nUsability test viewer: http://127.0.0.1:${port}/viewer`);
console.log(`Sample files:          ${samples}`);
console.log(`Stop with Ctrl+C${keep?' (temporary folder is kept)':' (temporary folder is deleted)'}\n`);

let closing=false;
async function stop(){
  if(closing)return;closing=true;
  await app.close();
  if(!keep)await fs.rm(root,{recursive:true,force:true});else console.log('Kept '+root);
  process.exit(0);
}
process.on('SIGINT',stop);process.on('SIGTERM',stop);
