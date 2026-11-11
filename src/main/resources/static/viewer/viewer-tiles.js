import {validatePyramid,pyramidPlan} from "./viewer-core.js";

/** A bounded viewport layer in preview coordinates. Never changes editing geometry or allocates a full raster. */
export function createTileUI({state,api,onFatal,onGeometry,viewport,layer,status,message,retry}){
  let context=null,epoch=0,frame=0,running=0;
  const requests=new Map(),MAX_BYTES=16*1048576;
  const current=c=>context===c&&c.epoch===epoch&&!c.controller.signal.aborted&&state.ready&&state.current?.mediaId===c.file.mediaId&&state.current?.mediaVersion===c.file.mediaVersion&&state.current?.permissions?.view&&state.page===c.page.page;
  const label=(c,text,canRetry=false)=>{if(!current(c))return;status.hidden=false;message.textContent=text;retry.hidden=!canRetry;layer.dataset.tileState=canRetry?"failed":c.tiles.size?"ready":"loading";};
  function drop(c){for(const request of requests.values())if(request.context===c)request.controller.abort();for(const tile of c.tiles.values()){tile.image.remove();URL.revokeObjectURL(tile.url);c.bytes=Math.max(0,c.bytes-tile.bytes);}c.tiles.clear();}
  function clear(){epoch++;cancelAnimationFrame(frame);frame=0;if(context){context.controller.abort();drop(context);}context=null;layer.replaceChildren();delete layer.dataset.generation;delete layer.dataset.tileState;status.hidden=true;retry.hidden=true;}
  const wait=(ms,signal)=>new Promise((resolve,reject)=>{if(signal.aborted)return reject(new DOMException("Aborted","AbortError"));const abort=()=>{clearTimeout(timer);reject(new DOMException("Aborted","AbortError"));};const timer=setTimeout(()=>{signal.removeEventListener("abort",abort);resolve();},ms);signal.addEventListener("abort",abort,{once:true});});
  function problem(c,error){if(!current(c)||error.name==="AbortError")return;
    drop(c);c.failed=true;
    if([401,403].includes(error.status)){onFatal(error);return;}
    label(c,error.code==="CONVERSION_BUSY"?"변환 요청이 많습니다. 잠시 후 다시 준비해 주세요.":error.code==="TILE_QUEUE_FULL"
      ?"이미지 준비 대기열이 가득 찼습니다. 잠시 후 다시 준비해 주세요.":"미리보기를 표시하고 있습니다",true);
  }
  async function missing(c,error){
    drop(c);c.failed=true;
    try{const reply=await api(c.base+"/pyramid",{signal:c.controller.signal,redirect:"error"});
      if(!current(c))return;if(reply.mediaVersion!==c.file.mediaVersion){onFatal({status:404});return;}
      label(c,"원본 화질을 다시 준비해 주세요",true);
    }catch(check){if(current(c)){if([401,403,404].includes(check.status))onFatal(check);else problem(c,error);}}
  }
  async function prepare(c,explicitRetry=false){
    if(c.preparing)return;c.preparing=true;
    try{
      c.failed=false;label(c,"원본 화질 준비 중");
      // Only one explicit preparation per context; polls are read-only and never retry failures.
      let reply=await api(c.base+"/pyramids?retry="+explicitRetry,{method:"POST",signal:c.controller.signal,redirect:"error"});
      const started=Date.now();let delay=800;
      while(current(c)){
        if(reply.mediaVersion!==c.file.mediaVersion){onFatal({status:404});return;}
        if(reply.page!==c.page.page)throw new Error("INVALID_PYRAMID");
        if(reply.state==="ready"){
          const manifest=validatePyramid(reply,c.file,c.page);
          if(c.geometry&&(c.geometry.rasterWidth!==manifest.rasterWidth||c.geometry.rasterHeight!==manifest.rasterHeight))throw new Error("INVALID_PYRAMID");
          c.geometry=manifest;c.manifest=manifest;layer.dataset.generation=c.manifest.tileSetId;
          await onGeometry(c.page,c.file,reply,c.controller.signal,()=>current(c));
          if(current(c)){if(state.pageInfo?.originalCoordinatesAvailable)c.page={...state.pageInfo};refresh();}return;
        }
        if(["disabled","unsupported"].includes(reply.state)||reply.errorCode==="TILE_PROFILE_UNAVAILABLE"){
          label(c,"미리보기 화질로 표시 중");layer.dataset.tileState="preview";return;
        }
        if(reply.state==="failed")throw Object.assign(new Error("TILE_PREPARATION_FAILED"),{code:reply.errorCode});
        if(!["queued","running"].includes(reply.state)||Date.now()-started>180000)throw new Error("TILE_PREPARATION_TIMEOUT");
        await wait(delay,c.controller.signal);delay=Math.min(4000,Math.round(delay*1.3));
        while(document.hidden&&current(c)){if(Date.now()-started>180000)throw new Error("TILE_PREPARATION_TIMEOUT");await wait(1000,c.controller.signal);}
        if(current(c))reply=await api(c.base+"/pyramid",{signal:c.controller.signal,redirect:"error"});
      }
    }catch(error){if(current(c)){if([401,403,404].includes(error.status))onFatal(error);else problem(c,error);}}
    finally{c.preparing=false;}
  }
  function open(page,file){clear();if(!state.bootstrap?.features.tiles||file.originalMimeType!=="image/tiff")return;
    const c={epoch,file:{mediaId:file.mediaId,mediaVersion:file.mediaVersion},page:{...page},base:"/api/v1/media/"+file.mediaId+"/pages/"+page.page,
      controller:new AbortController(),tiles:new Map(),wanted:new Map(),bytes:0,manifest:null,failed:false};
    context=c;void prepare(c);
  }
  async function load(c,tile,request){let url=null,reserved=0,admitted=false;
    try{
      const path=c.manifest.tileUrlTemplate.replace("{level}",tile.z).replace("{column}",tile.column).replace("{row}",tile.row);
      const blob=await api(path,{binary:true,maxBytes:2097152,signal:request.controller.signal,redirect:"error"});
      if(!current(c)||request.controller.signal.aborted||!c.wanted.has(tile.key))return;
      if(c.bytes+blob.size>MAX_BYTES)throw new Error("TILE_MEMORY_BUDGET");c.bytes+=blob.size;reserved=blob.size;
      const bytes=new DataView(await blob.slice(0,33).arrayBuffer());
      if(bytes.byteLength<33||bytes.getUint32(0)!==0x89504e47||bytes.getUint32(4)!==0x0d0a1a0a||bytes.getUint32(8)!==13||bytes.getUint32(12)!==0x49484452
        ||bytes.getUint32(16)!==tile.width||bytes.getUint32(20)!==tile.height||bytes.getUint8(24)!==8||bytes.getUint8(25)!==6||bytes.getUint8(26)!==0||bytes.getUint8(27)!==0||bytes.getUint8(28)!==0)throw new Error("INVALID_TILE_HEADER");
      if(!current(c)||request.controller.signal.aborted)return;
      const image=new Image();url=URL.createObjectURL(blob);image.src=url;image.alt="";image.draggable=false;image.decoding="async";
      await image.decode();
      if(!current(c)||c.failed||request.controller.signal.aborted||!c.wanted.has(tile.key))return;
      if(image.naturalWidth!==tile.width||image.naturalHeight!==tile.height)throw new Error("INVALID_TILE_SIZE");
      Object.assign(image.style,{position:"absolute",left:tile.left+"px",top:tile.top+"px",width:tile.displayWidth+"px",height:tile.displayHeight+"px"});
      image.dataset.tile=tile.key;c.tiles.set(tile.key,{...tile,image,url,bytes:reserved});layer.append(image);admitted=true;
      label(c,"원본 화질 · "+c.manifest.rasterWidth+" × "+c.manifest.rasterHeight);
    }catch(error){if(current(c)&&!request.controller.signal.aborted){if(error.status===404)void missing(c,error);else problem(c,error);}}
    finally{if(!admitted){if(url)URL.revokeObjectURL(url);c.bytes=Math.max(0,c.bytes-reserved);}requests.delete(request.id);running--;pump();}
  }
  function pump(){const c=context;if(!c||!current(c)||c.failed||!c.manifest||document.hidden)return;
    for(const tile of c.wanted.values()){
      if(running>=4)break;const id=c.epoch+":"+tile.key;
      if(c.tiles.has(tile.key)||requests.has(id))continue;
      const request={id,context:c,tile,controller:new AbortController()};requests.set(id,request);running++;void load(c,tile,request);
    }
  }
  function paint(){frame=0;const c=context;if(!c||!current(c)||!c.manifest||c.failed)return;
    layer.style.filter="brightness("+state.brightness+"%)";
    if(document.hidden){drop(c);return;}
    const display={width:c.page.displayWidth,height:c.page.displayHeight};
    const plan=pyramidPlan(c.manifest,display,state.view,{width:viewport.clientWidth,height:viewport.clientHeight},Math.min(2,devicePixelRatio||1)*(state.magnifier?2:1));
    c.wanted=new Map(plan.map(tile=>[tile.key,tile]));
    for(const request of requests.values())if(request.context===c&&!c.wanted.has(request.tile.key))request.controller.abort();
    for(const [key,tile] of c.tiles)if(!c.wanted.has(key)){tile.image.remove();URL.revokeObjectURL(tile.url);c.bytes=Math.max(0,c.bytes-tile.bytes);c.tiles.delete(key);}
    if(plan.length&&!c.tiles.size)label(c,"원본 화질 불러오는 중");
    pump();
  }
  function refresh(){if(context&&state.ready&&state.current?.mediaId===context.file.mediaId&&(state.current.mediaVersion!==context.file.mediaVersion||!state.current.permissions?.view)){onFatal({status:404});return;}if(frame)cancelAnimationFrame(frame);frame=requestAnimationFrame(paint);}
  function pixelScale(){return context?.geometry?context.geometry.rasterWidth/context.page.displayWidth:1;}
  function paintLens(canvas,pixel){const c=context;if(!c||!current(c))return;for(const tile of c.tiles.values())canvas.drawImage(tile.image,tile.left-pixel.x,tile.top-pixel.y,tile.displayWidth,tile.displayHeight);}
  retry.addEventListener("click",()=>{const c=context;if(!c||!current(c)||c.preparing)return;drop(c);c.manifest=null;c.wanted.clear();c.failed=false;void prepare(c,true);});
  // Viewport editing/panning must not interpret a retry control as an image click.
  status.addEventListener("pointerdown",event=>event.stopPropagation());
  return {open,clear,refresh,pixelScale,maxScale:()=>Math.max(16,Math.min(4096,pixelScale()*16)),paintLens};
}
