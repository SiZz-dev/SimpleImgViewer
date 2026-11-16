import fs from 'node:fs/promises';
import {TemporaryFiles} from '../../media/service/temporary-files.mjs';
const owner=new TemporaryFiles(process.argv[2],{recoveryEnabled:true,minAgeMs:60000,scanLimit:100});
await owner.init();const file=owner.allocate();await fs.writeFile(file,'abandoned upload');
const old=new Date(Date.now()-120000);await fs.utimes(file,old,old);
process.send({file});
// Parent terminates this process without calling close(), simulating a crash.
setInterval(()=>{},1000);
