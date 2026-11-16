import fs from 'node:fs/promises';import path from 'node:path';
import {LpacImageTransport} from '../../conversion/service/lpac-image-transport.mjs';
const options=JSON.parse(await fs.readFile(process.argv[2],'utf8')),transport=await new LpacImageTransport({...options.transport,recovery:{enabled:false,minAgeMs:0}}).init();
if(options.mode==='delete'){
 transport.workRecovery.policy.enabled=true;const remove=transport.workRecovery.removeFile.bind(transport.workRecovery);let once=true;
 transport.workRecovery.removeFile=async file=>{await remove(file);if(once){once=false;process.send({deleted:path.basename(file)});setInterval(()=>{},1000);await new Promise(()=>{});}};
 await transport.recover();
}else{
 const runtime={...options.runtime,verify:async()=>{}};transport.run(options.job,{runtime}).catch(error=>process.send?.({error:error.code}));
 const timer=setInterval(async()=>{for(const name of await fs.readdir(transport.root)){if(!/^[a-f0-9-]{36}$/.test(name))continue;try{const bytes=await fs.readFile(path.join(transport.root,name,'out/export.png'));if(/\nRECOVERY-GATE pid=\d+\n$/.test(bytes.toString('latin1'))){clearInterval(timer);process.send({ready:true,id:name,launcherPid:[...transport.children][0]?.pid,workerPid:Number(bytes.toString('latin1').match(/pid=(\d+)\n$/)[1])});return;}}catch(error){if(error.code!=='ENOENT')throw error;}}},25);
}
