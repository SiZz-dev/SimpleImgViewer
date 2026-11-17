const wrap=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);
export function registerViewerRoutes(app,{config,sessions}){
  app.get('/api/v1/viewer/bootstrap',(req,res)=>res.json({features:{...config.features,
    video:config.playback.enabled&&config.features.video,audio:config.playback.enabled&&config.features.audio,tiles:false,remoteImport:config.remote.enabled},
    csrf:{headerName:'X-CSRF-TOKEN',token:req.viewerSecurity.csrf},limits:{maxFiles:100,maxUploadBytes:config.upload.maxBytes,maxPage:10000,maxAnnotationsPerPage:200,maxNoteLength:500,maxStampsPerUser:100,maxStampUploadBytes:2097152,maxCropOutputPixels:8000000},serverTime:new Date().toISOString()}));
  app.post('/api/v1/viewer/sessions',wrap(async(req,res)=>res.status(201).json(await sessions.create(req.body,req.actor))));
  app.get('/api/v1/viewer/sessions/:id',wrap(async(req,res)=>res.json(await sessions.view(req.params.id,req.actor))));
  app.patch('/api/v1/viewer/sessions/:id',wrap(async(req,res)=>res.json(await sessions.update(req.params.id,req.body||{},req.actor))));
}
