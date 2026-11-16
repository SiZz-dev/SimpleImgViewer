import {registerViewerRoutes} from '../../viewer/controller/routes.mjs';
import {registerMediaRoutes} from '../../media/controller/routes.mjs';
import {registerPlaybackRoutes} from '../../playback/controller/routes.mjs';
import {registerEditingRoutes} from '../../editing/controller/routes.mjs';
import express from 'express';
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import {settings} from '../config/settings.mjs';
import {ApiError,requireThat,integer} from '../util/errors.mjs';
import {security} from '../../security/service/session.mjs';
import {MediaStore} from '../../media/repository/store.mjs';
import {MediaService} from '../../media/service/media.mjs';
import {regular,detect} from '../../media/util/files.mjs';
import {ImageEngine} from '../../conversion/service/image-engine.mjs';
import {PlaybackService} from '../../playback/service/ffmpeg.mjs';
import {EditingService} from '../../editing/service/editing.mjs';
import {ViewerSessions} from '../../viewer/service/sessions.mjs';
import {ManagedRuntimes} from '../../conversion/service/managed-runtimes.mjs';
import {createLpacTransports} from '../../conversion/service/lpac-factory.mjs';

export async function createApplication(options={}){
  const config=await settings(options);const app=express();app.disable('x-powered-by');app.set('query parser','simple');
  const dist=path.resolve('dist');requireThat(!config.storage.root.startsWith(dist+path.sep)&&config.storage.root!==dist,500,'UNSAFE_STORAGE');
  const store=new MediaStore(config);await store.init();
  let images,playback,runtimes,transports;
  try{
  if(config.runtimeVersions.enabled)runtimes=await new ManagedRuntimes(config.runtimeVersions).init({playback:config.playback.enabled});
  transports=await createLpacTransports(config,runtimes);
  images=new ImageEngine(config,runtimes,transports.image);playback=new PlaybackService(config,store,runtimes,transports.media);await playback.init();
  const media=new MediaService(store,images,playback),editing=new EditingService(store,media),sessions=new ViewerSessions(store,media);
  await sessions.cleanupExpired();
  let uploads=0;const admission=async action=>{requireThat(uploads<2,429,'UPLOAD_BUSY');uploads++;try{return await action();}finally{uploads--;}};
  app.use((req,res,next)=>{
    res.set({'X-Content-Type-Options':'nosniff','X-Frame-Options':'SAMEORIGIN','Referrer-Policy':'no-referrer','Cache-Control':'no-store',
      'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'self'"});next();
  });
  app.use((req,_res,next)=>{
    requireThat(typeof req.headers.host==='string'&&/^[a-z0-9.:[\]-]+$/i.test(req.headers.host),400,'INVALID_HOST');
    const host=new URL('http://'+req.headers.host).hostname.toLowerCase();requireThat(config.server.allowedHosts.includes(host),403,'HOST_NOT_ALLOWED');next();
  });
  app.get('/actuator/health',(_req,res)=>res.json({status:'UP'}));
  const authenticate=security(config);app.use('/api',authenticate,express.json({limit:'64kb',strict:true}),(req,_res,next)=>{
    if(req.is('application/json'))requireThat(req.body&&typeof req.body==='object'&&!Array.isArray(req.body),400,'INVALID_REQUEST');next();
  });
  const wrap=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);
  registerViewerRoutes(app,{config,sessions});
  registerMediaRoutes(app,{store,media,admission});
  registerPlaybackRoutes(app,{store,playback,config});
  registerEditingRoutes(app,{store,editing,admission});
  app.use('/viewer/resources/TestFiles',authenticate,wrap(async(req,res)=>{
    const name=decodeURIComponent(req.path);requireThat(config.development&&['GET','HEAD'].includes(req.method)&&/^\/[\p{L}\p{N}][\p{L}\p{N} _.\-]{0,127}$/u.test(name),404,'RESOURCE_NOT_FOUND');
    const file=path.resolve('.runtime/development/url-files/TestFiles','.'+name);const stat=await regular(file,config.upload.maxBytes);const handle=await fs.open(file,'r');const head=Buffer.alloc(Math.min(512,stat.size));try{await handle.read(head,0,head.length,0);}finally{await handle.close();}
    const type=detect(head);requireThat(type,415,'UNSUPPORTED_MEDIA');res.type(type[1]).sendFile(file,{dotfiles:'allow'});
  }));
  app.get('/',(_req,res)=>res.redirect('/viewer'));
  app.use('/viewer',express.static(dist,{index:'index.html',dotfiles:'deny',fallthrough:true}));
  app.use((req,res)=>res.status(404).json({code:'NOT_FOUND'}));
  app.use((error,_req,res,_next)=>{if(res.headersSent)return res.destroy();const status=error instanceof ApiError?error.status:error.type==='entity.too.large'?413:error.code==='ENOENT'||error.statusCode===404?404:error instanceof SyntaxError?400:500;res.status(status).type('application/json').json({code:error instanceof ApiError?error.code:status===413?'REQUEST_TOO_LARGE':status===404?'NOT_FOUND':status===400?'INVALID_REQUEST':'INTERNAL_ERROR'});});
  const server=http.createServer({maxHeaderSize:16384},app);server.requestTimeout=30000;server.headersTimeout=15000;server.keepAliveTimeout=5000;
  return {app,server,config,store,media,editing,playback,images,runtimes,async listen(){await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(config.server.port,config.server.host,resolve);});return server.address();},async close(){const stopped=new Promise(resolve=>server.close(resolve));server.closeAllConnections();await Promise.all([images.close(),playback.close(),stopped]);await runtimes?.close();await store.close();}};
  }catch(error){await Promise.all([images?.close(),playback?.close(),transports?.image?.close(),transports?.media?.close()]);await runtimes?.close();await store.close();throw error;}
}
