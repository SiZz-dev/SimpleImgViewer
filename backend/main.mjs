import {createApplication} from './system/controller/application.mjs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
export async function start(development=false){
  const application=await createApplication({development});const address=await application.listen();console.log(`SimpleImgViewer Node.js listening on http://${application.config.server.host}:${address.port}/viewer`);
  if(development)console.log('Development administrator login enabled. Do not use this mode in production.');
  for(const signal of ['SIGINT','SIGTERM'])process.once(signal,async()=>{await application.close();process.exit(0);});return application;
}
const invoked=process.argv[1]?path.resolve(process.argv[1]):'';
const ownFile=fileURLToPath(import.meta.url);
if(invoked&&(process.platform==='win32'?invoked.toLowerCase()===ownFile.toLowerCase():invoked===ownFile))await start(process.argv.includes('--dev'));
