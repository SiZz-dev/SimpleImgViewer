import {createTileUI} from "./viewer-tiles.js";
import {createPlayerUI} from "./viewer-player.js";
import {createExportUI} from "./viewer-export.js";
import {createCropUI} from "./viewer-crop.js";
import {createEditingUI} from "./viewer-editing.js";
import {UUID,clamp,fitScale,screenToImage,anchoredZoom,sourceUrl,thumbnailUrl,toOriginal,formatBytes,validatePyramid} from "./viewer-core.js";

const $=id=>document.getElementById(id);
const ui=Object.fromEntries(["app","viewport","surface","image","lens","file-list","file-input","file-name",
  "file-kind","file-count","list-empty","stage-message","message-title","message-body","message-icon","retry",
  "stage-note","stage-caption","image-size","page-navigation","page","previous-page","next-page",
  "zoom-in","zoom-out","actual-size","fit","rotate-left","rotate-right","magnify","brightness",
  "brightness-value","brightness-reset","info-format","info-bytes","info-original","info-preview",
  "info-orientation","ready-badge","open-files","theme","toggle-list","toggle-tools","toast"].map(id=>[id,$(id)]));
let savedTheme=null;
try { savedTheme=localStorage.getItem("imagegate.theme"); } catch {}
const state={theme:savedTheme==="light"?"light":"dark",leftOpen:true,toolsOpen:innerWidth>900,
  bootstrap:null,session:null,media:[],current:null,page:1,pageInfo:null,ready:false,imageUrl:null,
  view:{scale:1,angle:0,panX:0,panY:0,fit:true},brightness:100,magnifier:false,load:0,
  controller:null,sessionTimer:null,expiryTimer:null,observer:null,pendingOpen:null,busy:false};
