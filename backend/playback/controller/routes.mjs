import {createReadStream} from 'node:fs';
import {regular} from '../../media/util/files.mjs';
import {requireThat} from '../../system/util/errors.mjs';
const wrap=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);
export function registerPlaybackRoutes(app,{store,playback,config}){
  for(const method of ['get','post'])app[method]('/api/v1/media/:id/playback',wrap(async(req,res)=>{
    const record=await store.require(req.params.id,req.actor);requireThat(record.mediaType!=='image',415,'NOT_PLAYABLE');
    if(!config.playback.enabled)return res.json({state:'disabled'});const info=await playback.prepare(record,req.actor);const current=await store.require(record.mediaId,req.actor);requireThat(current.mediaVersion===record.mediaVersion,409,'MEDIA_CHANGED');const visible={...info};delete visible.runtimeIdentity;delete visible.originalSha256;res.json(visible);
  }));
  app.get('/api/v1/media/:id/playback/sources/:sourceId/content',wrap(async(req,res)=>{
    const record=await store.require(req.params.id,req.actor);requireThat(config.playback.enabled,403,'PLAYBACK_DISABLED');const info=await playback.source(record,req.params.sourceId);const current=await store.require(record.mediaId,req.actor);requireThat(current.mediaVersion===record.mediaVersion,409,'MEDIA_CHANGED');
    const stat=await regular(playback.file(info),268435456);let start=0,end=stat.size-1;res.type(info.mimeType);res.set({'Accept-Ranges':'bytes',ETag:'"'+info.sourceId+'"'});
    if(req.headers.range&&(!req.headers['if-range']||req.headers['if-range']==='"'+info.sourceId+'"')){
      const m=/^bytes=(\d*)-(\d*)$/.exec(req.headers.range);if(!m||(!m[1]&&!m[2]))return res.status(416).set('Content-Range','bytes */'+stat.size).end();
      if(m[1]){start=Number(m[1]);end=m[2]?Math.min(end,Number(m[2])):end;}else start=Math.max(0,stat.size-Number(m[2]));
      if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start<0||start>end||start>=stat.size)return res.status(416).set('Content-Range','bytes */'+stat.size).end();
      res.status(206).set('Content-Range',`bytes ${start}-${end}/${stat.size}`);
    }
    res.set('Content-Length',String(end-start+1));if(req.method==='HEAD')return res.end();
    const stream=createReadStream(playback.file(info),{start,end});stream.on('error',()=>res.destroy());res.on('close',()=>stream.destroy());stream.pipe(res);
  }));
}
