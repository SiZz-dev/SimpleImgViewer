import assert from "node:assert/strict";
import {readFile,mkdir,writeFile} from "node:fs/promises";
import {createServer} from "node:http";
import {createRequire} from "node:module";
import {fileURLToPath,pathToFileURL} from "node:url";
import path from "node:path";

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),"..");
const deps=process.env.IMAGEGATE_NODE_MODULES;
if(!deps)throw new Error("Set IMAGEGATE_NODE_MODULES to a node_modules directory containing playwright.");
const require=createRequire(pathToFileURL(path.join(deps,"package.json")));
const {chromium}=require("playwright");
const coreText=await readFile(path.join(root,"src/main/resources/static/viewer/viewer-core.js"),"utf8");
const core=await import("data:text/javascript;base64,"+Buffer.from(coreText).toString("base64"));
const pyramidFile={mediaId:"10000000-0000-4000-8000-000000000001",mediaVersion:"v1"};
const pyramidPage={mediaId:pyramidFile.mediaId,mediaVersion:"v1",page:1,displayWidth:513,displayHeight:257,originalCoordinatesAvailable:false};
const pyramidReply={state:"ready",page:1,mediaVersion:"v1",pyramid:{schemaVersion:1,tileSetId:"40000000-0000-4000-8000-000000000001",mimeType:"image/png",rasterWidth:513,rasterHeight:257,tileSize:256,tileOverlap:0,minLevel:0,maxLevel:10,coordinateSpaceId:"original:v1:1:upright-v1",rasterToOriginal:{a:1,b:0,c:0,d:1,e:0,f:0},tileUrlTemplate:"/api/v1/media/"+pyramidFile.mediaId+"/pages/1/pyramids/40000000-0000-4000-8000-000000000001/tiles/{level}/{column}/{row}"}};
const pyramid=core.validatePyramid(pyramidReply,pyramidFile,pyramidPage);
assert.deepEqual(core.pyramidTile(pyramid,{width:513,height:257},10,2,1),{key:"10:2:1",z:10,column:2,row:1,width:1,height:1,left:512,top:256,displayWidth:1,displayHeight:1});
for(const change of [{tileUrlTemplate:"https://example.invalid/tile"},{rasterWidth:1024},{tileOverlap:1},{rasterToOriginal:{a:1,b:0,c:0,d:1,e:1,f:0}},{maxLevel:11}])assert.throws(()=>core.validatePyramid({...pyramidReply,pyramid:{...pyramidReply.pyramid,...change}},pyramidFile,pyramidPage));
assert.throws(()=>core.validatePyramid({...pyramidReply,mediaVersion:"old"},pyramidFile,pyramidPage));
for(const angle of [0,90,180,270])for(const scale of [.2,1,10]){const plan=core.pyramidPlan(pyramid,{width:513,height:257},{scale,angle,panX:0,panY:0},{width:900,height:700},2);assert.ok(plan.length<=48);for(const tile of plan)assert.ok(tile.width>0&&tile.height>0&&tile.left+tile.displayWidth<=513+1e-7&&tile.top+tile.displayHeight<=257+1e-7);}
assert.deepEqual(core.pyramidPlan(pyramid,{width:513,height:257},{scale:1,angle:0,panX:10000,panY:0},{width:900,height:700}),[]);
const ids=["10000000-0000-4000-8000-000000000001","10000000-0000-4000-8000-000000000002",
  "10000000-0000-4000-8000-000000000003"],sessionId="20000000-0000-4000-8000-000000000001";
const source=(id,page=1)=>({sourceId:"abcdef"[ids.indexOf(id)]?.repeat(64)||"f".repeat(64),role:"preview",page,
  mimeType:"image/png",width:1280,height:800,
  url:"/api/v1/media/"+id+"/sources/"+("abcdef"[ids.indexOf(id)]?.repeat(64)||"f".repeat(64))+"/content?page="+page});
const thumbnail=(id,page=1)=>({...source(id,page),role:"thumbnail",width:256,height:160,url:source(id,page).url.replace("/content?","/thumbnail?")});
assert.equal(core.thumbnailUrl(thumbnail(ids[0]),ids[0],1),thumbnail(ids[0]).url);
assert.throws(()=>core.thumbnailUrl({...thumbnail(ids[0]),url:"https://example.invalid/thumbnail.png"},ids[0],1));
assert.throws(()=>core.thumbnailUrl({...thumbnail(ids[0]),width:513},ids[0],1));
assert.throws(()=>core.thumbnailUrl(source(ids[0]),ids[0],1));
assert.throws(()=>core.thumbnailUrl(thumbnail(ids[0]),ids[1],1));
assert.equal(core.fitScale(1000,500,1000,800),.91);
assert.notEqual(core.fitScale(1000,500,700,900,0),core.fitScale(1000,500,700,900,90));
const view={scale:.5,panX:17,panY:-23,angle:90},point={x:275,y:300},box={width:900,height:700},image={width:1600,height:900};
const before=core.screenToImage(point,view,box,image),zoomed=core.anchoredZoom(view,1.25,point,box);
const after=core.screenToImage(point,zoomed,box,image);
assert.ok(Math.abs(before.x-after.x)<1e-8&&Math.abs(before.y-after.y)<1e-8);
assert.deepEqual(core.toOriginal({x:10,y:20},{originalCoordinatesAvailable:true,previewToOriginal:{a:2,b:0,c:0,d:2,e:0,f:0}}),{x:20,y:40});
assert.equal(core.toOriginal({x:1,y:1},{}),null);
assert.throws(()=>core.sourceUrl({...source(ids[0]),url:"https://example.invalid/image.png"},ids[0],1));
assert.throws(()=>core.sourceUrl(source(ids[0]),ids[1],1));
const csp="default-src 'self'; img-src 'self' blob:; media-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'self'";
const assets=new Map([["/viewer","index.html"],["/viewer/viewer.js","viewer.js"],
  ["/viewer/viewer-core.js","viewer-core.js"],["/viewer/viewer-tiles.js","viewer-tiles.js"],["/viewer/viewer.css","viewer.css"],["/viewer/viewer-editing.js","viewer-editing.js"],["/viewer/viewer-crop.js","viewer-crop.js"],["/viewer/viewer-export.js","viewer-export.js"],["/viewer/viewer-player.js","viewer-player.js"]]);
