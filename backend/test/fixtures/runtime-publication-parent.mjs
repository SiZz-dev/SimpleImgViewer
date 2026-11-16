import fs from 'node:fs/promises';import path from 'node:path';
import {RuntimeVersions} from '../../conversion/service/runtime-versions.mjs';
const [root,source,text]=process.argv.slice(2),manager=await new RuntimeVersions(root).init();
await manager.publish({source,descriptor:JSON.parse(text),prepare:async(from,to)=>{
  await fs.mkdir(to);await fs.copyFile(path.join(from,'sample.exe'),path.join(to,'sample.exe'));
  setInterval(()=>{},1000);process.send({partial:to});await new Promise(()=>{});
}});
