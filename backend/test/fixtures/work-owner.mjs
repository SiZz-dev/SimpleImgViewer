import fs from 'node:fs/promises';
import path from 'node:path';
import {openWorkLease} from '../../conversion/util/work-lease.mjs';
const lease=openWorkLease(process.argv[2]);
await fs.writeFile(path.join(process.argv[2],'preview.png'),'active raster');
process.send({ready:true});setInterval(()=>{},1000);
process.on('message',message=>{if(message==='stop'){lease.close();process.exit(0);}});
