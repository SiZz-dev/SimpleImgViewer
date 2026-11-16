import {randomBytes,timingSafeEqual} from 'node:crypto';
import {ApiError,requireThat} from '../../system/util/errors.mjs';
export function security(config){
  const sessions=new Map();
  const equal=(a,b)=>typeof a==='string'&&Buffer.byteLength(a)===Buffer.byteLength(b)&&timingSafeEqual(Buffer.from(a),Buffer.from(b));
  return (req,res,next)=>{
    if(!config.development)return next(new ApiError(401,'AUTHENTICATION_REQUIRED'));
    const now=Date.now();for(const [key,value] of sessions)if(value.expires<now)sessions.delete(key);
    let sid=(req.headers.cookie||'').split(';').map(s=>s.trim()).find(s=>s.startsWith('siv-session='))?.slice(12);
    let session=sessions.get(sid);
    if(!session){if(sessions.size>=1000)return next(new ApiError(429,'SESSION_LIMIT'));sid=randomBytes(32).toString('hex');session={csrf:randomBytes(32).toString('hex'),expires:now+1800000};sessions.set(sid,session);
      res.cookie('siv-session',sid,{httpOnly:true,sameSite:'strict',secure:!config.development,maxAge:1800000,path:'/'});}
    req.actor={tenantId:'development',userId:'dev-admin'};req.viewerSecurity=session;
    try{
      if(!['GET','HEAD','OPTIONS'].includes(req.method)){
        requireThat(equal(req.headers['x-csrf-token'],session.csrf),403,'CSRF_INVALID');
        const origin=req.headers.origin;if(origin)requireThat(new URL(origin).host===req.headers.host,403,'ORIGIN_REJECTED');
      }
      next();
    }catch(e){next(e);}
  };
}
