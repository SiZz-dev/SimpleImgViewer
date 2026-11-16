import {receiveUpload} from '../service/upload.mjs';
import {importRemote} from '../service/remote.mjs';
import {requireThat} from '../../system/util/errors.mjs';
const wrap=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);
export function registerMediaRoutes(app,{store,media,admission}){
  app.post('/api/v1/media',wrap(async(req,res)=>admission(async()=>{
    const received=await receiveUpload(req,store);let record;try{record=await store.register(received.temp,received.fileName,req.actor);}finally{await store.releaseTemp(received.temp);}res.status(201).json(media.public(record));
  })));
  app.post('/api/v1/media/import-url',wrap(async(req,res)=>admission(async()=>res.status(201).json(media.public(await importRemote(req.body,store,req.actor))))));
  app.get('/api/v1/media/:id',wrap(async(req,res)=>res.json(media.public(await store.require(req.params.id,req.actor)))));
  app.get('/api/v1/media/:id/pages/:page',wrap(async(req,res)=>res.json(await media.page(req.params.id,Number(req.params.page),req.actor))));
  app.get('/api/v1/media/:id/status',wrap(async(req,res)=>res.json(await media.page(req.params.id,Number(req.query.page||1),req.actor))));
  app.post('/api/v1/media/:id/previews',wrap(async(req,res)=>res.json(await media.page(req.params.id,Number(req.query.page||1),req.actor))));
  app.get('/api/v1/media/:id/sources/:sourceId/:role',wrap(async(req,res)=>{
    requireThat(['content','thumbnail'].includes(req.params.role)&&/^[a-f0-9]{64}$/.test(req.params.sourceId),404,'SOURCE_NOT_FOUND');
    const file=await media.source(req.params.id,Number(req.query.page||1),req.params.sourceId,req.params.role,req.actor);res.type('png').sendFile(file,{dotfiles:'allow'});
  }));
}
