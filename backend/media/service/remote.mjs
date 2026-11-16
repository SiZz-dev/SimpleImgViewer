import {isIP} from 'node:net';
import {createWriteStream} from 'node:fs';
import {Readable,Transform} from 'node:stream';
import {pipeline} from 'node:stream/promises';
import {ApiError,requireThat} from '../../system/util/errors.mjs';
export function remoteUrl(value,prefixes){
  requireThat(typeof value==='string'&&value.length<=8192&&!/[\x00-\x20\x7f\\]/.test(value),400,'URL_NOT_ALLOWED');
  let url;try{url=new URL(value);}catch{throw new ApiError(400,'URL_NOT_ALLOWED');}
  const host=url.hostname.replace(/^\[|\]$/g,'');const raw=value.match(/^https?:\/\/([^/]+)(\/[^?#]*)/i);
  requireThat(['http:','https:'].includes(url.protocol)&&isIP(host)&&!url.username&&!url.password&&!url.hash&&raw
    &&!/(?:^|\/)\.{1,2}(?:\/|$)|%(?:25|2f|5c|2e|0[0-9a-f]|1[0-9a-f]|7f)/i.test(raw[2])&&!raw[2].includes('//')
    &&!/%(?:0[0-9a-f]|1[0-9a-f]|7f)/i.test(url.search),400,'URL_NOT_ALLOWED');
  requireThat(!/^(?:0\.|169\.254\.|(?:22[4-9]|23\d|24\d|25[0-5])\.)/.test(host)&&!['::','0:0:0:0:0:0:0:0'].includes(host)&&!/^fe[89ab]|^ff/i.test(host),400,'URL_NOT_ALLOWED');
  const authority=raw[1].toLowerCase();requireThat(authority===url.host||authority===url.host+':'+(url.protocol==='http:'?'80':'443'),400,'URL_NOT_ALLOWED');
  requireThat(prefixes.some(prefix=>{try{const p=new URL(prefix);return p.pathname.endsWith('/')&&p.origin===url.origin&&url.pathname.startsWith(p.pathname)&&!url.pathname.endsWith('/');}catch{return false;}}),400,'URL_NOT_ALLOWED');return url;
}
export async function importRemote(body,store,actor){
  requireThat(store.config.remote.enabled,403,'URL_IMPORT_DISABLED');const url=remoteUrl(body.url,store.config.remote.allowedPrefixes);const temp=await store.temp();let response;
  try{
    response=await fetch(url,{redirect:'manual',headers:{'Accept-Encoding':'identity'},signal:AbortSignal.timeout(store.config.remote.timeoutMs)});
    requireThat(response.status===200&&(!response.headers.get('content-encoding')||response.headers.get('content-encoding')==='identity'),502,'URL_FETCH_FAILED');
    const declared=response.headers.get('content-length');requireThat(!declared||/^\d+$/.test(declared)&&Number(declared)<=store.config.upload.maxBytes,413,'FILE_TOO_LARGE');
    let size=0;const bound=new Transform({transform(chunk,_encoding,done){size+=chunk.length;done(size>store.config.upload.maxBytes?new ApiError(413,'FILE_TOO_LARGE'):null,chunk);}});
    await pipeline(Readable.fromWeb(response.body),bound,createWriteStream(temp,{flags:'wx',mode:0o600}));
    return await store.register(temp,body.fileName||decodeURIComponent(url.pathname.split('/').pop()),actor);
  }catch(e){if(e instanceof ApiError)throw e;throw new ApiError(e.name==='TimeoutError'||e.name==='AbortError'?504:502,e.name==='TimeoutError'||e.name==='AbortError'?'URL_FETCH_TIMEOUT':'URL_FETCH_FAILED');}
  finally{await response?.body?.cancel().catch(()=>{});await store.releaseTemp(temp);}
}
