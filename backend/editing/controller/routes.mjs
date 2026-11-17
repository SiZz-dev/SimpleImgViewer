import {receiveUpload} from '../../media/service/upload.mjs';
import {requireThat} from '../../system/util/errors.mjs';
const wrap=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);
export function registerEditingRoutes(app,{store,editing,admission}){
  const stampsEnabled=()=>requireThat(store.config.features.stamps,403,'FEATURE_DISABLED');
  app.get('/api/v1/stamps',wrap(async(req,res)=>{stampsEnabled();res.json(await editing.listStamps(req.actor));}));
  app.post('/api/v1/stamps',wrap(async(req,res)=>admission(async()=>{const received=await receiveUpload(req,store,2097152,false);let stamp;try{stamp=await editing.createStamp(received.temp,received.fileName,req.actor);}finally{await store.releaseTemp(received.temp);}res.status(201).json(stamp);})));
  app.get('/api/v1/stamps/:id/content',wrap(async(req,res)=>{stampsEnabled();res.type('png').sendFile((await editing.stamp(req.params.id,req.actor)).file,{dotfiles:'allow'});}));
  const annotations='/api/v1/media/:id/pages/:page/annotations';
  app.get(annotations,wrap(async(req,res)=>res.json(editing.public((await editing.document(req.params.id,Number(req.params.page),req.actor)).doc))));
  for(const [method,route,action] of [['post',annotations,'create'],['put',annotations+'/:itemId','replace'],['delete',annotations+'/:itemId','delete'],['post',annotations+'/history/:direction','history']]){
    app[method](route,wrap(async(req,res)=>{const direction=action==='history'?req.params.direction:action;requireThat(['create','replace','delete','undo','redo'].includes(direction),400,'INVALID_EDIT');
      res.status(action==='create'?201:200).json(await editing.change(req.params.id,Number(req.params.page),req.actor,req.body,direction,req.params.itemId));}));
  }
  for(const operation of ['crop','export'])app.post('/api/v1/media/:id/pages/:page/'+operation,wrap(async(req,res)=>{
    const content=await editing.export(req.params.id,Number(req.params.page),req.actor,req.body,operation==='export');res.type('png').set('Content-Disposition','attachment; filename="simpleimgviewer-'+operation+'.png"').send(content);
  }));
}
