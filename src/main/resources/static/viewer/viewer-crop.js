import {clamp,screenToImage,toOriginal} from "./viewer-core.js";

export function createCropUI({state,api,toast,onFatal,viewport,selection}){
  const $=id=>document.getElementById(id),ids=["crop-start","crop-controls","crop-x","crop-y","crop-width","crop-height","crop-save","crop-cancel","crop-hint"];
  const el=Object.fromEntries(ids.map(id=>[id,$(id)]));
  let active=false,area=null,drag=null,busy=false,epoch=0,controller=null,url=null;
  const allowed=()=>Boolean(state.ready&&state.pageInfo?.originalCoordinatesAvailable
    &&state.bootstrap?.features.cropAndSave&&state.current?.permissions?.cropAndSave);
  const maxPixels=()=>state.bootstrap?.limits.maxCropOutputPixels||8000000;
  function refresh(){
    const enabled=allowed();el["crop-start"].disabled=busy||!enabled;
    el["crop-start"].setAttribute("aria-pressed",String(active));
    el["crop-controls"].hidden=!(active||area||busy);
    el["crop-save"].disabled=busy||!enabled||!area||area.width*area.height>maxPixels();
    for(const id of ["crop-x","crop-y","crop-width","crop-height"])el[id].disabled=busy||!enabled;
    viewport.classList.toggle("cropping",active);
    if(!enabled){
      if(active||area)clear();
      el["crop-hint"].textContent=state.ready&&!state.pageInfo?.originalCoordinatesAvailable
        ?"원본 좌표가 확인된 이미지에서 사용할 수 있습니다.":"파일 권한과 설치 설정에 따라 사용할 수 있습니다.";
    }else if(busy)el["crop-hint"].textContent="원본 영역의 PNG 사본을 준비하고 있습니다.";
    else if(area?.width*area?.height>maxPixels())el["crop-hint"].textContent="선택 영역이 저장 가능한 픽셀 수를 초과했습니다.";
    else el["crop-hint"].textContent=active?"이미지 위에서 드래그해 영역을 선택하세요. Esc로 취소합니다."
      :area?"선택 영역을 확인한 뒤 PNG로 저장하세요.":"영역을 선택해 원본 해상도의 PNG 사본을 저장합니다.";
  }
  function revoke(){if(url)URL.revokeObjectURL(url);url=null;}
  function clear(){
    epoch++;controller?.abort();controller=null;active=false;area=null;drag=null;busy=false;revoke();
    selection.hidden=true;viewport.classList.remove("cropping");el["crop-controls"].hidden=true;
    for(const id of ["crop-x","crop-y","crop-width","crop-height"])el[id].value="";
    // Do not recurse through refresh when a permission change invalidates an active selection.
    el["crop-start"].setAttribute("aria-pressed","false");el["crop-save"].disabled=true;viewport.dispatchEvent(new Event("imagegate:cropchange"));
  }
  function paint(){
    if(!area||!state.pageInfo){selection.hidden=true;return;}
    const page=state.pageInfo,sx=page.displayWidth/page.coordinateWidth,sy=page.displayHeight/page.coordinateHeight;
    selection.style.left=area.x*sx+"px";selection.style.top=area.y*sy+"px";
    selection.style.width=area.width*sx+"px";selection.style.height=area.height*sy+"px";selection.hidden=false;
    selection.setAttribute("aria-label","선택 영역 "+area.width+" × "+area.height);
    for(const [key,value]of Object.entries(area))el["crop-"+key].value=String(value);viewport.dispatchEvent(new Event("imagegate:cropchange"));
  }
  function point(event){
    const box=viewport.getBoundingClientRect(),page=state.pageInfo;
    const pixel=screenToImage({x:event.clientX-box.left,y:event.clientY-box.top},state.view,box,
      {width:page.displayWidth,height:page.displayHeight});
    return toOriginal(pixel,page);
  }
  function start(){
    if(!allowed()||busy)return;
    clear();active=true;viewport.dispatchEvent(new CustomEvent("imagegate:tool",{detail:"crop"}));refresh();
  }
  viewport.addEventListener("imagegate:tool",event=>{if(event.detail!=="crop"){clear();refresh();}});
  viewport.addEventListener("pointerdown",event=>{
    if(!active||event.button!==0||event.target.closest("button,input,.page-navigation,.tile-status"))return;
    event.preventDefault();event.stopImmediatePropagation();
    if(!allowed())return;
    const pos=point(event),page=state.pageInfo;
    if(!pos||pos.x<0||pos.y<0||pos.x>=page.coordinateWidth||pos.y>=page.coordinateHeight)return;
    drag={start:pos,pointerId:event.pointerId,clientX:event.clientX,clientY:event.clientY,moved:false};
    viewport.setPointerCapture(event.pointerId);
  },true);
  viewport.addEventListener("pointermove",event=>{
    if(!drag||event.pointerId!==drag.pointerId)return;
    event.preventDefault();event.stopImmediatePropagation();
    const pos=point(event),page=state.pageInfo;
    if(!pos)return;
    drag.moved=drag.moved||Math.hypot(event.clientX-drag.clientX,event.clientY-drag.clientY)>3;
    const x=clamp(Math.floor(Math.min(drag.start.x,pos.x)),0,page.coordinateWidth-1);
    const y=clamp(Math.floor(Math.min(drag.start.y,pos.y)),0,page.coordinateHeight-1);
    const right=clamp(Math.ceil(Math.max(drag.start.x,pos.x)),x+1,page.coordinateWidth);
    const bottom=clamp(Math.ceil(Math.max(drag.start.y,pos.y)),y+1,page.coordinateHeight);
    area={x,y,width:right-x,height:bottom-y};paint();refresh();
  },true);
  viewport.addEventListener("pointerup",event=>{
    if(!drag||event.pointerId!==drag.pointerId)return;
    event.preventDefault();event.stopImmediatePropagation();
    const moved=drag.moved;drag=null;
    if(viewport.hasPointerCapture(event.pointerId))viewport.releasePointerCapture(event.pointerId);
    if(moved&&area){active=false;paint();}else{area=null;selection.hidden=true;}
    refresh();
  },true);
  viewport.addEventListener("pointercancel",()=>{if(drag){clear();refresh();}},true);
  viewport.addEventListener("lostpointercapture",()=>{if(drag){clear();refresh();}});
  for(const name of ["x","y","width","height"])el["crop-"+name].addEventListener("change",()=>{
    if(!allowed()||busy)return;
    if(!area&&["x","y","width","height"].some(k=>el["crop-"+k].value===""))return;
    const next=Object.fromEntries(["x","y","width","height"].map(k=>[k,Number(el["crop-"+k].value)]));
    const page=state.pageInfo;
    if(!Object.values(next).every(Number.isInteger)||next.x<0||next.y<0||next.width<1||next.height<1
      ||next.width>page.coordinateWidth-next.x||next.height>page.coordinateHeight-next.y){
      toast("이미지 범위 안의 정수 좌표와 크기를 입력해 주세요.");paint();return;
    }
    area=next;active=false;paint();refresh();
  });
  async function save(){
    if(el["crop-save"].disabled||!allowed()||!area)return;
    const ticket=epoch,selected={...area},page=state.pageInfo,id=state.current.mediaId;
    const version=page.mediaVersion,space=page.coordinateSpaceId;
    busy=true;active=false;controller=new AbortController();refresh();
    try{
      const blob=await api("/api/v1/media/"+id+"/pages/"+page.page+"/crop",{method:"POST",
        json:{mediaVersion:version,coordinateSpaceId:space,...selected},signal:controller.signal,
        binary:true,maxBytes:state.bootstrap.limits.maxCropOutputBytes||16777216});
      if(ticket!==epoch)return;
      revoke();url=URL.createObjectURL(blob);
      const image=new Image();image.src=url;await image.decode();
      if(ticket!==epoch)return;
      if(image.naturalWidth!==selected.width||image.naturalHeight!==selected.height)throw new Error("INVALID_CROP");
      const link=document.createElement("a");link.href=url;link.download="imagegate-crop-p"+page.page+".png";
      document.body.append(link);link.click();link.remove();toast("선택 영역의 PNG 사본을 저장했습니다.");
    }catch(error){
      if(ticket!==epoch||error.name==="AbortError")return;
      revoke();
      if([401,403,404].includes(error.status))onFatal(error);
      else toast(error.status===409?"원본 상태가 변경되었습니다. 파일을 다시 선택해 주세요."
        :error.status===413?"저장 크기 제한을 초과했습니다. 더 작은 영역을 선택해 주세요."
        :error.status===429?"다른 저장 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요.":"선택 영역을 저장하지 못했습니다.");
    }finally{if(ticket===epoch){busy=false;controller=null;refresh();}}
  }
  el["crop-start"].addEventListener("click",start);
  el["crop-cancel"].addEventListener("click",()=>{clear();refresh();});
  el["crop-save"].addEventListener("click",save);
  document.addEventListener("keydown",event=>{
    if(event.key==="Escape"&&(active||area||busy)){event.preventDefault();clear();refresh();}
  },true);
  refresh();return {clear,refresh,getArea:()=>area?{...area}:null};
}
