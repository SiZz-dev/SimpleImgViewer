// SPDX-License-Identifier: BSD-3-Clause
// Deterministic cancellation gate for acceptance only; normal mode imports the real worker immediately.
import fs from 'node:fs/promises';
import path from 'node:path';
try{
  if(process.argv[3]==='hold'){
    await fs.writeFile(path.join(path.dirname(process.argv[2]),'ready.json'),JSON.stringify({pid:process.pid}));
    await new Promise(resolve=>setTimeout(resolve,60000));
  }
  if(process.argv[3]==='normal'){
    const job=JSON.parse(await fs.readFile(process.argv[2],'utf8'));
    for(const file of [process.argv[2],job.input,...(job.items||[]).filter(item=>item.type==='stamp').map(item=>item.file)]){
      let denied=false;
      try{const handle=await fs.open(file,'r+');await handle.close();}catch(error){if(!['EPERM','EACCES'].includes(error.code))throw error;denied=true;}
      if(!denied)throw Error('Writable job/input/stamp binding');
    }
  }
  await import('./backend/conversion/util/image-worker.mjs');
}catch{console.log(JSON.stringify({ok:false}));process.exitCode=1;}