const server=createServer(async(req,res)=>{
  const file=assets.get(new URL(req.url,"http://localhost").pathname);
  if(!file){res.writeHead(404);res.end();return;}
  res.setHeader("Content-Type",file.endsWith(".css")?"text/css":file.endsWith(".js")?"text/javascript":"text/html;charset=utf-8");
  res.setHeader("Content-Security-Policy",csp);res.setHeader("X-Content-Type-Options","nosniff");
  res.end(await readFile(path.join(root,"src/main/resources/static/viewer",file)));
});
await new Promise(resolve=>server.listen(0,"127.0.0.1",resolve));
const origin="http://127.0.0.1:"+server.address().port;
const browser=await chromium.launch({headless:true,executablePath:process.env.IMAGEGATE_BROWSER||undefined});
const out=path.join(root,"build/ui-tests");await mkdir(out,{recursive:true});
const scenarios=[];
try{
  const generator=await browser.newPage();
  const dataUrl=await generator.evaluate(()=>{
    const canvas=document.createElement("canvas");canvas.width=1280;canvas.height=800;const c=canvas.getContext("2d");
    c.fillStyle="#f1eee6";c.fillRect(0,0,1280,800);
    c.strokeStyle="#dcd9d0";c.lineWidth=1;
    for(let x=40;x<1280;x+=40){c.beginPath();c.moveTo(x,0);c.lineTo(x,800);c.stroke();}
    for(let y=40;y<800;y+=40){c.beginPath();c.moveTo(0,y);c.lineTo(1280,y);c.stroke();}
    c.fillStyle="#26394b";c.fillRect(68,65,9,72);c.font="600 35px Segoe UI";c.fillText("PROJECT 27",101,98);
    c.font="17px Segoe UI";c.fillStyle="#7a878d";c.fillText("SOUTH ELEVATION  /  SITE REVIEW",103,131);
    c.fillStyle="#ce7759";c.fillRect(1094,67,114,48);c.fillStyle="#fff";c.font="600 20px Segoe UI";c.fillText("REV. 02",1115,98);
    c.fillStyle="#e4e2db";c.fillRect(194,253,876,366);
    c.fillStyle="#d4d6d2";c.fillRect(207,247,70,372);c.fillRect(987,247,70,372);
    c.strokeStyle="#51636a";c.lineWidth=2;c.strokeRect(194,253,876,366);
    c.fillStyle="#334f62";
    for(let row=0;row<4;row++)for(let col=0;col<9;col++){
      const x=307+col*73,y=282+row*76;c.fillRect(x,y,45,47);
      c.fillStyle="#76949b";c.fillRect(x+4,y+4,17,39);c.fillStyle="#334f62";
    }
    c.fillStyle="#334952";c.fillRect(546,549,171,70);c.strokeStyle="#c6d2d1";c.lineWidth=2;c.beginPath();c.moveTo(631,550);c.lineTo(631,619);c.stroke();
    c.strokeStyle="#65787c";c.lineWidth=3;c.beginPath();c.moveTo(132,621);c.lineTo(1147,621);c.stroke();
    c.fillStyle="#7b927e";for(const [x,y] of [[130,567],[1129,553]]){c.beginPath();c.ellipse(x,y,40,56,0,0,Math.PI*2);c.fill();}
    c.strokeStyle="#899c8b";c.lineWidth=5;c.beginPath();c.moveTo(130,600);c.lineTo(130,640);c.moveTo(1129,587);c.lineTo(1129,640);c.stroke();
    c.strokeStyle="#b67157";c.lineWidth=2;c.setLineDash([7,6]);c.strokeRect(279,270,697,278);c.setLineDash([]);
    c.strokeStyle="#8c999b";c.lineWidth=1;c.beginPath();c.moveTo(194,679);c.lineTo(1070,679);c.moveTo(194,664);c.lineTo(194,690);c.moveTo(1070,664);c.lineTo(1070,690);c.stroke();
    c.fillStyle="#7b8589";c.font="15px Segoe UI";c.fillText("42,000",603,674);
    c.font="13px Segoe UI";c.fillText("IMAGEGATE SAMPLE · SYNTHETIC TEST IMAGE",68,744);c.fillText("A-027  /  1:200",1064,744);
    return canvas.toDataURL("image/png");
  });
  const png=Buffer.from(dataUrl.split(",")[1],"base64");
  const thumbnailData=await generator.evaluate(async data=>{
    const image=new Image();image.src=data;await image.decode();
    const canvas=document.createElement("canvas");canvas.width=256;canvas.height=160;canvas.getContext("2d").drawImage(image,0,0,256,160);return canvas.toDataURL("image/png");
  },dataUrl);
  const thumbnailPng=Buffer.from(thumbnailData.split(",")[1],"base64");
  const stampData=await generator.evaluate(()=>{
    const c=document.createElement("canvas");c.width=96;c.height=96;const g=c.getContext("2d");
    g.strokeStyle="#bc3939";g.lineWidth=5;g.beginPath();g.arc(48,48,41,0,Math.PI*2);g.stroke();
    g.fillStyle="#bc3939";g.font="bold 26px Malgun Gothic";g.fillText("확인",22,57);return c.toDataURL("image/png");
  });
  const stampPng=Buffer.from(stampData.split(",")[1],"base64");
  const cropData=await generator.evaluate(()=>{
    const canvas=document.createElement("canvas");canvas.width=240;canvas.height=160;
    const g=canvas.getContext("2d");g.fillStyle="#334f62";g.fillRect(0,0,240,160);return canvas.toDataURL("image/png");
  });
  const cropPng=Buffer.from(cropData.split(",")[1],"base64");
  const exportPng=async(width,height)=>{
    const data=await generator.evaluate(({width,height})=>{
      const canvas=document.createElement("canvas");canvas.width=width;canvas.height=height;
      const g=canvas.getContext("2d");g.fillStyle="#d7d4cb";g.fillRect(0,0,width,height);return canvas.toDataURL("image/png");
    },{width,height});
    return Buffer.from(data.split(",")[1],"base64");
  };
  const videoBytes=await readFile(path.join(root,"build/playback-fixtures/video.webm"));
  const audioBytes=await readFile(path.join(root,"build/playback-fixtures/audio.mp3"));
  const mediaDurations=await generator.evaluate(async data=>{
    const duration=async(type,text)=>{
      const element=document.createElement(type),blob=new Blob([Uint8Array.from(atob(text),c=>c.charCodeAt(0))]);
      const url=URL.createObjectURL(blob);element.src=url;
      try{return await new Promise((resolve,reject)=>{element.onloadedmetadata=()=>resolve(element.duration);element.onerror=()=>reject(new Error("Invalid native fixture"));});}
      finally{element.removeAttribute("src");element.load();URL.revokeObjectURL(url);}
    };
    return {video:await duration("video",data.video),audio:await duration("audio",data.audio)};
  },{video:videoBytes.toString("base64"),audio:audioBytes.toString("base64")});
  const features={image:true,video:true,audio:true,zoom:true,rotation:true,brightness:true,magnifier:true,
    annotations:false,stamps:false,cropAndSave:false,exportEdited:false,videoSeek:true,audioSeek:true,videoPlaybackSpeed:true,audioPlaybackSpeed:true};
  const makeFile=(id,index)=>({mediaId:id,mediaVersion:"30000000-0000-4000-8000-00000000000"+(index+1),
    fileName:["현장_외관_01.tif","평면도_B1.tif","구조_상세_03.png"][index]||"추가.png",
    mediaType:"image",originalMimeType:"image/tiff",sizeBytes:2457600,status:"ready",
    permissions:{view:true,annotate:true,stamp:true,cropAndSave:true,exportEdited:true},sources:[source(id),thumbnail(id)],preview:{state:"ready",page:1},
    statusUrl:"/api/v1/media/"+id+"/status?page=1"});
  const tileBuffers=new Map();
  async function tileBuffer(width,height){const key=width+"x"+height;if(!tileBuffers.has(key))tileBuffers.set(key,generator.evaluate(({width,height})=>{const c=document.createElement("canvas");c.width=width;c.height=height;const x=c.getContext("2d");x.fillStyle="#d6e4e8";x.fillRect(0,0,width,height);x.strokeStyle="#8caebc";x.lineWidth=1;for(let i=0;i<width;i+=16){x.beginPath();x.moveTo(i,0);x.lineTo(i,height);x.stroke();}for(let i=0;i<height;i+=16){x.beginPath();x.moveTo(0,i);x.lineTo(width,i);x.stroke();}return c.toDataURL("image/png");},{width,height}).then(data=>{const bytes=Buffer.from(data.split(",")[1],"base64");assert.equal(bytes[25],6);return bytes;}));return tileBuffers.get(key);}
  async function fixture(options={}){
    const context=await browser.newContext({viewport:options.viewport||{width:1440,height:920}});
    const scenario={revoked:false,unsafe:false,headers:[],requests:[],errors:[],files:ids.map(makeFile),
      documents:new Map(),histories:new Map(),historyRequests:[],stamps:[],itemCounter:0,conflictOnce:false,...options};
    if(options.manyFiles)for(let i=4;i<=30;i++){
      const id="60000000-0000-4000-8000-"+String(i).padStart(12,"0"),file=makeFile(id,i);
      file.mediaVersion="30000000-0000-4000-8000-"+String(i).padStart(12,"0");scenario.files.push(file);
    }
    if(options.noThumbnail)for(const file of scenario.files)file.sources=file.sources.filter(s=>s.role!=="thumbnail");
    if(options.unsafeThumbnail)for(const file of scenario.files)file.sources.find(s=>s.role==="thumbnail").url="https://example.invalid/thumbnail.png";
    scenario.tileRequests=[];scenario.tilePosts=[];scenario.previewPosts=[];scenario.activeTiles=0;scenario.maxActiveTiles=0;scenario.mediaRanges=[];
    if(options.playback){
      Object.assign(scenario.files[0],{mediaType:options.playback,fileName:options.playback==="video"?"블랙박스_01.mp4":"녹음_01.mp3",originalMimeType:options.playback==="video"?"video/mp4":"audio/mpeg",sources:[]});
      Object.assign(scenario.files[1],{mediaType:"audio",fileName:"녹음_02.wav",originalMimeType:"audio/wav",sources:[]});
    }
    if(options.xss)scenario.files[0].fileName="<img src=x onerror=window.xss=1>.png";
    if(scenario.tiles)await context.addInitScript(()=>{const objects=new Set(),create=URL.createObjectURL.bind(URL),revoke=URL.revokeObjectURL.bind(URL);window.tileObjects=objects;window.tileObjectCreated=0;window.tileFetchActive=0;window.tileFetchMax=0;window.tileDecodes=0;const fetchOriginal=window.fetch.bind(window),decodeOriginal=HTMLImageElement.prototype.decode;
      HTMLImageElement.prototype.decode=function(){window.tileDecodes++;return decodeOriginal.call(this);};window.fetch=async(...args)=>{const tile=String(args[0]).includes("/tiles/");if(tile)window.tileFetchMax=Math.max(window.tileFetchMax,++window.tileFetchActive);try{return await fetchOriginal(...args);}finally{if(tile)window.tileFetchActive--;}};URL.createObjectURL=value=>{const url=create(value);objects.add(url);window.tileObjectCreated++;return url;};URL.revokeObjectURL=url=>{objects.delete(url);revoke(url);};});
    const page=await context.newPage();page.on("pageerror",error=>scenario.errors.push(error.message));
    page.on("console",message=>{if(message.text().includes("Content Security Policy"))scenario.errors.push(message.text());});
    const historyDocument=(key,doc)=>{
      const h=scenario.histories.get(key),file=scenario.files.find(f=>key.startsWith(f.mediaId+":"));
      const current=h&&h.revision===doc.revision,undo=current&&h.cursor>0?h.steps[h.cursor-1]:null,redo=current&&h.cursor<h.steps.length?h.steps[h.cursor]:null;
      const allowed=step=>step&&scenario.features?.[step.type==="note"?"annotations":"stamps"]!==false&&file.permissions[step.type==="note"?"annotate":"stamp"];
      return {...doc,history:{enabled:!scenario.historyDisabled,canUndo:Boolean(!scenario.historyDisabled&&allowed(undo)),canRedo:Boolean(!scenario.historyDisabled&&allowed(redo)),
        undoDepth:!scenario.historyDisabled&&allowed(undo)?h.cursor:0,redoDepth:!scenario.historyDisabled&&allowed(redo)?h.steps.length-h.cursor:0,
        undoType:!scenario.historyDisabled&&allowed(undo)?undo.type:null,redoType:!scenario.historyDisabled&&allowed(redo)?redo.type:null}};
    };
    const recordHistory=(key,before,doc,type)=>{
      if(scenario.historyDisabled)return;
      let h=scenario.histories.get(key);if(!h||h.revision!==doc.revision-1)h={revision:doc.revision-1,cursor:0,steps:[]};
      h.steps.splice(h.cursor);h.steps.push({before,after:structuredClone(doc.items),type});if(h.steps.length>20)h.steps.shift();
      h.cursor=h.steps.length;h.revision=doc.revision;scenario.histories.set(key,h);
    };
    await context.route(origin+"/api/v1/**",async route=>{
      const req=route.request(),url=new URL(req.url()),method=req.method();scenario.requests.push(url.pathname+url.search);
      const respond=(body,status=200)=>route.fulfill({status,contentType:"application/json",headers:{"Cache-Control":"no-store"},body:JSON.stringify(body)});
      if(scenario.revoked)return respond({code:"MEDIA_NOT_FOUND"},404);
      if(url.pathname==="/api/v1/viewer/bootstrap")return respond({
        features:{...features,...(scenario.tiles?{tiles:true}:{}),...(scenario.editing?{annotations:true,stamps:true,stampUpload:true}:{}),...(scenario.crop?{cropAndSave:true}:{}),...(scenario.export?{exportEdited:true}:{}),...scenario.features},csrf:{headerName:"X-CSRF-TOKEN",token:"fixture-token"},
        limits:{maxFiles:100,maxUploadBytes:104857600,maxPage:10000,maxNoteLength:2000,maxStampUploadBytes:5242880,maxAnnotationsPerPage:100,maxCropOutputPixels:8000000,maxCropOutputBytes:16777216},serverTime:new Date().toISOString()
      },scenario.anonymous?401:200);
      if(method!=="GET"&&method!=="HEAD"){
        scenario.headers.push(req.headers()["x-csrf-token"]);
        if(req.headers()["x-csrf-token"]!=="fixture-token")return respond({},403);
      }
      const session=()=>({sessionId,createdAt:new Date().toISOString(),expiresAt:new Date(Date.now()+1800000).toISOString(),
        selectedMediaId:scenario.files[0].mediaId,initialPage:scenario.playback?null:1,initialTimeSeconds:scenario.playback?(scenario.initialTime??.5):null,positionState:"validated",
        theme:"dark",leftPanelOpen:true,media:scenario.files});
      if(url.pathname.startsWith("/api/v1/viewer/sessions")){
        if(method==="POST"){
          const body=req.postDataJSON();scenario.files=body.mediaIds.map(id=>scenario.files.find(f=>f.mediaId===id)||makeFile(id,3));
          return respond(session(),201);
        }
        return respond(session());
      }
      if(url.pathname==="/api/v1/media"&&method==="POST"){
        const id="10000000-0000-4000-8000-000000000004";const file=makeFile(id,3);
        scenario.files.push(file);return respond(file,201);
      }
      if(url.pathname==="/api/v1/stamps")return respond(scenario.stamps);
      if(url.pathname.match(/^\/api\/v1\/stamps\/[^/]+\/content$/))return route.fulfill({contentType:"image/png",body:stampPng});
      const match=url.pathname.match(/^\/api\/v1\/media\/([^/]+)\/(.*)$/);
      if(!match)return respond({},404);
      const [,id,tail]=match;const file=scenario.files.find(f=>f.mediaId===id);
      if(!file)return respond({},404);
      if(tail==="playback"){
        const video=file.mediaType==="video",sourceId=(video?"7":"8").repeat(64);
        return respond({mediaVersion:file.mediaVersion,sourceId,state:method==="POST"?"queued":"ready",errorCode:null,durationSeconds:mediaDurations[video?"video":"audio"],
          mimeType:video?"video/webm":"audio/mpeg",width:video?320:0,height:video?180:0,
          videoCodec:video?"vp8":null,audioCodec:video?"opus":"mp3",sizeBytes:(video?videoBytes:audioBytes).length,seekable:true,
          url:scenario.unsafePlayback?"https://example.invalid/private.webm":"/api/v1/media/"+id+"/playback/sources/"+sourceId+"/content"},method==="POST"?202:200);
      }
      if(tail.startsWith("playback/sources/")&&tail.endsWith("/content")){
        const bytes=file.mediaType==="video"?videoBytes:audioBytes,range=req.headers().range;
        scenario.mediaRanges.push(range||"full");
        let start=0,end=bytes.length-1,status=200;
        if(range){const parts=range.substring(6).split("-");start=Number(parts[0]||0);end=parts[1]?Math.min(Number(parts[1]),end):end;status=206;}
        const headers={"Cache-Control":"no-store","Accept-Ranges":"bytes","Content-Length":String(end-start+1)};
        if(status===206)headers["Content-Range"]="bytes "+start+"-"+end+"/"+bytes.length;
        return route.fulfill({status,contentType:file.mediaType==="video"?"video/webm":"audio/mpeg",headers,body:bytes.subarray(start,end+1)});
      }
      if(tail==="stamps"&&method==="POST"){
        const id="40000000-0000-4000-8000-000000000001";
        const asset={stampId:id,fileName:"확인.png",width:96,height:96,previewWidth:96,previewHeight:96,contentUrl:"/api/v1/stamps/"+id+"/content"};
        scenario.stamps.push(asset);return respond(asset,201);
      }
      if(tail.startsWith("pages/")&&tail.endsWith("/export")&&method==="POST"){
        const data=req.postDataJSON();(scenario.exportRequests||=[]).push(data);
        if(scenario.exportStatus)return respond({code:"EDIT_CONFLICT"},scenario.exportStatus);
        const bytes=await exportPng(data.rotation%180?data.height:data.width,data.rotation%180?data.width:data.height);
        if(scenario.deferExport){scenario.deferExport=false;await new Promise(resolve=>scenario.releaseExport=resolve);}
        return route.fulfill({contentType:scenario.exportMime||"image/png",headers:{"Cache-Control":"no-store"},body:bytes});
      }
      if(tail.startsWith("pages/")&&tail.endsWith("/crop")&&method==="POST"){
        const data=req.postDataJSON();(scenario.cropRequests||=[]).push(data);
        if(scenario.cropStatus)return respond({code:"EDIT_CONFLICT"},scenario.cropStatus);
        if(scenario.deferCrop){scenario.deferCrop=false;await new Promise(resolve=>scenario.releaseCrop=resolve);}
        return route.fulfill({contentType:"image/png",headers:{"Cache-Control":"no-store"},body:cropPng});
      }
      const history=tail.match(/^pages\/(\d+)\/annotations\/history\/(undo|redo)$/);
      if(history){
        const key=id+":"+Number(history[1]),doc=scenario.documents.get(key),h=scenario.histories.get(key),redo=history[2]==="redo",body=req.postDataJSON();
        scenario.historyRequests.push({key,direction:history[2],body});
        if(scenario.historyDisabled)return respond({},403);
        if(scenario.historyConflictOnce){scenario.historyConflictOnce=false;doc.revision++;return respond({code:"EDIT_CONFLICT"},409);}
        if(!doc||body.expectedRevision!==doc.revision)return respond({code:"EDIT_CONFLICT"},409);
        if(!h||h.revision!==doc.revision||(!redo&&h.cursor===0)||(redo&&h.cursor===h.steps.length))return respond({code:"EDIT_HISTORY_UNAVAILABLE"},409);
        const step=h.steps[redo?h.cursor:h.cursor-1];doc.items=structuredClone(redo?step.after:step.before);doc.revision++;h.cursor+=redo?1:-1;h.revision=doc.revision;
        const reply=structuredClone(historyDocument(key,doc));
        if(scenario.historyDelay){scenario.historyDelay=false;await new Promise(resolve=>scenario.releaseHistoryResponse=resolve);}
        if(scenario.historyLostOnce){scenario.historyLostOnce=false;return route.abort("failed");}
        return respond(reply);
      }
      const edit=tail.match(/^pages\/(\d+)\/annotations(?:\/([^/]+))?(\/stamp\/content)?$/);
      if(edit){
        const pageNumber=Number(edit[1]),itemId=edit[2],key=id+":"+pageNumber;
        if(!scenario.documents.has(key))scenario.documents.set(key,{mediaVersion:file.mediaVersion,page:pageNumber,
          coordinateSpaceId:"original:"+file.mediaVersion+":"+pageNumber+":upright-v1",revision:0,items:[]});
        const doc=scenario.documents.get(key);
        if(edit[3])return route.fulfill({contentType:"image/png",body:stampPng});
        if(method==="GET"){if(scenario.deferAnnotationRead){scenario.deferAnnotationRead=false;await new Promise(resolve=>scenario.releaseAnnotationRead=resolve);}return respond(historyDocument(key,doc));}
        const body=req.postDataJSON();
        if(scenario.conflictOnce){scenario.conflictOnce=false;doc.revision++;return respond({code:"EDIT_CONFLICT"},409);}
        if(body.expectedRevision!==doc.revision)return respond({code:"EDIT_CONFLICT"},409);
        const before=structuredClone(doc.items);
        if(method==="DELETE"){const removed=doc.items.find(item=>item.id===itemId);doc.items=doc.items.filter(item=>item.id!==itemId);doc.revision++;recordHistory(key,before,doc,removed.type);return respond(historyDocument(key,doc));}
        const nextId=itemId||"50000000-0000-4000-8000-"+String(++scenario.itemCounter).padStart(12,"0");
        const item={id:nextId,type:body.type,x:body.x,y:body.y,width:body.width,height:body.height,
          text:body.type==="note"?body.text:null,stampId:body.type==="stamp"?body.stampId:null,
          stampUrl:body.type==="stamp"?"/api/v1/media/"+id+"/pages/"+pageNumber+"/annotations/"+nextId+"/stamp/content":null};
        doc.items=doc.items.filter(item=>item.id!==nextId);doc.items.push(item);doc.revision++;recordHistory(key,before,doc,item.type);return respond(historyDocument(key,doc),method==="POST"?201:200);
      }
      const tileMeta=tail.match(/^pages\/(\d+)\/(pyramid|pyramids)$/);
      if(tileMeta){const number=Number(tileMeta[1]),generation="40000000-0000-4000-8000-"+String((scenario.files.indexOf(file)+1)*10000+number+(scenario.tileRevision||0)*1000000).padStart(12,"0");
        if(method==="POST")scenario.tilePosts.push({id,page:number,retry:url.searchParams.get("retry"),csrf:req.headers()["x-csrf-token"]});
        if(method==="POST"&&scenario.tileQueueFull)return respond({code:"TILE_QUEUE_FULL"},429);
        if(scenario.deferPyramid&&id===ids[0]&&number===1){scenario.deferPyramid=false;await new Promise(resolve=>scenario.releasePyramid=resolve);}
        const width=scenario.tileWidth||2560,height=scenario.tileHeight||1600;
        const reply={state:scenario.tileFailure||scenario.tileBusy?"failed":scenario.tileQueued&&method==="POST"?"queued":"ready",page:number,mediaVersion:scenario.tileVersion||file.mediaVersion,errorCode:scenario.tileBusy?"CONVERSION_BUSY":scenario.tileFailure?(scenario.tileFailureCode||"TILE_FAILED"):null,
          pyramid:{schemaVersion:1,tileSetId:generation,mimeType:"image/png",rasterWidth:width,rasterHeight:height,tileSize:256,tileOverlap:0,minLevel:0,maxLevel:Math.ceil(Math.log2(Math.max(width,height))),coordinateSpaceId:"original:"+file.mediaVersion+":"+number+":upright-v1",rasterToOriginal:{a:1,b:0,c:0,d:1,e:0,f:0},tileUrlTemplate:"/api/v1/media/"+id+"/pages/"+number+"/pyramids/"+generation+"/tiles/{level}/{column}/{row}"}};
        if(scenario.unsafeTiles)reply.pyramid.tileUrlTemplate="https://example.invalid/private-tile";
        try{return await respond(reply);}catch{return;}
      }
      const tileMatch=tail.match(/^pages\/(\d+)\/pyramids\/([^/]+)\/tiles\/(\d+)\/(\d+)\/(\d+)$/);
      if(tileMatch){const [,number,generation,z,c,r]=tileMatch;scenario.tileRequests.push({id,page:Number(number),generation,z:Number(z),column:Number(c),row:Number(r)});scenario.maxActiveTiles=Math.max(scenario.maxActiveTiles,++scenario.activeTiles);
        try{if(scenario.tileDelay)await new Promise(resolve=>setTimeout(resolve,scenario.tileDelay));
          if(scenario.tileStatus)return respond({code:"MEDIA_NOT_FOUND"},scenario.tileStatus);
          if(scenario.tileRedirect)return route.fulfill({status:302,headers:{location:"https://example.invalid/private-tile"}});
          const width=scenario.tileWidth||2560,height=scenario.tileHeight||1600,d=2**(Math.ceil(Math.log2(Math.max(width,height)))-Number(z)),w=Math.min(256,Math.ceil(width/d)-Number(c)*256),h=Math.min(256,Math.ceil(height/d)-Number(r)*256);
          let bytes=await tileBuffer(w,h);if(scenario.badTileHeader){bytes=Buffer.from(bytes);bytes.writeUInt32BE(40000000,16);}
          return await route.fulfill({contentType:"image/png",headers:{"Cache-Control":"no-store"},body:bytes});
        }catch{return;}finally{scenario.activeTiles--;}
      }
      if(tail==="previews"){
        if(method==="POST")scenario.previewPosts.push({retry:url.searchParams.get("retry")});
        if(scenario.previewQueueFull)return respond({code:"PREVIEW_QUEUE_FULL"},429);
        return respond({state:"ready",page:Number(url.searchParams.get("page")),source:source(id,Number(url.searchParams.get("page")))});
      }
      if(tail.startsWith("pages/")){
        const number=Number(tail.split("/")[1]);
        if(scenario.previewBusy)return respond({mediaId:id,mediaVersion:file.mediaVersion,page:number,state:"failed",errorCode:"CONVERSION_BUSY"});
        if(scenario.previewFailureCode)return respond({mediaId:id,mediaVersion:file.mediaVersion,page:number,state:"failed",errorCode:scenario.previewFailureCode,source:null,thumbnail:null,editingReady:false,originalCoordinatesAvailable:false});
        if(scenario.slow&&id===ids[1])await new Promise(resolve=>setTimeout(resolve,400));
        const src=source(id,number);if(scenario.unsafe)src.url="https://example.invalid/private.png";
        return respond({mediaId:id,mediaVersion:file.mediaVersion,page:number,state:"ready",errorCode:null,
          coordinateSpaceId:"original:"+file.mediaVersion+":"+number+":upright-v1",
          coordinateWidth:scenario.tileWidth||2560,coordinateHeight:scenario.tileHeight||1600,encodedWidth:scenario.tileWidth||2560,encodedHeight:scenario.tileHeight||1600,exifOrientation:1,
          displayWidth:1280,displayHeight:800,originalCoordinatesAvailable:!scenario.unknownGeometry||Boolean(scenario.verifyAfterTiles&&scenario.tilePosts.length),editingReady:!scenario.unknownGeometry||Boolean(scenario.verifyAfterTiles&&scenario.tilePosts.length),
          previewToOriginal:{a:(scenario.tileWidth||2560)/1280,b:0,c:0,d:(scenario.tileHeight||1600)/800,e:0,f:0},originalToEncoded:{a:1,b:0,c:0,d:1,e:0,f:0},source:src,thumbnail:scenario.noThumbnail?null:scenario.unsafeThumbnail?{...thumbnail(id,number),url:"https://example.invalid/thumbnail.png"}:thumbnail(id,number)});
      }
      if(tail.includes("/thumbnail"))return route.fulfill({contentType:"image/png",headers:{"Cache-Control":"no-store"},body:thumbnailPng});
      if(tail.includes("/content"))return route.fulfill({contentType:"image/png",headers:{"Cache-Control":"no-store"},body:png});
      return respond({},404);
    });
    await page.goto(origin+"/viewer?sessionId="+sessionId);
    return {context,page,scenario};
  }
  const {context,page,scenario}=await fixture();
  await page.locator("#surface").waitFor({state:"visible"});
  assert.equal(await page.locator(".file-row").count(),3);
  assert.equal(await page.locator("#file-name").textContent(),"현장_외관_01.tif");
  const initial=await page.locator("#actual-size").textContent();
  await page.getByRole("button",{name:"확대",exact:true}).click();
  assert.notEqual(await page.locator("#actual-size").textContent(),initial);
  await page.getByRole("button",{name:"오른쪽으로 회전",exact:true}).click();
  assert.equal(await page.locator("#surface").getAttribute("data-rotation"),"90");
  await page.locator("#brightness").evaluate(el=>{el.value="135";el.dispatchEvent(new Event("input",{bubbles:true}));});
  assert.equal(await page.locator("#image").evaluate(el=>el.style.filter),"brightness(135%)");
  const panArea=await page.locator("#viewport").boundingBox();
  await page.mouse.move(panArea.x+panArea.width/2,panArea.y+panArea.height/2);
  const wheelBefore=await page.locator("#actual-size").textContent();await page.mouse.wheel(0,-120);
  await page.waitForFunction(value=>document.querySelector("#actual-size").textContent!==value,wheelBefore);
  await page.mouse.down();await page.mouse.move(panArea.x+panArea.width/2+60,panArea.y+panArea.height/2+35);await page.mouse.up();
  assert.ok((await page.locator("#surface").getAttribute("style")).replaceAll(" ","").includes("translate(60px,35px)"));
  await page.getByRole("button",{name:"돋보기",exact:true}).click();
  const area=await page.locator("#viewport").boundingBox();await page.mouse.move(area.x+area.width/2,area.y+area.height/2);
  await page.locator("#lens").waitFor({state:"visible"});
  assert.ok(Number(await page.locator("#lens").getAttribute("data-original-x"))>0);
  await page.getByRole("button",{name:"돋보기",exact:true}).click();
  const beforeCollapse=await page.locator("#viewport").boundingBox();
  await page.getByRole("button",{name:"파일 목록 접기",exact:true}).click();
  assert.ok((await page.locator("#viewport").boundingBox()).width>beforeCollapse.width+100);
  assert.equal(await page.locator("#toggle-list").getAttribute("aria-expanded"),"false");
  await page.getByRole("button",{name:"파일 목록 펼치기",exact:true}).click();
  await page.getByRole("button",{name:"오른쪽으로 회전",exact:true}).click();
  await page.getByRole("button",{name:"오른쪽으로 회전",exact:true}).click();
  await page.getByRole("button",{name:"오른쪽으로 회전",exact:true}).click();
  await page.locator("#brightness-reset").click();await page.locator("#fit").click();
  await page.screenshot({path:path.join(out,"viewer-dark.png")});
  await page.getByRole("button",{name:"라이트 테마로 전환",exact:true}).click();
  assert.equal(await page.locator("html").getAttribute("data-theme"),"light");
  await page.waitForTimeout(200);
  await page.screenshot({path:path.join(out,"viewer-light.png")});
  await page.reload();await page.locator("#surface").waitFor({state:"visible"});
  assert.equal(await page.locator("html").getAttribute("data-theme"),"light");
  await page.locator("#next-page").click();
  await page.waitForFunction(()=>document.querySelector("#page").value==="2"&&!document.querySelector("#surface").hidden);
  assert.ok(scenario.requests.some(url=>url.endsWith("/pages/2")));
  scenario.slow=true;
  await page.locator(".file-row").nth(1).click();await page.locator(".file-row").nth(2).click();
  await page.waitForFunction(()=>!document.querySelector("#surface").hidden);
  await page.waitForTimeout(500);
  assert.equal(await page.locator("#file-name").textContent(),"구조_상세_03.png");
  assert.equal(await page.evaluate(()=>window.xss),undefined);
  assert.equal(await page.locator("#file-list img[onerror]").count(),0);
  await page.locator("#file-input").setInputFiles({name:"추가.png",mimeType:"image/png",buffer:png});
  await page.waitForFunction(()=>document.querySelector("#file-name").textContent==="추가.png"&&!document.querySelector("#surface").hidden);
  assert.ok(scenario.headers.length>0&&scenario.headers.every(header=>header==="fixture-token"));
  scenario.revoked=true;await page.locator("#next-page").click();
  await page.waitForFunction(()=>document.querySelector("#file-list").children.length===0);
  assert.equal(await page.locator("#image").getAttribute("src"),null);
  assert.deepEqual(scenario.errors,[]);scenarios.push("desktop controls, theme, page, stale response, XSS, CSRF upload, permission loss");
  await context.close();


  const thumbnails=await fixture({manyFiles:true});const tp=thumbnails.page;
  await tp.locator("#surface").waitFor({state:"visible"});
  await tp.waitForFunction(()=>[...document.querySelectorAll("#file-list img")].some(img=>img.naturalWidth===256));
  const lastRow=tp.locator(".file-row").last();assert.equal(await lastRow.locator("img").getAttribute("src"),null);
  assert.equal(thumbnails.scenario.requests.filter(u=>u.includes("/sources/")&&u.includes("/content")).length,1);
  await lastRow.scrollIntoViewIfNeeded();await lastRow.locator("img").waitFor({state:"visible"});
  await tp.waitForFunction(()=>document.querySelector(".file-row:last-child img").naturalWidth===256);
  assert.ok((await lastRow.locator("img").getAttribute("src")).includes("/thumbnail?page=1"));
  await tp.waitForFunction(()=>!document.querySelector(".file-row:first-child img").hasAttribute("src"));
  assert.equal(thumbnails.scenario.requests.filter(u=>u.includes("/sources/")&&u.includes("/content")).length,1);
  assert.deepEqual(thumbnails.scenario.errors,[]);await thumbnails.context.close();
  scenarios.push("dedicated 256px thumbnails, visible-list loading, offscreen release and no preview downloads for list rows");
  for(const options of [{noThumbnail:true},{unsafeThumbnail:true}]){
    const missing=await fixture(options);await missing.page.locator("#surface").waitFor({state:"visible"});
    assert.equal(await missing.page.locator("#file-list img[src]").count(),0);
    assert.equal(missing.scenario.requests.some(u=>u.includes("/thumbnail")),false);
    assert.deepEqual(missing.scenario.errors,[]);await missing.context.close();
  }
  scenarios.push("missing or foreign thumbnail sources keep safe file placeholders");

  const editing=await fixture({editing:true});const ep=editing.page;
  await ep.locator("#add-note").waitFor({state:"visible"});await ep.waitForFunction(()=>!document.querySelector("#add-note").disabled);
  const imageBox=await ep.locator("#image").boundingBox();
  await ep.locator("#add-note").click();await ep.mouse.click(imageBox.x+imageBox.width*.15,imageBox.y+imageBox.height*.2);
  await ep.locator(".annotation-note").waitFor({state:"visible"});
  await ep.locator("#note-text").fill("출입구 상부 마감 확인\n승인 후 진행");await ep.locator("#save-note").click();
  await ep.waitForFunction(()=>document.querySelector(".annotation-note")?.textContent.includes("출입구"));
  const docKey=ids[0]+":1",firstNote={...editing.scenario.documents.get(docKey).items[0]};
  const noteBox=await ep.locator(".annotation-note").boundingBox();
  await ep.mouse.move(noteBox.x+noteBox.width/2,noteBox.y+noteBox.height/2);await ep.mouse.down();
  await ep.mouse.move(noteBox.x+noteBox.width/2+40,noteBox.y+noteBox.height/2+25);await ep.mouse.up();
  await ep.waitForFunction(()=>document.querySelector("#editing-status").textContent==="저장됨");
  const moved=editing.scenario.documents.get(docKey).items[0];
  assert.ok(Math.abs(moved.x-firstNote.x-40/(imageBox.width/2560))<1e-5);
  assert.ok(Math.abs(moved.y-firstNote.y-25/(imageBox.height/1600))<1e-5);
  await ep.locator("#annotation-width").fill("800");await ep.locator("#annotation-height").fill("360");await ep.locator("#save-size").click();
  await ep.waitForFunction(()=>document.querySelector("#editing-status").textContent==="저장됨");
  assert.equal(editing.scenario.documents.get(docKey).items[0].width,800);
  await ep.locator("#rotate-right").click();
  await ep.locator("#stamp-input").setInputFiles({name:"확인.png",mimeType:"image/png",buffer:stampPng});
  await ep.waitForFunction(()=>document.querySelector("#add-stamp").getAttribute("aria-pressed")==="true"&&!document.querySelector("#add-stamp").disabled);
  const editorArea=await ep.locator("#viewport").boundingBox();await ep.mouse.click(editorArea.x+editorArea.width/2,editorArea.y+editorArea.height/2);
  await ep.locator(".annotation-stamp").waitFor({state:"visible"});
  const placed=editing.scenario.documents.get(docKey).items.find(item=>item.type==="stamp");
  assert.ok(Math.abs(placed.x-1280)<5&&Math.abs(placed.y-800)<5);
  for(let i=0;i<3;i++)await ep.locator("#rotate-right").click();
  await ep.locator("#tools-panel").evaluate(node=>node.scrollTop=0);await ep.screenshot({path:path.join(out,"viewer-editing.png")});
  await ep.reload();await ep.waitForFunction(()=>document.querySelectorAll(".annotation").length===2);
  await ep.locator("#next-page").click();await ep.waitForFunction(()=>document.querySelector("#page").value==="2"&&!document.querySelector("#surface").hidden);
  assert.equal(await ep.locator(".annotation").count(),0);
  await ep.locator("#previous-page").click();await ep.waitForFunction(()=>document.querySelectorAll(".annotation").length===2);
  await ep.locator(".annotation-row").filter({hasText:"출입구"}).click();
  await ep.locator("#note-text").fill("<img src=x onerror=window.noteXss=1>");editing.scenario.conflictOnce=true;
  await ep.locator("#save-note").click();await ep.waitForFunction(()=>document.querySelector("#editing-status").textContent==="저장 확인 필요");
  assert.equal(await ep.locator("#note-text").inputValue(),"<img src=x onerror=window.noteXss=1>");
  await ep.locator("#save-note").click();await ep.waitForFunction(()=>document.querySelector(".annotation-note")?.textContent.includes("<img"));
  assert.equal(await ep.evaluate(()=>window.noteXss),undefined);assert.equal(await ep.locator(".annotation-note img").count(),0);
  await ep.locator("#delete-annotation").click();await ep.waitForFunction(()=>document.querySelectorAll(".annotation").length===1);
  assert.equal(editing.scenario.documents.get(docKey).items[0].type,"stamp");
  // A delayed conflict refresh for page 1 must not overwrite page 2's editing state.
  await ep.locator(".annotation-row").click();
  editing.scenario.conflictOnce=true;editing.scenario.deferAnnotationRead=true;
  await ep.locator("#save-size").click();
  const heldUntil=Date.now()+5000;
  while(!editing.scenario.releaseAnnotationRead&&Date.now()<heldUntil)await new Promise(resolve=>setTimeout(resolve,10));
  assert.ok(editing.scenario.releaseAnnotationRead);
  await ep.locator("#next-page").click();
  await ep.waitForFunction(()=>document.querySelector("#page").value==="2"&&!document.querySelector("#add-note").disabled);
  editing.scenario.releaseAnnotationRead();await ep.waitForTimeout(150);
  const secondImage=await ep.locator("#image").boundingBox();
  await ep.locator("#add-note").click();await ep.mouse.click(secondImage.x+secondImage.width*.1,secondImage.y+secondImage.height*.1);
  await ep.locator(".annotation-note").waitFor({state:"visible"});
  assert.equal(editing.scenario.documents.get(ids[0]+":2").items.length,1);
  assert.equal(editing.scenario.documents.get(docKey).items.length,1);
  await ep.locator("#previous-page").click();await ep.waitForFunction(()=>document.querySelectorAll(".annotation-stamp").length===1);
  editing.scenario.files[0].permissions.stamp=false;editing.scenario.files[0].permissions.annotate=false;
  await ep.reload();await ep.waitForFunction(()=>document.querySelectorAll(".annotation").length===1);
  assert.equal(await ep.locator("#add-stamp").isDisabled(),true);assert.equal(await ep.locator("#add-note").isDisabled(),true);
  editing.scenario.revoked=true;await ep.locator("#next-page").click();
  await ep.waitForFunction(()=>document.querySelector("#file-list").children.length===0);
  assert.equal(await ep.locator(".annotation").count(),0);assert.equal(await ep.locator("#stamp-select option").count(),0);
  assert.deepEqual(editing.scenario.errors,[]);await editing.context.close();scenarios.push("click notes and uploaded stamps, rotated coordinates, move, resize, persistence, page scope, conflict, deletion, readonly and revocation");

  async function addHistoryNote(f){const p=f.page;await p.waitForFunction(()=>!document.querySelector("#add-note").disabled);
    const box=await p.locator("#image").boundingBox();await p.locator("#add-note").click();await p.mouse.click(box.x+box.width*.2,box.y+box.height*.2);
    await p.waitForFunction(()=>!document.querySelector("#annotation-undo").disabled||document.querySelector("#history-hint").textContent.includes("사용할 수 없습니다"));
    await p.locator(".annotation-note").waitFor({state:"visible"});
  }
  const undoView=await fixture({editing:true});const up=undoView.page;await addHistoryNote(undoView);
  const undoKey=ids[0]+":1",originalId=undoView.scenario.documents.get(undoKey).items[0].id;
  await up.locator("#note-text").fill("초안");assert.equal(await up.locator("#annotation-undo").isDisabled(),true);
  await up.locator("#note-text").press("Control+z");assert.equal(undoView.scenario.historyRequests.length,0);
  await up.locator("#note-text").fill("최종 메모");await up.locator("#save-note").click();await up.waitForFunction(()=>!document.querySelector("#annotation-undo").disabled);
  await up.locator("#viewport").focus();await up.keyboard.press("Control+z");await up.waitForFunction(()=>document.querySelector(".annotation-note")?.textContent==="새 메모"&&!document.querySelector("#annotation-redo").disabled);
  await up.keyboard.press("Control+Shift+z");await up.waitForFunction(()=>document.querySelector(".annotation-note")?.textContent==="최종 메모");
  await up.locator("#delete-annotation").click();await up.waitForFunction(()=>document.querySelectorAll(".annotation").length===0&&!document.querySelector("#annotation-undo").disabled);
  await up.locator("#annotation-undo").click();await up.locator(".annotation-note").waitFor({state:"visible"});assert.equal(undoView.scenario.documents.get(undoKey).items[0].id,originalId);
  await up.locator("#viewport").focus();await up.keyboard.press("Control+y");await up.waitForFunction(()=>document.querySelectorAll(".annotation").length===0);
  await up.locator("#annotation-undo").click();await up.waitForFunction(()=>document.querySelectorAll(".annotation").length===1&&!document.querySelector("#annotation-undo").disabled);
  await up.locator("#note-text").fill("새 분기");await up.locator("#save-note").click();await up.waitForFunction(()=>document.querySelector(".annotation-note")?.textContent==="새 분기");assert.equal(await up.locator("#annotation-redo").isDisabled(),true);
  await up.locator("#annotation-width").fill("600");assert.equal(await up.locator("#annotation-undo").isDisabled(),true);await up.locator("#save-size").click();await up.waitForFunction(()=>!document.querySelector("#annotation-undo").disabled);
  await up.locator("#annotation-undo").click();await up.waitForFunction(()=>!document.querySelector("#annotation-redo").disabled);assert.notEqual(undoView.scenario.documents.get(undoKey).items[0].width,600);
  await up.locator("#stamp-input").setInputFiles({name:"확인.png",mimeType:"image/png",buffer:stampPng});
  await up.waitForFunction(()=>document.querySelector("#add-stamp").getAttribute("aria-pressed")==="true"&&!document.querySelector("#add-stamp").disabled);
  const ub=await up.locator("#image").boundingBox();await up.mouse.click(ub.x+ub.width*.6,ub.y+ub.height*.6);await up.locator(".annotation-stamp").waitFor({state:"visible"});
  const stampId=undoView.scenario.documents.get(undoKey).items.find(i=>i.type==="stamp").id;
  await up.locator("#annotation-undo").click();await up.waitForFunction(()=>document.querySelectorAll(".annotation-stamp").length===0);
  await up.locator("#annotation-redo").click();await up.locator(".annotation-stamp").waitFor({state:"visible"});assert.equal(undoView.scenario.documents.get(undoKey).items.find(i=>i.type==="stamp").id,stampId);
  await up.locator("#tools-panel").evaluate(node=>node.scrollTop=0);await up.screenshot({path:path.join(out,"viewer-history.png")});
  assert.deepEqual(undoView.scenario.errors,[]);await undoView.context.close();scenarios.push("saved note and stamp undo/redo, stable IDs, size restoration, new branch, shortcuts and native draft undo");

  const recovery=await fixture({editing:true});const rp=recovery.page;await addHistoryNote(recovery);
  recovery.scenario.historyConflictOnce=true;await rp.locator("#annotation-undo").click();await rp.waitForFunction(()=>document.querySelector("#editing-status").textContent==="변경 기록 확인 필요");
  assert.equal(await rp.locator(".annotation-note").count(),1);assert.equal(await rp.locator("#annotation-undo").isDisabled(),true);
  await rp.locator("#note-text").fill("복구 확인");await rp.locator("#save-note").click();await rp.waitForFunction(()=>!document.querySelector("#annotation-undo").disabled);
  const requestsBefore=recovery.scenario.historyRequests.length;recovery.scenario.historyLostOnce=true;
  await rp.locator("#annotation-undo").click();await rp.waitForFunction(()=>document.querySelector("#editing-status").textContent==="변경 기록 확인 필요");
  assert.equal(recovery.scenario.historyRequests.length,requestsBefore+1);assert.equal(await rp.locator(".annotation-note").textContent(),"새 메모");
  await rp.locator("#annotation-redo").click();await rp.waitForFunction(()=>document.querySelector(".annotation-note")?.textContent==="복구 확인");
  recovery.scenario.files[0].permissions.annotate=false;recovery.scenario.files[0].permissions.stamp=false;await rp.reload();await rp.locator(".annotation-note").waitFor({state:"visible"});
  assert.equal(await rp.locator("#annotation-undo").isDisabled(),true);recovery.scenario.revoked=true;await rp.locator("#next-page").click();await rp.waitForFunction(()=>document.querySelector("#file-list").children.length===0);
  assert.equal(await rp.locator("#annotation-redo").isDisabled(),true);assert.deepEqual(recovery.scenario.errors,[]);await recovery.context.close();scenarios.push("history conflict and committed lost response reload without replay, permission changes and revocation cleanup");

  const delayedHistory=await fixture({editing:true});const dp=delayedHistory.page;await addHistoryNote(delayedHistory);
  delayedHistory.scenario.historyDelay=true;await dp.locator("#annotation-undo").click();
  const waitUntil=Date.now()+5000;while(!delayedHistory.scenario.releaseHistoryResponse&&Date.now()<waitUntil)await new Promise(r=>setTimeout(r,10));assert.ok(delayedHistory.scenario.releaseHistoryResponse);
  await dp.locator("#next-page").click();await dp.waitForFunction(()=>document.querySelector("#page").value==="2"&&!document.querySelector("#add-note").disabled);
  delayedHistory.scenario.releaseHistoryResponse();await dp.waitForTimeout(100);assert.equal(await dp.locator(".annotation").count(),0);assert.equal(await dp.locator("#annotation-redo").isDisabled(),true);
  await dp.locator('.file-row[data-id="'+ids[1]+'"]').click();await dp.waitForFunction(()=>document.querySelector("#file-name").textContent==="평면도_B1.tif"&&!document.querySelector("#add-note").disabled);assert.equal(await dp.locator("#annotation-redo").isDisabled(),true);
  await dp.locator('.file-row[data-id="'+ids[0]+'"]').click();await dp.waitForFunction(()=>!document.querySelector("#annotation-redo").disabled);await dp.locator("#annotation-redo").click();await dp.locator(".annotation-note").waitFor({state:"visible"});
  assert.deepEqual(delayedHistory.scenario.errors,[]);await delayedHistory.context.close();scenarios.push("late history response is isolated across pages and files and each context reloads its own commands");
  const noHistory=await fixture({editing:true,historyDisabled:true});await addHistoryNote(noHistory);
  assert.equal(await noHistory.page.locator("#annotation-undo").isDisabled(),true);await noHistory.page.locator("#viewport").focus();await noHistory.page.keyboard.press("Control+z");
  assert.equal(noHistory.scenario.historyRequests.length,0);assert.equal(await noHistory.page.locator(".annotation-note").count(),1);await noHistory.context.close();scenarios.push("history installation policy disables commands and keyboard mutations");

  const cropped=await fixture({crop:true}),cp=cropped.page;
  await cp.waitForFunction(()=>!document.querySelector("#crop-start").disabled);
  await cp.locator("#rotate-right").click();await cp.locator("#zoom-in").click();
  const rotated=await cp.locator("#image").boundingBox();
  const transformBefore=await cp.locator("#surface").getAttribute("style");
  await cp.locator("#magnify").click();
  await cp.locator("#crop-start").click();
  assert.equal(await cp.locator("#magnify").getAttribute("aria-pressed"),"false");
  await cp.mouse.move(rotated.x+rotated.width*.25,rotated.y+rotated.height*.25);await cp.mouse.down();
  await cp.mouse.move(rotated.x+rotated.width*.55,rotated.y+rotated.height*.65,{steps:8});await cp.mouse.up();
  await cp.locator("#crop-selection").waitFor({state:"visible"});
  assert.equal(await cp.locator("#surface").getAttribute("style"),transformBefore);
  const selected=await cp.locator("#crop-controls").evaluate(node=>Object.fromEntries(["x","y","width","height"].map(k=>[k,Number(node.querySelector("#crop-"+k).value)])));
  assert.ok(Math.abs(selected.x-640)<3&&Math.abs(selected.y-720)<3);
  assert.ok(Math.abs(selected.width-1024)<3&&Math.abs(selected.height-480)<3);
  await cp.locator("#rotate-left").click();await cp.locator("#fit").click();
  await cp.screenshot({path:path.join(out,"viewer-crop.png")});
  for(const [key,value]of Object.entries({x:100,y:120,width:240,height:160})){
    await cp.locator("#crop-"+key).fill(String(value));await cp.locator("#crop-"+key).dispatchEvent("change");
  }
  cropped.scenario.cropStatus=409;await cp.locator("#crop-save").click();
  await cp.waitForFunction(()=>document.querySelector("#toast").textContent.includes("원본 상태가 변경"));
  assert.equal(await cp.locator("#crop-selection").isVisible(),true);
  cropped.scenario.cropStatus=null;
  const downloadEvent=cp.waitForEvent("download");await cp.locator("#crop-save").click();const download=await downloadEvent;
  assert.equal(download.suggestedFilename(),"imagegate-crop-p1.png");
  await download.saveAs(path.join(out,"crop-download.png"));
  assert.equal((await readFile(path.join(out,"crop-download.png"))).readUInt32BE(16),240);
  const request=cropped.scenario.cropRequests.at(-1);
  assert.equal(request.x,100);assert.equal(request.y,120);assert.equal(request.width,240);assert.equal(request.height,160);
  assert.equal(request.mediaVersion,cropped.scenario.files[0].mediaVersion);
  assert.equal(request.coordinateSpaceId,"original:"+request.mediaVersion+":1:upright-v1");
  assert.ok(cropped.scenario.headers.every(token=>token==="fixture-token"));
  await cp.keyboard.press("Escape");assert.equal(await cp.locator("#crop-selection").isVisible(),false);
  // Clear an in-flight export on page change; the late response must never start a download.
  await cp.locator("#crop-start").click();
  for(const [key,value]of Object.entries({x:100,y:120,width:240,height:160})){
    await cp.locator("#crop-"+key).fill(String(value));await cp.locator("#crop-"+key).dispatchEvent("change");
  }
  cropped.scenario.deferCrop=true;let lateDownloads=0;cp.on("download",()=>lateDownloads++);
  await cp.locator("#crop-save").click();
  const cropHeldUntil=Date.now()+5000;
  while(!cropped.scenario.releaseCrop&&Date.now()<cropHeldUntil)await new Promise(resolve=>setTimeout(resolve,10));
  assert.ok(cropped.scenario.releaseCrop);
  await cp.locator("#next-page").click();await cp.waitForFunction(()=>document.querySelector("#page").value==="2"&&!document.querySelector("#surface").hidden);
  cropped.scenario.releaseCrop();await cp.waitForTimeout(150);
  assert.equal(lateDownloads,0);assert.equal(await cp.locator("#crop-selection").isVisible(),false);
  cropped.scenario.files[0].permissions.cropAndSave=false;await cp.reload();await cp.locator("#surface").waitFor({state:"visible"});
  assert.equal(await cp.locator("#crop-start").isDisabled(),true);
  assert.deepEqual(cropped.scenario.errors,[]);await cropped.context.close();scenarios.push("rotated and zoomed crop coordinates, PNG download, version conflict, CSRF, cancel, stale download and readonly");
  const unknownGeometry=await fixture({crop:true,unknownGeometry:true});await unknownGeometry.page.locator("#surface").waitFor({state:"visible"});
  assert.equal(await unknownGeometry.page.locator("#crop-start").isDisabled(),true);
  await unknownGeometry.context.close();scenarios.push("crop blocked when original coordinates are unverified");

  const editedView=await fixture({editing:true,crop:true,export:true}),xp=editedView.page;
  await xp.waitForFunction(()=>!document.querySelector("#add-note").disabled&&!document.querySelector("#export-full").disabled);
  const xb=await xp.locator("#image").boundingBox();
  await xp.locator("#add-note").click();await xp.mouse.click(xb.x+xb.width*.2,xb.y+xb.height*.2);
  await xp.locator(".annotation-note").waitFor({state:"visible"});
  await xp.locator("#note-text").fill("합성 저장 확인");
  assert.equal(await xp.locator("#export-full").isDisabled(),true);
  await xp.locator("#save-note").click();await xp.waitForFunction(()=>!document.querySelector("#export-full").disabled);
  await xp.locator("#stamp-input").setInputFiles({name:"확인.png",mimeType:"image/png",buffer:stampPng});
  await xp.waitForFunction(()=>document.querySelector("#add-stamp").getAttribute("aria-pressed")==="true"&&!document.querySelector("#add-stamp").disabled);
  const xv=await xp.locator("#viewport").boundingBox();await xp.mouse.click(xv.x+xv.width/2,xv.y+xv.height/2);
  await xp.locator(".annotation-stamp").waitFor({state:"visible"});
  await xp.locator("#brightness").evaluate(el=>{el.value="135";el.dispatchEvent(new Event("input",{bubbles:true}));});
  await xp.locator("#rotate-right").click();await xp.locator("#crop-start").click();
  for(const [key,value]of Object.entries({x:200,y:250,width:240,height:160})){
    await xp.locator("#crop-"+key).fill(String(value));await xp.locator("#crop-"+key).dispatchEvent("change");
  }
  await xp.locator(".export-section").scrollIntoViewIfNeeded();
  await xp.locator("#toast").waitFor({state:"hidden"});
  await xp.screenshot({path:path.join(out,"viewer-export.png")});
  editedView.scenario.exportStatus=409;await xp.locator("#export-region").click();
  await xp.waitForFunction(()=>document.querySelector("#toast").textContent.includes("원본 또는 메모가 변경"));
  editedView.scenario.exportStatus=null;
  let exportedDownload=xp.waitForEvent("download");await xp.locator("#export-region").click();let xd=await exportedDownload;
  assert.equal(xd.suggestedFilename(),"imagegate-edited-p1.png");await xd.saveAs(path.join(out,"edited-download.png"));
  assert.equal((await readFile(path.join(out,"edited-download.png"))).readUInt32BE(16),160);
  let exportRequest=editedView.scenario.exportRequests.at(-1);
  assert.equal(exportRequest.expectedRevision,editedView.scenario.documents.get(ids[0]+":1").revision);
  assert.equal(exportRequest.includeAnnotations,true);assert.equal(exportRequest.brightness,135);assert.equal(exportRequest.rotation,90);
  assert.equal(exportRequest.x,200);assert.equal(exportRequest.width,240);
  for(const id of ["export-notes","export-brightness","export-rotation"])await xp.locator("#"+id).uncheck();
  exportedDownload=xp.waitForEvent("download");await xp.locator("#export-full").click();xd=await exportedDownload;
  exportRequest=editedView.scenario.exportRequests.at(-1);
  assert.equal(exportRequest.includeAnnotations,false);assert.equal(exportRequest.brightness,100);assert.equal(exportRequest.rotation,0);
  assert.equal(exportRequest.x,0);assert.equal(exportRequest.y,0);assert.equal(exportRequest.width,2560);assert.equal(exportRequest.height,1600);
  editedView.scenario.exportMime="text/html";await xp.locator("#export-full").click();
  await xp.waitForFunction(()=>document.querySelector("#toast").textContent==="편집 이미지를 저장하지 못했습니다.");
  editedView.scenario.exportMime=null;editedView.scenario.deferExport=true;let lateExports=0;xp.on("download",()=>lateExports++);
  await xp.locator("#export-region").click();
  const exportHeldUntil=Date.now()+5000;
  while(!editedView.scenario.releaseExport&&Date.now()<exportHeldUntil)await new Promise(resolve=>setTimeout(resolve,10));
  assert.ok(editedView.scenario.releaseExport);await xp.locator("#next-page").click();
  await xp.waitForFunction(()=>document.querySelector("#page").value==="2"&&!document.querySelector("#surface").hidden);
  editedView.scenario.releaseExport();await xp.waitForTimeout(150);assert.equal(lateExports,0);
  editedView.scenario.files[0].permissions.exportEdited=false;await xp.reload();await xp.locator("#surface").waitFor({state:"visible"});
  assert.equal(await xp.locator("#export-full").isDisabled(),true);assert.equal(await xp.locator("#export-region").isDisabled(),true);
  assert.ok(editedView.scenario.headers.every(token=>token==="fixture-token"));assert.deepEqual(editedView.scenario.errors,[]);
  await editedView.context.close();scenarios.push("edited full and region PNG, saved revision, unsaved draft guard, brightness and rotation options, MIME rejection, stale download and export permission");

  const disabled=await fixture({features:{zoom:false,rotation:false,brightness:false,magnifier:false}});
  await disabled.page.locator("#surface").waitFor({state:"visible"});
  for(const selector of ["#zoom-in","#rotate-right","#brightness","#magnify"])assert.equal(await disabled.page.locator(selector).isDisabled(),true);
  const transform=await disabled.page.locator("#surface").getAttribute("style");
  await disabled.page.keyboard.press("r");await disabled.page.keyboard.press("+");
  assert.equal(await disabled.page.locator("#surface").getAttribute("style"),transform);
  assert.deepEqual(disabled.scenario.errors,[]);await disabled.context.close();scenarios.push("feature policy and keyboard guards");

  const unsafe=await fixture({unsafe:true});
  await unsafe.page.waitForFunction(()=>document.querySelector("#message-title").textContent==="파일을 표시할 수 없습니다");
  assert.equal(await unsafe.page.locator("#surface").isVisible(),false);
  assert.equal(unsafe.scenario.requests.some(url=>url.includes("example.invalid")),false);
  await unsafe.context.close();scenarios.push("reject foreign source URL");

  const xss=await fixture({xss:true});
  await xss.page.locator("#surface").waitFor({state:"visible"});
  assert.equal(await xss.page.locator("#file-name").textContent(),"<img src=x onerror=window.xss=1>.png");
  assert.equal(await xss.page.evaluate(()=>window.xss),undefined);
  assert.equal(await xss.page.locator("#file-list img[onerror]").count(),0);
  await xss.context.close();scenarios.push("literal untrusted filenames without HTML execution");

  const anonymous=await fixture({anonymous:true});
  await anonymous.page.getByRole("heading",{name:"로그인이 필요합니다",exact:true}).waitFor();
  assert.equal(await anonymous.page.locator("#open-files").isDisabled(),true);
  assert.equal(await anonymous.page.locator(".file-row").count(),0);
  await anonymous.context.close();scenarios.push("anonymous shell without media");

  const replay=await fixture({playback:"video"}),vp=replay.page;
  await vp.waitForFunction(()=>!document.querySelector("#player-play").disabled);
  assert.equal(await vp.locator("#player-video").isVisible(),true);assert.equal(await vp.locator("#zoom-in").isDisabled(),true);
  await vp.waitForFunction(()=>Math.abs(document.querySelector("#player-video").currentTime-.5)<.15);
  assert.equal(await vp.locator("#player-video").evaluate(el=>el.paused),true);
  await vp.locator("#player-play").click();await vp.waitForFunction(()=>document.querySelector("#player-video").currentTime>.8);
  await vp.locator("#player-play").click();
  await vp.locator("#player-speed").selectOption("1.5");assert.equal(await vp.locator("#player-video").evaluate(el=>el.playbackRate),1.5);
  await vp.locator("#player-seek").evaluate(el=>{el.value="1.7";el.dispatchEvent(new Event("input",{bubbles:true}));});
  await vp.waitForFunction(()=>Math.abs(document.querySelector("#player-video").currentTime-1.7)<.15);
  await vp.locator("#player-volume").evaluate(el=>{el.value=".35";el.dispatchEvent(new Event("input",{bubbles:true}));});
  assert.equal(await vp.locator("#player-video").evaluate(el=>el.volume),.35);
  await vp.locator("#player-mute").click();assert.equal(await vp.locator("#player-video").evaluate(el=>el.muted),true);
  await vp.locator("#player-fullscreen").click();
  await vp.waitForFunction(()=>document.fullscreenElement?.id==="media-player");
  await vp.evaluate(()=>document.exitFullscreen());
  await vp.waitForFunction(()=>!document.fullscreenElement);
  await vp.screenshot({path:path.join(out,"viewer-video.png")});
  await vp.locator(".file-row").nth(1).click();await vp.waitForFunction(()=>!document.querySelector("#player-play").disabled&&document.querySelector("#audio-card").hidden===false);
  assert.equal(await vp.locator("#player-video").getAttribute("src"),null);assert.equal(await vp.locator("#player-video").evaluate(el=>el.paused),true);
  await vp.locator("#player-play").click();await vp.waitForFunction(()=>document.querySelector("#player-audio").currentTime>.2);
  await vp.locator("#player-play").click();await vp.screenshot({path:path.join(out,"viewer-audio.png")});
  await vp.locator(".file-row").nth(2).click();await vp.locator("#surface").waitFor({state:"visible"});
  assert.equal(await vp.locator("#player-audio").getAttribute("src"),null);assert.equal(await vp.locator("#media-player").isVisible(),false);
  assert.ok(replay.scenario.mediaRanges.length>0);assert.ok(replay.scenario.headers.every(token=>token==="fixture-token"));
  assert.deepEqual(replay.scenario.errors,[]);await replay.context.close();scenarios.push("real FFmpeg WebM and MP3 playback, initial seek, seek bar, speed, volume, mute and source cleanup");
  const restricted=await fixture({playback:"audio",features:{audioSeek:false,audioPlaybackSpeed:false}});
  await restricted.page.waitForFunction(()=>!document.querySelector("#player-play").disabled);
  assert.equal(await restricted.page.locator("#player-seek").isDisabled(),true);assert.equal(await restricted.page.locator("#player-speed").isDisabled(),true);
  await restricted.page.locator("#viewport").focus();await restricted.page.keyboard.press("ArrowRight");
  assert.equal(await restricted.page.locator("#player-audio").evaluate(el=>el.currentTime),.5);
  restricted.scenario.revoked=true;await restricted.page.locator(".file-row").nth(1).click();
  await restricted.page.waitForFunction(()=>document.querySelector("#file-list").children.length===0);
  assert.equal(await restricted.page.locator("#player-audio").getAttribute("src"),null);
  await restricted.context.close();scenarios.push("audio seek and speed installation policy, keyboard guards and permission loss cleanup");
  const invalidTime=await fixture({playback:"video",initialTime:999});
  await invalidTime.page.getByRole("heading",{name:"재생 시작 위치를 확인해 주세요",exact:true}).waitFor();
  assert.equal(await invalidTime.page.locator("#file-list").locator(".file-row").count(),0);
  await invalidTime.context.close();scenarios.push("out of range playback start is rejected without playing");
  const unsafePlayback=await fixture({playback:"video",unsafePlayback:true});
  await unsafePlayback.page.getByRole("heading",{name:"파일을 표시할 수 없습니다",exact:true}).waitFor();
  assert.equal(await unsafePlayback.page.locator("#player-video").getAttribute("src"),null);
  assert.equal(unsafePlayback.scenario.requests.some(url=>url.includes("example.invalid")),false);
  await unsafePlayback.context.close();scenarios.push("reject foreign media URL before assigning the player source");

  const tileView=await fixture({tiles:true,editing:true,tileQueued:true,tileDelay:40,tileWidth:8000,tileHeight:5000});const tv=tileView.page;
  await tv.locator("#surface").waitFor({state:"visible"});await tv.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);
  await tv.waitForTimeout(400);assert.equal(tileView.scenario.tilePosts.length,1);assert.equal(tileView.scenario.tilePosts[0].csrf,"fixture-token");assert.ok(tileView.scenario.maxActiveTiles<=4);
  await tv.locator("#actual-size").click();await tv.waitForFunction(()=>document.querySelector("#actual-size").textContent==="100%");await tv.waitForTimeout(400);
  assert.ok(await tv.locator("#tile-layer img").count()<=48);assert.ok(tileView.scenario.tileRequests.some(r=>r.z===13));
  await tv.waitForFunction(()=>!document.querySelector("#add-note").disabled);const va=await tv.locator("#viewport").boundingBox();
  await tv.locator("#add-note").click();await tv.mouse.click(va.x+va.width/2,va.y+va.height/2);await tv.locator(".annotation-note").waitFor();
  const tileDocKey=ids[0]+":1",placedNote=tileView.scenario.documents.get(tileDocKey).items[0];assert.ok(Math.abs(placedNote.x-4000)<3&&Math.abs(placedNote.y-2500)<3);
  await tv.locator("#rotate-right").click();await tv.locator("#brightness").evaluate(el=>{el.value="135";el.dispatchEvent(new Event("input",{bubbles:true}));});
  await tv.waitForFunction(()=>document.querySelector("#tile-layer").style.filter==="brightness(135%)");
  await tv.locator("#stamp-input").setInputFiles({name:"확인.png",mimeType:"image/png",buffer:stampPng});await tv.waitForFunction(()=>document.querySelector("#add-stamp").getAttribute("aria-pressed")==="true");
  await tv.mouse.click(va.x+va.width/2,va.y+va.height/2);await tv.locator(".annotation-stamp").waitFor();const placedStamp=tileView.scenario.documents.get(tileDocKey).items.find(i=>i.type==="stamp");assert.ok(Math.abs(placedStamp.x-4000)<3&&Math.abs(placedStamp.y-2500)<3);
  await tv.locator("#magnify").click();await tv.mouse.move(va.x+va.width/2+30,va.y+va.height/2+30);await tv.locator("#lens").waitFor({state:"visible"});await tv.locator("#magnify").click();
  await tv.waitForTimeout(400);assert.ok(await tv.evaluate(()=>window.tileFetchMax<=4));assert.equal(await tv.locator("#tile-layer").evaluate(el=>getComputedStyle(el).pointerEvents),"none");
  await tv.locator("#brightness-reset").click();await tv.waitForTimeout(120);await tv.screenshot({path:path.join(out,"viewer-tiles.png")});await tv.locator("#theme").click();await tv.screenshot({path:path.join(out,"viewer-tiles-light.png")});
  assert.deepEqual(tileView.scenario.errors,[]);await tileView.context.close();scenarios.push("bounded viewport tiles, original 100%, rotation/brightness/lens, click note/uploaded stamp coordinate alignment and themes");

  const unknownTile=await fixture({tiles:true,editing:true,unknownGeometry:true});await unknownTile.page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);assert.equal(await unknownTile.page.locator("#add-note").isDisabled(),true);await unknownTile.context.close();scenarios.push("tiles do not fabricate editing geometry or permissions");
  const busyTile=await fixture({tiles:true,tileBusy:true,editing:true});await busyTile.page.locator("#tile-retry").waitFor({state:"visible"});
  assert.match(await busyTile.page.locator("#tile-message").textContent(),/변환 요청이 많습니다/);assert.equal(await busyTile.page.locator("#image").isVisible(),true);
  await busyTile.page.waitForTimeout(300);assert.equal(busyTile.scenario.tilePosts.length,1);
  busyTile.scenario.tileBusy=false;busyTile.scenario.deferPyramid=true;await busyTile.page.locator("#tile-retry").click();
  await busyTile.page.waitForFunction(()=>document.querySelector("#tile-retry").hidden);let busyUntil=Date.now()+5000;while(!busyTile.scenario.releasePyramid&&Date.now()<busyUntil)await new Promise(r=>setTimeout(r,10));
  assert.ok(busyTile.scenario.releasePyramid);await busyTile.page.locator("#tile-retry").dispatchEvent("click");assert.equal(busyTile.scenario.tilePosts.length,2);
  busyTile.scenario.releasePyramid();await busyTile.page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);
  assert.equal(busyTile.scenario.tilePosts[1].retry,"true");assert.deepEqual(busyTile.scenario.errors,[]);await busyTile.context.close();scenarios.push("native tile busy preserves preview and needs one explicit retry, with duplicate in-flight clicks ignored");
  const fullTile=await fixture({tiles:true,tileQueueFull:true});await fullTile.page.locator("#tile-retry").waitFor({state:"visible"});assert.match(await fullTile.page.locator("#tile-message").textContent(),/대기열이 가득/);
  await fullTile.page.waitForTimeout(300);assert.equal(fullTile.scenario.tilePosts.length,1);assert.equal(await fullTile.page.locator("#image").isVisible(),true);
  fullTile.scenario.tileQueueFull=false;await fullTile.page.locator("#tile-retry").click();await fullTile.page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);await fullTile.context.close();scenarios.push("tile admission 429 has a distinct queue message and manual recovery");
  const rejectedTile=await fixture({tiles:true,tileFailure:true,tileFailureCode:"TILE_QUEUE_FULL"});await rejectedTile.page.locator("#tile-retry").waitFor({state:"visible"});assert.match(await rejectedTile.page.locator("#tile-message").textContent(),/대기열이 가득/);
  await rejectedTile.page.waitForTimeout(300);assert.equal(rejectedTile.scenario.tilePosts.length,1);rejectedTile.scenario.tileFailure=false;await rejectedTile.page.locator("#tile-retry").click();await rejectedTile.page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);await rejectedTile.context.close();scenarios.push("persisted executor admission rejection uses the queue message without automatic resubmission");
  for(const [code,text,retry] of [
    ["CONVERSION_UNSUPPORTED_PROFILE",/지원하지 않는 이미지 구성/,false],
    ["DECODER_UNAVAILABLE",/이미지 형식을 열 수 없습니다/,false],
    ["CONVERSION_INVALID_INPUT",/이미지를 처리할 수 없습니다/,false],
    ["CONVERSION_UNAVAILABLE",/변환 기능을 사용할 수 없습니다/,false],
    ["CONVERSION_PROCESS_FAILED",/이미지 변환에 실패했습니다/,true],
    ["__proto__",/이미지를 표시할 수 없습니다/,true]
  ]){
    const blocked=await fixture({previewFailureCode:code,editing:true});
    await blocked.page.waitForFunction(()=>document.querySelector("#stage-message")?.hidden===false&&!document.querySelector("#stage-message").classList.contains("loading"));
    assert.match(await blocked.page.locator("#message-title").textContent(),text);
    assert.equal(await blocked.page.locator("#retry").isVisible(),retry);
    assert.equal(await blocked.page.locator("#surface").isVisible(),false);
    assert.equal(await blocked.page.locator("#add-note").isEnabled(),false);
    await blocked.page.waitForTimeout(250);assert.equal(blocked.scenario.previewPosts.length,1);
    if(retry){blocked.scenario.previewFailureCode=null;await blocked.page.locator("#retry").click();await blocked.page.locator("#surface").waitFor({state:"visible"});assert.equal(blocked.scenario.previewPosts[1].retry,"true");}
    assert.deepEqual(blocked.scenario.errors,[]);await blocked.context.close();
    scenarios.push("preview failure "+code+" uses fixed text, no automatic retry, and correct manual retry availability");
  }
  for(const reason of ["previewBusy","previewQueueFull"]){const blocked=await fixture({[reason]:true});await blocked.page.locator("#retry").waitFor({state:"visible"});
    assert.match(await blocked.page.locator("#message-body").textContent(),reason==="previewBusy"?/변환 요청이 많습니다/:/대기열이 가득/);
    await blocked.page.waitForTimeout(300);assert.equal(blocked.scenario.previewPosts.length,1);blocked.scenario[reason]=false;await blocked.page.locator("#retry").click();await blocked.page.locator("#surface").waitFor({state:"visible"});assert.equal(blocked.scenario.previewPosts[1].retry,"true");await blocked.context.close();
  }scenarios.push("preview native busy and admission 429 stop polling and recover only with explicit retry");
  const verifiedTile=await fixture({tiles:true,editing:true,unknownGeometry:true,verifyAfterTiles:true});
  await verifiedTile.page.waitForFunction(()=>!document.querySelector("#add-note").disabled);
  await verifiedTile.page.locator("#add-note").click();const verifiedBox=await verifiedTile.page.locator("#image").boundingBox();
  await verifiedTile.page.mouse.click(verifiedBox.x+verifiedBox.width*.3,verifiedBox.y+verifiedBox.height*.3);await verifiedTile.page.locator(".annotation-note").waitFor();
  await verifiedTile.page.locator("#note-text").fill("저장 전 입력 보존");
  await verifiedTile.page.locator("#tile-retry").dispatchEvent("click");await verifiedTile.page.waitForTimeout(300);
  assert.equal(await verifiedTile.page.locator("#note-text").inputValue(),"저장 전 입력 보존");
  await verifiedTile.context.close();scenarios.push("server verified page geometry enables editing once and tile retries preserve drafts");
  const tileRetry=await fixture({tiles:true,tileFailure:true,editing:true});await tileRetry.page.locator("#tile-retry").waitFor({state:"visible"});await tileRetry.page.waitForTimeout(250);assert.equal(tileRetry.scenario.tilePosts.length,1);
  tileRetry.scenario.tileFailure=false;await tileRetry.page.waitForFunction(()=>!document.querySelector("#add-note").disabled);await tileRetry.page.locator("#actual-size").click();await tileRetry.page.locator("#add-note").click();await tileRetry.page.locator("#tile-retry").click();await tileRetry.page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);assert.equal(tileRetry.scenario.tilePosts.at(-1).retry,"true");assert.equal(tileRetry.scenario.documents.get(ids[0]+":1").items.length,0);
  tileRetry.scenario.tileStatus=404;await tileRetry.page.locator("#zoom-in").click();await tileRetry.page.locator("#tile-retry").waitFor({state:"visible"});assert.equal(await tileRetry.page.locator("#image").isVisible(),true);const tilePostCount=tileRetry.scenario.tilePosts.length;await tileRetry.page.waitForTimeout(250);assert.equal(tileRetry.scenario.tilePosts.length,tilePostCount);
  tileRetry.scenario.tileStatus=0;tileRetry.scenario.tileRevision=1;await tileRetry.page.locator("#tile-retry").click();await tileRetry.page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);await tileRetry.context.close();scenarios.push("explicit tile failure/expiry retry preserves preview and never submits from tile GET errors");
  const lateTiles=await fixture({tiles:true,deferPyramid:true,tileDelay:60});await lateTiles.page.locator("#surface").waitFor({state:"visible"});let pendingUntil=Date.now()+5000;while(!lateTiles.scenario.releasePyramid&&Date.now()<pendingUntil)await new Promise(r=>setTimeout(r,10));assert.ok(lateTiles.scenario.releasePyramid);
  await lateTiles.page.locator("#next-page").click();await lateTiles.page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0&&document.querySelector("#page").value==="2");const secondGeneration=await lateTiles.page.locator("#tile-layer").getAttribute("data-generation");lateTiles.scenario.releasePyramid();await lateTiles.page.waitForTimeout(200);
  assert.equal(await lateTiles.page.locator("#tile-layer").getAttribute("data-generation"),secondGeneration);await lateTiles.page.locator('.file-row[data-id="'+ids[1]+'"]').click();await lateTiles.page.waitForFunction(()=>document.querySelector("#file-name").textContent==="평면도_B1.tif"&&document.querySelectorAll("#tile-layer img").length>0);assert.deepEqual(lateTiles.scenario.errors,[]);await lateTiles.context.close();scenarios.push("late tile metadata and images stay isolated across pages/files");
  const unsafeTiles=await fixture({tiles:true,unsafeTiles:true});await unsafeTiles.page.locator("#tile-retry").waitFor({state:"visible"});assert.equal(unsafeTiles.scenario.tileRequests.length,0);assert.equal(await unsafeTiles.page.locator("#image").isVisible(),true);assert.deepEqual(unsafeTiles.scenario.errors,[]);await unsafeTiles.context.close();scenarios.push("foreign tile templates rejected before fetch");
  const badHeader=await fixture({tiles:true,badTileHeader:true});await badHeader.page.locator("#tile-retry").waitFor({state:"visible"});await badHeader.page.waitForTimeout(150);assert.equal(await badHeader.page.evaluate(()=>window.tileObjectCreated),1);assert.equal(await badHeader.page.evaluate(()=>window.tileDecodes),1);assert.equal(await badHeader.page.locator("#tile-layer img").count(),0);await badHeader.context.close();scenarios.push("oversized PNG dimensions rejected before object URL creation or decode");
  const redirected=await fixture({tiles:true,tileRedirect:true});await redirected.page.locator("#tile-retry").waitFor({state:"visible"});assert.equal(await redirected.page.locator("#image").isVisible(),true);assert.deepEqual(redirected.scenario.errors,[]);await redirected.context.close();scenarios.push("tile redirects fail closed without cross-origin loads");
  const tileRevoked=await fixture({tiles:true});await tileRevoked.page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);tileRevoked.scenario.revoked=true;await tileRevoked.page.locator("#zoom-in").dispatchEvent("click");await tileRevoked.page.waitForFunction(()=>document.querySelector("#file-list").children.length===0);await tileRevoked.page.waitForFunction(()=>window.tileObjects.size===0);assert.equal(await tileRevoked.page.locator("#tile-layer img").count(),0);await tileRevoked.context.close();scenarios.push("tile permission loss clears preview, tiles and object URLs");
  const tileVersion=await fixture({tiles:true,tileVersion:"stale",deferPyramid:true});let versionUntil=Date.now()+5000;while(!tileVersion.scenario.releasePyramid&&Date.now()<versionUntil)await new Promise(r=>setTimeout(r,10));assert.ok(tileVersion.scenario.releasePyramid);tileVersion.scenario.releasePyramid();await tileVersion.page.waitForFunction(()=>document.querySelector("#file-list").children.length===0);await tileVersion.page.waitForFunction(()=>window.tileObjects.size===0);await tileVersion.context.close();scenarios.push("tile media version mismatch clears stale media");

  const mobile=await fixture({viewport:{width:390,height:844},tiles:true});
  await mobile.page.locator("#surface").waitFor({state:"visible"});
  await mobile.page.getByRole("button",{name:"파일 목록 접기",exact:true}).click();
  assert.equal((await mobile.page.locator(".header").boundingBox()).height,60);
  assert.equal(await mobile.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  assert.ok((await mobile.page.locator("#viewport").boundingBox()).width>350);
  await mobile.page.waitForTimeout(150);
  await mobile.page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);
  assert.ok((await mobile.page.locator("#image").boundingBox()).width>200);
  await mobile.page.screenshot({path:path.join(out,"viewer-mobile.png")});
  assert.deepEqual(mobile.scenario.errors,[]);await mobile.context.close();scenarios.push("mobile single-line header, responsive panels and bounded tiles");
  await writeFile(path.join(out,"results.json"),JSON.stringify({passed:true,scenarios,screenshots:["viewer-dark.png","viewer-light.png","viewer-mobile.png","viewer-editing.png","viewer-crop.png","viewer-export.png","viewer-history.png","viewer-video.png","viewer-audio.png","viewer-tiles.png","viewer-tiles-light.png"]},null,2));
  console.log(JSON.stringify({passed:true,scenarios,output:out},null,2));
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
