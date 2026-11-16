// Independent process: server event-loop stalls must remain visible in HTTP timings.
import assert from 'node:assert/strict';
import {setTimeout as delay} from 'node:timers/promises';
process.on('message',async job=>{
 try{
  const until=performance.now()+job.durationMs,probes=[],pages=[];let securityChecks=0;
  const request=(url,options={})=>fetch(job.base+url,{...options,signal:AbortSignal.timeout(10000),headers:{Cookie:job.cookie,...options.headers}});
  async function check(url,expected=200){const start=performance.now(),response=await request(url);assert.equal(response.status,expected);const bytes=Buffer.from(await response.arrayBuffer());if(url===job.source)assert.equal(bytes.subarray(0,8).toString('hex'),'89504e470d0a1a0a');return performance.now()-start;}
  const probe=async()=>{let index=0;while(performance.now()<until){const kind=['health','bootstrap','content'][index++%3],url={health:'/actuator/health',bootstrap:'/api/v1/viewer/bootstrap',content:job.source}[kind];probes.push({kind,ms:await check(url)});if(index%12===0){await check('/api/v1/media/00000000-0000-4000-8000-000000000000',404);await check(job.source.replace(/sources\/[a-f0-9]{64}/,'sources/'+'0'.repeat(64)),404);const response=await request('/api/v1/media',{method:'POST'});assert.equal(response.status,403);await response.arrayBuffer();securityChecks+=3;}await delay(20);}};
  const load=async()=>{while(performance.now()<until){const batch=await Promise.all(Array.from({length:3},async()=>{const start=performance.now(),response=await request(job.page);assert.equal(response.status,200);const info=await response.json();assert.equal(info.source.url,job.source);assert.equal(info.coordinateWidth,800);return performance.now()-start;}));pages.push(...batch);await delay(5);}};
  await Promise.all([probe(),...(job.load?[load()]:[])]);process.send({passed:true,probes,pages,securityChecks});
 }catch(error){process.send({passed:false,error:error.stack});}
});
process.send({ready:true});