let editor,cropper,exporter,player,tiles;
class ApiError extends Error {
  constructor(status,code){super(code||"REQUEST_FAILED");this.status=status;this.code=code;}
}
const errorText=error=>{
  if(error.status===401||error.status===403)return "업무시스템 로그인 상태와 접근 권한을 확인해 주세요.";
  if(error.status===404)return "파일 또는 뷰어 세션을 열 수 없습니다. 접근 권한이나 만료 여부를 확인해 주세요.";
    if(error.status===429&&error.code==="PREVIEW_QUEUE_FULL")return "이미지 준비 대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요.";
  if(error.status===429)return "현재 처리 중인 요청이 많습니다. 잠시 후 다시 시도해 주세요.";
  if(error.status===413)return "파일 크기가 허용 범위를 초과했습니다.";
  if(error.code==="TENANT_STORAGE_QUOTA_EXCEEDED")return "조직의 원본 저장 용량 한도에 도달했습니다. 관리자에게 문의해 주세요.";
  if(error.status===507)return "저장 공간이 부족합니다. 관리자에게 문의해 주세요.";
  if(error.status===415)return "지원하지 않는 파일 형식입니다.";
  if(error.status===422)return "파일을 읽을 수 없습니다. 파일이 손상되었는지 확인해 주세요.";
  if(error.status===409)return "파일 상태가 변경되었습니다. 다시 열어 주세요.";
  return "요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.";
};
async function api(path,{method="GET",json,body,signal,binary=false,maxBytes=16777216,redirect="follow"}={}){
  if(!path.startsWith("/api/v1/"))throw new Error("INVALID_API_PATH");
  const headers=new Headers();
  if(method!=="GET"){
    const csrf=state.bootstrap?.csrf;
    if(!csrf||!/^[A-Za-z0-9-]{1,80}$/.test(csrf.headerName))throw new ApiError(403);
    headers.set(csrf.headerName,csrf.token);
  }
  if(json!==undefined){headers.set("Content-Type","application/json");body=JSON.stringify(json);}
  const response=await fetch(path,{method,body,signal,headers,credentials:"same-origin",cache:"no-store",redirect});
  if(!response.ok){
    let code;try{code=(await response.json()).code;}catch{}
    throw new ApiError(response.status,code);
  }
  if(binary){
    if(response.headers.get("Content-Type")?.split(";")[0]!=="image/png")throw new Error("INVALID_CROP");
    const length=Number(response.headers.get("Content-Length"));
    if(length>maxBytes){await response.body?.cancel();throw new Error("INVALID_CROP");}
    const reader=response.body.getReader(),chunks=[];let size=0;
    try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;
      if(size>maxBytes){await reader.cancel();throw new Error("INVALID_CROP");}chunks.push(value);}}
    finally{reader.releaseLock();}
    if(size<1)throw new Error("INVALID_CROP");
    return new Blob(chunks,{type:"image/png"});
  }
  return response.status===204?null:response.json();
}
function toast(text){
  ui.toast.textContent=text;ui.toast.hidden=false;clearTimeout(toast.timer);
  toast.timer=setTimeout(()=>ui.toast.hidden=true,4800);
}
function message(title,body,retry=false,loading=false){
  ui["stage-message"].hidden=false;ui["stage-message"].classList.toggle("loading",loading);
  ui["message-title"].textContent=title;ui["message-body"].textContent=body;ui.retry.hidden=!retry;
}
function setTheme(theme){
  state.theme=theme;document.documentElement.dataset.theme=theme;
  const text=theme==="dark"?"라이트 테마로 전환":"다크 테마로 전환";
  ui.theme.setAttribute("aria-label",text);ui.theme.title=text;ui.theme.textContent=theme==="dark"?"☼":"☾";
}
function panels(){
  ui.app.classList.toggle("list-collapsed",!state.leftOpen);
  ui.app.classList.toggle("tools-collapsed",!state.toolsOpen);
  for(const [id,open,label] of [["toggle-list",state.leftOpen,"파일 목록"],["toggle-tools",state.toolsOpen,"도구 패널"]]){
    ui[id].setAttribute("aria-expanded",String(open));ui[id].setAttribute("aria-label",label+(open?" 접기":" 펼치기"));
    ui[id].title=label+(open?" 접기":" 펼치기");
  }
  requestAnimationFrame(()=>{if(state.ready&&state.view.fit)fitView();});
}
function releaseImage(){
  tiles?.clear();editor?.clear();cropper?.clear();exporter?.clear();player?.clear();
  state.ready=false;ui.surface.hidden=true;ui.image.removeAttribute("src");ui.image.alt="";
  if(state.imageUrl)URL.revokeObjectURL(state.imageUrl);state.imageUrl=null;
  ui.viewport.classList.remove("has-image","magnifying","dragging");ui.lens.hidden=true;
  ui["ready-badge"].textContent="보기 준비 완료";
  ui["stage-note"].hidden=true;ui["ready-badge"].hidden=true;state.magnifier=false;
  ui.magnify.setAttribute("aria-pressed","false");updateTools();
}
function clearSession(){
  state.controller?.abort();state.load++;releaseImage();editor?.clear({forget:true});
  clearInterval(state.sessionTimer);clearTimeout(state.expiryTimer);state.session=null;state.current=null;
  state.media=[];state.pageInfo=null;state.pendingOpen=null;state.observer?.disconnect();ui["file-list"].replaceChildren();
  ui["file-count"].textContent="0";ui["list-empty"].hidden=false;ui["file-name"].textContent="미디어 뷰어";
  ui["file-kind"].hidden=true;ui["page-navigation"].hidden=true;ui["image-size"].textContent="";
  for(const id of ["info-format","info-bytes","info-original","info-preview","info-orientation"])ui[id].textContent="—";
  try{history.replaceState(null,"","/viewer");}catch{}
}
function fail(error){
  if(error.name==="AbortError")return;
  if(error.code==="INVALID_VIEWER_POSITION"){clearSession();message("재생 시작 위치를 확인해 주세요","파일의 재생 시간 범위를 벗어났습니다. 업무시스템에서 다시 열어 주세요.");return;}
  if([401,403,404].includes(error.status)){
    clearSession();message(error.status===404?"파일을 열 수 없습니다":"로그인이 필요합니다",errorText(error),true);
    if(error.status!==404){state.bootstrap=null;ui["open-files"].disabled=true;}
  } else {releaseImage();message("파일을 표시할 수 없습니다",errorText(error),true);}
}
function updateTools(){
  const f=state.bootstrap?.features||{},view=state.ready&&state.current?.permissions?.view;
  for(const id of ["zoom-in","zoom-out","actual-size","fit"])ui[id].disabled=!(view&&f.zoom);
  for(const id of ["rotate-left","rotate-right"])ui[id].disabled=!(view&&f.rotation);
  ui.magnify.disabled=!(view&&f.magnifier);
  editor?.refresh();cropper?.refresh();exporter?.refresh();player?.refresh();
  ui.brightness.disabled=!(view&&f.brightness);ui["brightness-reset"].disabled=ui.brightness.disabled;tiles?.refresh();
}
function info(){
  const file=state.current,page=state.pageInfo;
  ui["info-format"].textContent=file?file.originalMimeType:"—";
  ui["info-bytes"].textContent=file?formatBytes(file.sizeBytes):"—";
  ui["info-original"].textContent=page?.originalCoordinatesAvailable?page.encodedWidth+" × "+page.encodedHeight:"확인 전";
  ui["info-preview"].textContent=page?.source?page.displayWidth+" × "+page.displayHeight:"—";
  ui["info-orientation"].textContent=page?.originalCoordinatesAvailable?(page.exifOrientation===1?"기본 방향":"자동 보정됨"):"확인 전";
  ui["image-size"].textContent=page?.source?page.displayWidth+" × "+page.displayHeight+" · 미리보기":"";
}
function renderList(){
  state.observer?.disconnect();ui["file-list"].replaceChildren();
  state.observer=new IntersectionObserver(entries=>{
    // Drop off-screen image references before admitting visible thumbnails.
    for(const e of entries)if(!e.isIntersecting)e.target.removeAttribute("src");
    for(const e of entries)if(e.isIntersecting&&e.target.dataset.source)e.target.src=e.target.dataset.source;
  },{root:ui["file-list"],rootMargin:"40px"});
  state.media.forEach((file,index)=>{
    const button=document.createElement("button");button.className="file-row";button.dataset.id=file.mediaId;
    button.type="button";button.setAttribute("aria-label",file.fileName+" 열기");
    const thumb=document.createElement("span");thumb.className="file-thumb";
    const symbol=document.createElement("span");symbol.textContent=file.mediaType==="image"?"▧":file.mediaType==="video"?"▷":"♫";
    const image=document.createElement("img");image.alt="";image.decoding="async";image.loading="lazy";image.fetchPriority="low";
    image.addEventListener("error",()=>image.removeAttribute("src"));
    const number=document.createElement("span");number.className="file-index";number.textContent=String(index+1).padStart(2,"0");
    thumb.append(symbol,image,number);
    const description=document.createElement("span");description.className="file-description";
    const name=document.createElement("strong");name.textContent=file.fileName;name.title=file.fileName;
    const detail=document.createElement("small");detail.textContent=(file.fileName.split(".").pop()||"FILE").toUpperCase()+" · "+formatBytes(file.sizeBytes);
    const marker=document.createElement("span");marker.className="file-state";marker.textContent=file.mediaType==="image"?"이미지":file.mediaType==="video"?"동영상":"음성";
    description.append(name,detail,marker);button.append(thumb,description);
    button.addEventListener("click",()=>select(file.mediaId,1));
    ui["file-list"].append(button);state.observer.observe(image);hydrateThumb(file);
  });
  ui["file-count"].textContent=String(state.media.length);ui["list-empty"].hidden=state.media.length>0;selectRow();
}
function hydrateThumb(file){
  const row=[...ui["file-list"].children].find(row=>row.dataset.id===file.mediaId);
  const img=row?.querySelector("img"),source=file.sources?.find(s=>s.page===1&&s.role==="thumbnail");
  // List entries use only dedicated small derivatives, loaded when visible.
  if(!img||!source)return;
  try{const url=thumbnailUrl(source,file.mediaId,1);if(img.dataset.source!==url){img.dataset.source=url;state.observer.unobserve(img);state.observer.observe(img);}}catch{}
}
function selectRow(){
  for(const row of ui["file-list"].children){
    const selected=row.dataset.id===state.current?.mediaId;row.classList.toggle("selected",selected);
    row.setAttribute("aria-current",selected?"true":"false");
  }
}
function draw(){
  if(!state.ready)return;const v=state.view;
  ui.surface.style.transform="translate(-50%,-50%) translate("+v.panX+"px,"+v.panY+"px) rotate("+v.angle+"deg) scale("+v.scale+")";
  ui.image.style.filter="brightness("+state.brightness+"%)";
  ui["actual-size"].textContent=Math.round(v.scale/(tiles?.pixelScale()||1)*100)+"%";ui.surface.dataset.rotation=String(v.angle);exporter?.refresh();tiles?.refresh();
}
function fitView(){
  if(!state.ready)return;
  state.view.scale=fitScale(state.pageInfo.displayWidth,state.pageInfo.displayHeight,
    ui.viewport.clientWidth,ui.viewport.clientHeight,state.view.angle);
  state.view.panX=0;state.view.panY=0;state.view.fit=true;draw();
}
function zoom(factor,point=null,absolute=null){
  if(ui["zoom-in"].disabled)return;
  const box=ui.viewport.getBoundingClientRect();
  state.view=anchoredZoom(state.view,absolute??state.view.scale*factor,
    point||{x:box.width/2,y:box.height/2},box,tiles?.maxScale()||16);state.view.fit=false;draw();ui.lens.hidden=true;
}
function rotate(degrees){
  if(ui["rotate-right"].disabled)return;
  state.view.angle=(state.view.angle+degrees+360)%360;
  state.view.fit?fitView():draw();ui.lens.hidden=true;
}
function sleep(ms,signal){
  return new Promise((resolve,reject)=>{
    if(signal.aborted)return reject(new DOMException("Aborted","AbortError"));
    const abort=()=>{clearTimeout(timer);reject(new DOMException("Aborted","AbortError"));};
    const timer=setTimeout(()=>{signal.removeEventListener("abort",abort);resolve();},ms);
    signal.addEventListener("abort",abort,{once:true});
  });
}
async function showSource(page,file,signal,load){
  const path=sourceUrl(page.source,file.mediaId,state.page);
  const response=await fetch(path,{credentials:"same-origin",cache:"no-store",signal});
  if(!response.ok)throw new ApiError(response.status);
  if(response.headers.get("Content-Type")?.split(";")[0]!=="image/png")throw new Error("INVALID_SOURCE");
  const blob=await response.blob();if(blob.size>32*1048576)throw new Error("INVALID_SOURCE");
  const url=URL.createObjectURL(blob);
  try{
    const image=new Image();image.src=url;await image.decode();
    if(signal.aborted||load!==state.load){URL.revokeObjectURL(url);return;}
    if(image.naturalWidth!==page.displayWidth||image.naturalHeight!==page.displayHeight)throw new Error("INVALID_SOURCE");
    state.imageUrl=url;state.ready=true;ui.image.src=url;ui.image.alt=file.fileName;
    ui.surface.style.width=page.displayWidth+"px";ui.surface.style.height=page.displayHeight+"px";
    ui.surface.hidden=false;ui["stage-message"].hidden=true;ui["stage-note"].hidden=false;
    ui["ready-badge"].hidden=false;ui.viewport.classList.add("has-image");updateTools();fitView();
    if(state.page===1){file.sources=[page.source,...(page.thumbnail?[page.thumbnail]:[])];hydrateThumb(file);}
    const editing=editor?.load(page,file,signal);
    if(!signal.aborted&&load===state.load)tiles?.open(page,file);
    await editing;
  }catch(error){URL.revokeObjectURL(url);throw error;}
}
async function select(id,page=1,retry=false,initialTime=0){
  const file=state.media.find(m=>m.mediaId===id);if(!file)return;
  state.controller?.abort();state.controller=new AbortController();const signal=state.controller.signal,load=++state.load;
  releaseImage();state.current=file;state.page=page;state.pageInfo=null;state.brightness=100;
  ui.brightness.value="100";ui["brightness-value"].textContent="100%";
  state.view={scale:1,angle:0,panX:0,panY:0,fit:true};
  ui["file-name"].textContent=file.fileName;ui["file-kind"].textContent=file.mediaType.toUpperCase();
  ui["file-kind"].hidden=false;ui.page.value=String(page);ui["previous-page"].disabled=page<=1;
  ui["next-page"].disabled=page>=state.bootstrap.limits.maxPage;ui["page-navigation"].hidden=file.mediaType!=="image";
  selectRow();info();
  if(file.mediaType!=="image"){
    try{await player.open(file,signal,retry,initialTime);}catch(error){if(load===state.load)fail(error);}
    return;
  }
  try{
    message("이미지를 준비하고 있습니다","검증된 미리보기를 불러오고 있습니다.",false,true);
    await api("/api/v1/media/"+id+"/previews?page="+page+"&retry="+retry,{method:"POST",signal});
    const started=Date.now();let delay=1000;
    while(!signal.aborted){
      const result=await api("/api/v1/media/"+id+"/pages/"+page,{signal});
      if(signal.aborted||load!==state.load)return;
      state.pageInfo=result;info();
      if(result.state==="ready"){await showSource(result,file,signal,load);return;}
      if(result.state==="failed"){
        const failures={
          CONVERSION_UNSUPPORTED_PROFILE:["지원하지 않는 이미지 구성입니다","현재 변환 기능에서 지원하지 않는 색상·비트 깊이 또는 이미지 구성입니다. 지원되는 사본을 사용하거나 관리자에게 문의해 주세요.",false],
          DECODER_UNAVAILABLE:["이미지 형식을 열 수 없습니다","이 이미지 형식을 읽는 기능을 사용할 수 없습니다. 관리자에게 지원 형식을 확인해 주세요.",false],
          CONVERSION_INVALID_INPUT:["이미지를 처리할 수 없습니다","파일 내용 또는 처리 범위를 확인할 수 없습니다. 원본 파일을 확인하거나 관리자에게 문의해 주세요.",false],
          CONVERSION_UNAVAILABLE:["변환 기능을 사용할 수 없습니다","관리자에게 변환 기능의 상태를 확인해 주세요.",false],
          CONVERSION_PROCESS_FAILED:["이미지 변환에 실패했습니다","변환 기능이 파일을 처리하지 못했습니다. 다시 시도해도 계속되면 원본 파일을 확인하거나 관리자에게 문의해 주세요.",true],
          ORIGINAL_OPEN_BUSY:["이미지를 표시할 수 없습니다","이미지 처리 요청이 많아 대기 시간이 초과됐습니다. 잠시 후 다시 시도해 주세요.",true],
          CONVERSION_BUSY:["이미지를 표시할 수 없습니다","변환 요청이 많습니다. 잠시 후 다시 시도해 주세요.",true]
        };
        const failure=Object.hasOwn(failures,result.errorCode)?failures[result.errorCode]
          :["이미지를 표시할 수 없습니다","이미지 처리에 실패했습니다. 다시 시도하거나 파일 상태를 확인해 주세요.",true];
        message(...failure);
        return;
      }
      if(result.state==="disabled"){message("이미지 표시가 꺼져 있습니다","시스템의 이미지 미리보기 설정을 확인해 주세요.");return;}
      if(Date.now()-started>120000){message("처리가 지연되고 있습니다","잠시 후 다시 확인해 주세요.",true);return;}
      await sleep(delay,signal);delay=Math.min(5000,Math.round(delay*1.3));
    }
  }catch(error){if(load===state.load)fail(error);}
}
function scheduleSessionChecks(){
  clearInterval(state.sessionTimer);clearTimeout(state.expiryTimer);
  const id=state.session.sessionId;
  state.expiryTimer=setTimeout(()=>{
    if(state.session?.sessionId!==id)return;
    clearSession();message("뷰어 세션이 만료되었습니다","업무시스템에서 파일을 다시 열어 주세요.",true);
  },Math.max(0,Math.min(7200000,Date.parse(state.session.expiresAt)-Date.now())));
  state.sessionTimer=setInterval(async()=>{
    if(document.hidden||state.busy||state.session?.sessionId!==id)return;
    try{
      const session=await api("/api/v1/viewer/sessions/"+id);
      if(state.session?.sessionId!==id)return;
      for(const file of session.media){
        const current=state.media.find(m=>m.mediaId===file.mediaId);
        if(current){Object.assign(current,file);hydrateThumb(current);}
      }
      updateTools();
    }catch(error){
      if(state.session?.sessionId===id&&[401,403,404,409].includes(error.status)){
        clearSession();message("뷰어를 다시 열어 주세요",errorText(error),true);
      }
    }
  },20000);
}
async function applySession(session){
  if(!UUID.test(session.sessionId)||!Array.isArray(session.media)||session.media.length<1||session.media.length>100
    ||session.media.some(file=>!UUID.test(file.mediaId)))throw new Error("INVALID_SESSION");
  clearSession();state.session=session;state.media=session.media;state.leftOpen=session.leftPanelOpen;
  if(savedTheme!=="dark"&&savedTheme!=="light")setTheme(session.theme==="light"?"light":"dark");
  panels();renderList();scheduleSessionChecks();
  history.replaceState(null,"","/viewer?sessionId="+session.sessionId);
  await select(session.selectedMediaId,session.initialPage??1,false,session.initialTimeSeconds??0);
}
async function createSession(ids){
  state.pendingOpen=ids;
  const session=await api("/api/v1/viewer/sessions",{method:"POST",json:{
    mediaIds:ids,theme:state.theme,leftPanelOpen:state.leftOpen}});
  await applySession(session);
}
async function openFiles(files){
  if(state.busy||!state.bootstrap||!files.length)return;
  if(files.length>state.bootstrap.limits.maxFiles){toast("한 번에 100개 이하의 파일을 선택해 주세요.");return;}
  if(files.some(file=>file.size>state.bootstrap.limits.maxUploadBytes)){toast("파일 하나의 크기는 100 MB 이하여야 합니다.");return;}
  state.busy=true;ui["open-files"].disabled=true;state.controller?.abort();state.load++;releaseImage();message("파일을 여는 중입니다","파일을 안전하게 등록하고 있습니다.",false,true);
  const ids=[];let failed=0,stop=null;
  try{
    for(let i=0;i<files.length;i++){
      ui["message-body"].textContent=(i+1)+" / "+files.length+" · "+files[i].name;
      const body=new FormData();body.append("file",files[i]);
      try{ids.push((await api("/api/v1/media",{method:"POST",body})).mediaId);}
      catch(error){failed++;if([401,403].includes(error.status)){stop=error;break;}}
    }
    if(stop)throw stop;
    if(!ids.length)throw new ApiError(422);
    await createSession(ids);
    if(failed)toast(ids.length+"개 파일을 열었습니다. "+failed+"개 파일은 처리하지 못했습니다.");
  }catch(error){fail(error);}
  finally{state.busy=false;ui["open-files"].disabled=!state.bootstrap;ui["file-input"].value="";}
}
async function start(){
  ui["open-files"].disabled=true;
  try{
    state.bootstrap=await api("/api/v1/viewer/bootstrap");
    const f=state.bootstrap.features,types=[];
    if(f.image)types.push(".jpg",".jpeg",".png",".gif",".bmp",".webp",".tif",".tiff",".heic",".heif",".jp2",".j2c");
    if(f.video)types.push(".mp4",".mpeg",".mpg");if(f.audio)types.push(".mp3",".wav");
    ui["file-input"].accept=types.join(",");ui["open-files"].disabled=types.length===0;
    const params=new URLSearchParams(location.search),session=params.get("sessionId"),ids=params.getAll("mediaId");
    if(session){if(!UUID.test(session))throw new ApiError(404);await applySession(await api("/api/v1/viewer/sessions/"+session));}
    else if(ids.length){if(ids.length>100||ids.some(id=>!UUID.test(id)))throw new ApiError(400);await createSession(ids);}
    else message("이미지를 더 가까이","파일 열기 버튼으로 이미지·동영상·음성 파일을 선택하세요.");
  }catch(error){fail(error);}
}
ui.theme.addEventListener("click",()=>{
  setTheme(state.theme==="dark"?"light":"dark");savedTheme=state.theme;
  try{localStorage.setItem("imagegate.theme",state.theme);}catch{}
});
ui["toggle-list"].addEventListener("click",()=>{state.leftOpen=!state.leftOpen;panels();});
ui["toggle-tools"].addEventListener("click",()=>{state.toolsOpen=!state.toolsOpen;panels();});
ui["open-files"].addEventListener("click",()=>ui["file-input"].click());
ui["file-input"].addEventListener("change",()=>openFiles([...ui["file-input"].files]));
ui["zoom-in"].addEventListener("click",()=>zoom(1.25));ui["zoom-out"].addEventListener("click",()=>zoom(.8));
ui["actual-size"].addEventListener("click",()=>zoom(1,null,tiles?.pixelScale()||1));
ui.fit.addEventListener("click",()=>{if(!ui.fit.disabled)fitView();});
ui["rotate-left"].addEventListener("click",()=>rotate(-90));ui["rotate-right"].addEventListener("click",()=>rotate(90));
ui.magnify.addEventListener("click",()=>{
  if(ui.magnify.disabled)return;state.magnifier=!state.magnifier;
  ui.magnify.setAttribute("aria-pressed",String(state.magnifier));
  ui.viewport.classList.toggle("magnifying",state.magnifier);ui.lens.hidden=true;draw();
});
ui.viewport.addEventListener("imagegate:tool",()=>{
  state.magnifier=false;ui.magnify.setAttribute("aria-pressed","false");
  ui.viewport.classList.remove("magnifying");ui.lens.hidden=true;
});
function brightness(value){
  if(ui.brightness.disabled)return;state.brightness=clamp(Number(value),25,200);
  ui.brightness.value=String(state.brightness);ui["brightness-value"].value=state.brightness+"%";draw();ui.lens.hidden=true;
}
ui.brightness.addEventListener("input",()=>brightness(ui.brightness.value));
ui["brightness-reset"].addEventListener("click",()=>brightness(100));
function navigatePage(page){
  if(!state.current||state.current.mediaType!=="image")return;
  if(!Number.isInteger(page)||page<1||page>state.bootstrap.limits.maxPage){ui.page.value=String(state.page);toast("올바른 페이지 번호를 입력해 주세요.");return;}
  select(state.current.mediaId,page);
}
ui.page.addEventListener("change",()=>navigatePage(Number(ui.page.value)));
ui["previous-page"].addEventListener("click",()=>navigatePage(state.page-1));
ui["next-page"].addEventListener("click",()=>navigatePage(state.page+1));
ui.retry.addEventListener("click",async()=>{
  try{
    if(!state.bootstrap)return await start();
    if(state.pendingOpen)return await createSession(state.pendingOpen);
    if(state.current)return await select(state.current.mediaId,state.page,true);
    message("파일을 다시 열어 주세요","파일 열기 버튼이나 업무시스템에서 파일을 선택하세요.");
  }catch(error){fail(error);}
});
let drag=null;
ui.viewport.addEventListener("pointerdown",event=>{
  if(!state.ready||event.button!==0||event.target.closest("button,input,.page-navigation")||state.magnifier)return;
  drag={x:event.clientX,y:event.clientY,panX:state.view.panX,panY:state.view.panY};
  ui.viewport.setPointerCapture(event.pointerId);ui.viewport.classList.add("dragging");state.view.fit=false;
});
function lensAt(event){
  if(!state.ready||!state.magnifier)return;
  const box=ui.viewport.getBoundingClientRect(),point={x:event.clientX-box.left,y:event.clientY-box.top};
  const pixel=screenToImage(point,state.view,box,{width:state.pageInfo.displayWidth,height:state.pageInfo.displayHeight});
  if(pixel.x<0||pixel.y<0||pixel.x>state.pageInfo.displayWidth||pixel.y>state.pageInfo.displayHeight){ui.lens.hidden=true;return;}
  const context=ui.lens.getContext("2d");context.setTransform(1,0,0,1,0,0);context.clearRect(0,0,180,180);
  context.filter="brightness("+state.brightness+"%)";context.translate(90,90);
  context.rotate(state.view.angle*Math.PI/180);context.scale(state.view.scale*2,state.view.scale*2);
  context.drawImage(ui.image,-pixel.x,-pixel.y);tiles?.paintLens(context,pixel);ui.lens.hidden=false;
  ui.lens.style.left=clamp(point.x+22,6,Math.max(6,box.width-186))+"px";
  ui.lens.style.top=clamp(point.y+22,6,Math.max(6,box.height-186))+"px";
  const original=toOriginal(pixel,state.pageInfo);
  if(original){ui.lens.dataset.originalX=original.x.toFixed(2);ui.lens.dataset.originalY=original.y.toFixed(2);}
}
ui.viewport.addEventListener("pointermove",event=>{
  if(drag){state.view.panX=drag.panX+event.clientX-drag.x;state.view.panY=drag.panY+event.clientY-drag.y;draw();}
  else lensAt(event);
});
for(const name of ["pointerup","pointercancel","lostpointercapture"])ui.viewport.addEventListener(name,()=>{
  drag=null;ui.viewport.classList.remove("dragging");
});
ui.viewport.addEventListener("pointerleave",()=>ui.lens.hidden=true);
ui.viewport.addEventListener("wheel",event=>{
  if(!state.ready||ui["zoom-in"].disabled)return;event.preventDefault();
  const box=ui.viewport.getBoundingClientRect();
  zoom(Math.exp(-clamp(event.deltaY,-600,600)*.0015),{x:event.clientX-box.left,y:event.clientY-box.top});
},{passive:false});
ui.viewport.addEventListener("dblclick",()=>{if(!ui.fit.disabled)fitView();});
document.addEventListener("keydown",event=>{
  if(event.ctrlKey||event.metaKey||event.altKey||event.target.closest("input,textarea,[contenteditable=true]"))return;
  if(event.key==="+"||event.key==="="){zoom(1.25);event.preventDefault();}
  else if(event.key==="-"){zoom(.8);event.preventDefault();}
  else if(event.key==="0"&&!ui.fit.disabled)fitView();
  else if(event.key==="1")zoom(1,null,tiles?.pixelScale()||1);
  else if(event.key.toLowerCase()==="r")rotate(90);
  else if(event.key.toLowerCase()==="l")ui.magnify.click();
  else if(["ArrowLeft","ArrowRight"].includes(event.key)&&state.current){
    const index=state.media.findIndex(m=>m.mediaId===state.current.mediaId)+(event.key==="ArrowRight"?1:-1);
    if(state.media[index])select(state.media[index].mediaId,1);
  }
});
let resizeFrame;
new ResizeObserver(()=>{
  cancelAnimationFrame(resizeFrame);resizeFrame=requestAnimationFrame(()=>{if(state.ready){if(state.view.fit)fitView();else tiles?.refresh();}});
}).observe(ui.viewport);
document.addEventListener("visibilitychange",()=>{if(document.hidden)ui.lens.hidden=true;tiles?.refresh();});
window.addEventListener("pageshow",event=>{if(event.persisted)start();});
window.addEventListener("pagehide",()=>{state.controller?.abort();releaseImage();clearInterval(state.sessionTimer);clearTimeout(state.expiryTimer);});
editor=createEditingUI({state,api,toast,onFatal:fail,viewport:ui.viewport,overlay:$("annotation-overlay")});
cropper=createCropUI({state,api,toast,onFatal:fail,viewport:ui.viewport,selection:$("crop-selection")});
exporter=createExportUI({state,api,toast,onFatal:fail,viewport:ui.viewport,editor,cropper});
player=createPlayerUI({state,api,toast,message,viewport:ui.viewport});
tiles=createTileUI({state,api,onFatal:fail,onGeometry:async(page,file,reply,signal,current)=>{
  draw();
  if(page.originalCoordinatesAvailable)return;
  const verified=await api("/api/v1/media/"+file.mediaId+"/pages/"+page.page,{signal,redirect:"error"});
  if(!current())return;
  if(verified.mediaId!==file.mediaId||verified.mediaVersion!==file.mediaVersion||verified.page!==page.page)throw new ApiError(404);
  if(!verified.originalCoordinatesAvailable)return;
  if(verified.state!=="ready"||!verified.editingReady||verified.displayWidth!==page.displayWidth||verified.displayHeight!==page.displayHeight
    ||sourceUrl(verified.source,file.mediaId,page.page)!==sourceUrl(page.source,file.mediaId,page.page))throw new Error("INVALID_SOURCE");
  validatePyramid(reply,file,verified);
  // Adopt only server page geometry. Existing verified pages never reload drafts on tile retry.
  state.pageInfo=verified;info();updateTools();await editor.load(verified,state.current,signal);
},viewport:ui.viewport,layer:$("tile-layer"),status:$("tile-status"),message:$("tile-message"),retry:$("tile-retry")});
setTheme(state.theme);panels();start();
