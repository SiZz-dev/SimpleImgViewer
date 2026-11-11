import {formatBytes} from "./viewer-core.js";
export function createPlayerUI({state,api,toast,message,viewport}){
  const $=id=>document.getElementById(id);
  const ui=Object.fromEntries(["media-player","player-video","player-audio","audio-card","player-title","player-play",
    "player-back","player-forward","player-seek","player-time","player-speed","player-volume","player-mute","player-fullscreen"].map(id=>[id,$(id)]));
  let media=null,info=null,ready=false,epoch=0,startTime=0;
  const time=n=>{n=Math.max(0,Number.isFinite(n)?n:0);const h=Math.floor(n/3600),m=Math.floor(n%3600/60),s=Math.floor(n%60);
    return (h?h+":"+String(m).padStart(2,"0"):m)+":"+String(s).padStart(2,"0");};
  const feature=key=>Boolean(state.bootstrap?.features[(state.current?.mediaType==="video"?"video":"audio")+key]);
  function refresh(){
    const can=ready&&state.current?.permissions?.view;
    ui["player-play"].disabled=!can;ui["player-seek"].disabled=!can||!feature("Seek");
    ui["player-back"].disabled=ui["player-forward"].disabled=ui["player-seek"].disabled;
    ui["player-speed"].disabled=!can||!feature("PlaybackSpeed");
    ui["player-volume"].disabled=ui["player-mute"].disabled=!can;
    ui["player-fullscreen"].disabled=!can||state.current?.mediaType!=="video";
  }
  function clear(){
    epoch++;ready=false;info=null;
    for(const element of [ui["player-video"],ui["player-audio"]]){element.pause();element.removeAttribute("src");element.load();}
    document.getElementById("app").classList.remove("playing-media");document.getElementById("playback-details").hidden=true;
    document.getElementById("tools-title").textContent="이미지 도구";document.getElementById("tools-subtitle").textContent="조정 · 편집";
    document.getElementById("ready-badge").hidden=true;
    media=null;ui["media-player"].hidden=true;ui["player-play"].textContent="▶";ui["player-play"].setAttribute("aria-label","재생");
    ui["player-seek"].value="0";ui["player-time"].textContent="0:00 / 0:00";ui["player-mute"].textContent="음소거";refresh();
    if(document.fullscreenElement===ui["media-player"])document.exitFullscreen().catch(()=>{});
  }
  function source(result,file){
    if(result.mediaVersion!==file.mediaVersion||!/^[a-f0-9]{64}$/.test(result.sourceId)||!Number.isFinite(result.durationSeconds)||result.durationSeconds<=0||result.durationSeconds>7200
      ||!Number.isSafeInteger(result.sizeBytes)||result.sizeBytes<1||result.sizeBytes>536870912
      ||result.mimeType!==(file.mediaType==="video"?"video/webm":"audio/mpeg")
      ||result.url!=="/api/v1/media/"+file.mediaId+"/playback/sources/"+result.sourceId+"/content")throw new Error("INVALID_PLAYBACK_SOURCE");
    return result.url;
  }
  const delay=(ms,signal)=>new Promise((resolve,reject)=>{
    if(signal.aborted)return reject(new DOMException("Aborted","AbortError"));
    const cancel=()=>{clearTimeout(timer);reject(new DOMException("Aborted","AbortError"));};
    const timer=setTimeout(()=>{signal.removeEventListener("abort",cancel);resolve();},ms);signal.addEventListener("abort",cancel,{once:true});
  });
  async function open(file,signal,retry=false,initialTime=0){
    clear();const ticket=epoch;
    message("재생 파일을 준비하고 있습니다","검증된 영상·음성 재생본을 준비하고 있습니다.",false,true);
    await api("/api/v1/media/"+file.mediaId+"/playback?retry="+retry,{method:"POST",signal});
    const started=Date.now();let pause=1000;
    while(!signal.aborted&&ticket===epoch){
      const result=await api("/api/v1/media/"+file.mediaId+"/playback",{signal});
      if(signal.aborted||ticket!==epoch)return;
      if(result.state==="ready"){
        const path=source(result,file);
        if(!Number.isFinite(initialTime)||initialTime<0||initialTime>=result.durationSeconds)
          throw Object.assign(new Error("INVALID_VIEWER_POSITION"),{status:400,code:"INVALID_VIEWER_POSITION"});
        const element=file.mediaType==="video"?ui["player-video"]:ui["player-audio"];
        if(!element.canPlayType(result.mimeType)){message("이 브라우저에서 재생할 수 없습니다","재생 형식을 지원하는 최신 브라우저로 다시 열어 주세요.");return;}
        info=result;media=element;startTime=initialTime;
        document.getElementById("app").classList.add("playing-media");document.getElementById("playback-details").hidden=false;
        document.getElementById("tools-title").textContent="재생 정보";document.getElementById("tools-subtitle").textContent=file.mediaType==="video"?"동영상":"음성";
        for(const [key,value]of Object.entries({original:file.originalMimeType,format:result.mimeType,duration:time(result.durationSeconds),
          resolution:result.width?result.width+" × "+result.height:"—",codecs:[result.videoCodec,result.audioCodec].filter(Boolean).join(" / "),bytes:formatBytes(result.sizeBytes)}))
          document.getElementById("playback-"+key).textContent=value;
        ui["player-video"].hidden=file.mediaType!=="video";ui["audio-card"].hidden=file.mediaType!=="audio";
        ui["player-title"].textContent=file.fileName;
        ui["media-player"].hidden=false;ui["player-seek"].max=String(result.durationSeconds);
        ui["player-time"].textContent="0:00 / "+time(result.durationSeconds);ui["player-speed"].value="1";
        media.volume=Number(ui["player-volume"].value);media.muted=false;media.playbackRate=1;
        ui["player-mute"].textContent="음소거";media.src=path;media.load();
        document.getElementById("stage-message").hidden=true;document.getElementById("ready-badge").hidden=false;
        document.getElementById("ready-badge").textContent="재생 준비 완료";return;
      }
      if(result.state==="disabled"){message("재생 모듈이 비활성화되어 있습니다","시스템의 FFmpeg 경로와 재생 기능 설정을 확인해 주세요.");return;}
      if(result.state==="failed"){message("재생 파일을 준비하지 못했습니다","파일 형식·재생 시간·처리 제한을 확인하거나 다시 시도해 주세요.",true);return;}
      if(Date.now()-started>600000){message("재생 준비가 지연되고 있습니다","잠시 후 다시 확인해 주세요.",true);return;}
      await delay(pause,signal);pause=Math.min(5000,Math.round(pause*1.3));
    }
  }
  function update(){
    if(!media||!info)return;const current=Number.isFinite(media.currentTime)?media.currentTime:0;
    ui["player-seek"].value=String(current);ui["player-time"].textContent=time(current)+" / "+time(info.durationSeconds);
    ui["player-play"].textContent=media.paused?"▶":"❚❚";ui["player-play"].setAttribute("aria-label",media.paused?"재생":"일시 정지");
  }
  function seek(value){
    if(!ready||ui["player-seek"].disabled||!Number.isFinite(value))return;
    media.currentTime=Math.max(0,Math.min(value,Math.max(0,info.durationSeconds-.001)));update();
  }
  async function play(){if(!ready||ui["player-play"].disabled)return;
    if(media.paused){try{await media.play();}catch{toast("재생 버튼을 다시 누르거나 브라우저의 재생 허용 상태를 확인해 주세요.");}}
    else{media.pause();update();}
  }
  for(const element of [ui["player-video"],ui["player-audio"]]){
    element.addEventListener("loadedmetadata",()=>{
      if(element!==media||!info)return;
      if(!Number.isFinite(element.duration)||element.duration<=0||Math.abs(element.duration-info.durationSeconds)>.8){
        clear();message("재생 정보를 확인할 수 없습니다","파일을 다시 선택해 주세요.",true);return;
      }
      ready=true;if(startTime)element.currentTime=startTime;refresh();update();
    });
    for(const event of ["timeupdate","play","pause","ended","seeked"])element.addEventListener(event,()=>{if(element===media)update();});
    element.addEventListener("error",()=>{
      if(element!==media||!element.getAttribute("src"))return;
      clear();message("재생할 수 없습니다","로그인·파일 접근 권한과 브라우저 형식 지원을 확인해 주세요.",true);
    });
  }
  ui["player-play"].addEventListener("click",play);
  ui["player-back"].addEventListener("click",()=>seek(media?.currentTime-10));
  ui["player-forward"].addEventListener("click",()=>seek(media?.currentTime+10));
  ui["player-seek"].addEventListener("input",()=>seek(Number(ui["player-seek"].value)));
  ui["player-speed"].addEventListener("change",()=>{const speed=Number(ui["player-speed"].value);
    if(ready&&!ui["player-speed"].disabled&&[.5,1,1.25,1.5,2].includes(speed))media.playbackRate=speed;});
  ui["player-volume"].addEventListener("input",()=>{if(ready)media.volume=Math.max(0,Math.min(1,Number(ui["player-volume"].value)));});
  ui["player-mute"].addEventListener("click",()=>{if(ready){media.muted=!media.muted;ui["player-mute"].textContent=media.muted?"음소거 해제":"음소거";}});
  ui["player-fullscreen"].addEventListener("click",async()=>{if(ui["player-fullscreen"].disabled)return;
    try{document.fullscreenElement?await document.exitFullscreen():await ui["media-player"].requestFullscreen();}catch{toast("전체 화면을 사용할 수 없습니다.");}});
  document.addEventListener("keydown",event=>{
    if(!ready||event.ctrlKey||event.metaKey||event.altKey||event.target.closest("input,textarea,select,[contenteditable=true]"))return;
    if(["ArrowLeft","ArrowRight"].includes(event.key)){event.preventDefault();event.stopImmediatePropagation();seek(media.currentTime+(event.key==="ArrowLeft"?-5:5));}
    else if(event.code==="Space"&&!event.target.closest("button")){event.preventDefault();event.stopImmediatePropagation();play();}
  },true);
  refresh();return {open,clear,refresh};
}
