import {build} from 'vite';
import fs from 'node:fs/promises';
export async function buildWeb(){
  await import('./audit-node-licenses.mjs');const result=await build({root:'frontend',base:'/viewer/',configFile:false,build:{outDir:'../dist',emptyOutDir:true,target:'es2022'}});
  const notices=[];for(const name of ['react','react-dom','scheduler']){
    const inventory=JSON.parse(await fs.readFile('build/reports/node-licenses/inventory.json','utf8'));const pkg=inventory.packages.find(p=>p.name===name);
    if(pkg)for(const notice of pkg.notices)notices.push(`${pkg.name}@${pkg.version}\n${await fs.readFile('build/reports/node-licenses/texts/'+notice.sha256+'.txt','utf8')}`);
  }
  await fs.writeFile('dist/THIRD_PARTY_NOTICES.txt',notices.join('\n\n'));return result;
}
if(process.argv[1]?.replaceAll('\\','/').endsWith('/scripts/build-web.mjs')||process.argv[1]==='scripts/build-web.mjs')await buildWeb();
