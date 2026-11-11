export function createExportUI({state,api,toast,onFatal,viewport,editor,cropper}){
  const $=id=>document.getElementById(id);
  const el=Object.fromEntries(["export-notes","export-brightness","export-rotation","export-full","export-region","export-hint"].map(id=>[id,$(id)]));
  let busy=false,epoch=0,controller=null,url=null;
  const annotationsEnabled=()=>Boolean(state.bootstrap?.features.annotations||state.bootstrap?.features.stamps);
  const allowed=()=>Boolean(state.ready&&state.pageInfo?.originalCoordinatesAvailable&&state.bootstrap?.features.exportEdited
    &&state.current?.permissions?.exportEdited);
  function refresh(){
    const enabled=allowed(),ann=editor.exportState(),include=annotationsEnabled()&&el["export-notes"].checked;
    const max=state.bootstrap?.limits.maxCropOutputPixels||8000000,page=state.pageInfo,area=cropper.getArea();
    const blocked=busy||!enabled||(include&&(!ann.ready||ann.dirty));
    el["export-notes"].disabled=busy||!enabled||!annotationsEnabled();
    el["export-brightness"].disabled=busy||!enabled||!state.bootstrap?.features.brightness;
    el["export-rotation"].disabled=busy||!enabled||!state.bootstrap?.features.rotation;
    el["export-full"].disabled=blocked||!page||page.coordinateWidth*page.coordinateHeight>max;
    el["export-region"].disabled=blocked||!area||area.width*area.height>max
      ||!state.bootstrap?.features.cropAndSave||!state.current?.permissions?.cropAndSave;
    el["export-hint"].textContent=busy?"원본 해상도로 편집 사본을 준비하고 있습니다."
      :!enabled?"원본 좌표와 편집 반출 권한이 확인된 이미지에서 사용할 수 있습니다."
      :include&&ann.dirty?"메모 변경 내용을 먼저 적용하거나 메모·스탬프 포함을 해제하세요."
      :include&&!ann.ready?"메모·스탬프 저장이 완료되면 다운로드할 수 있습니다."
      :page.coordinateWidth*page.coordinateHeight>max?"전체 페이지가 저장 상한을 초과합니다. 작은 영역을 선택해 저장하세요."
      :"저장된 메모·스탬프를 합성합니다. 선택 테두리와 조절 핸들은 저장하지 않습니다.";
  }
  function revoke(){if(url)URL.revokeObjectURL(url);url=null;}
  function clear(){epoch++;controller?.abort();controller=null;busy=false;revoke();}
  async function save(region){
    if((region?el["export-region"]:el["export-full"]).disabled||!allowed())return;
    const page=state.pageInfo,area=region?cropper.getArea():{x:0,y:0,width:page.coordinateWidth,height:page.coordinateHeight};
    if(!area)return;
    const ann=editor.exportState(),include=annotationsEnabled()&&el["export-notes"].checked;
    if(include&&(!ann.ready||ann.dirty))return;
    const rotation=el["export-rotation"].checked&&state.bootstrap.features.rotation?state.view.angle:0;
    const brightness=el["export-brightness"].checked&&state.bootstrap.features.brightness?state.brightness:100;
    const ticket=epoch,id=state.current.mediaId;
    busy=true;controller=new AbortController();refresh();
    try{
      const blob=await api("/api/v1/media/"+id+"/pages/"+page.page+"/export",{method:"POST",
        json:{mediaVersion:page.mediaVersion,coordinateSpaceId:page.coordinateSpaceId,...area,
          expectedRevision:include?ann.revision:0,includeAnnotations:include,brightness,rotation},
        signal:controller.signal,binary:true,maxBytes:state.bootstrap.limits.maxCropOutputBytes||16777216});
      if(ticket!==epoch)return;
      revoke();url=URL.createObjectURL(blob);
      const image=new Image();image.src=url;await image.decode();if(ticket!==epoch)return;
      const width=rotation%180?area.height:area.width,height=rotation%180?area.width:area.height;
      if(image.naturalWidth!==width||image.naturalHeight!==height)throw new Error("INVALID_EXPORT");
      const link=document.createElement("a");link.href=url;link.download="imagegate-edited-p"+page.page+".png";
      document.body.append(link);link.click();link.remove();toast("편집 내용을 합성한 PNG 사본을 저장했습니다.");
    }catch(error){
      if(ticket!==epoch||error.name==="AbortError")return;
      revoke();
      if([401,403,404].includes(error.status))onFatal(error);
      else toast(error.code==="EXPORT_FONT_UNAVAILABLE"?"서버에서 메모의 일부 글자를 표현할 수 없습니다. 관리자에게 서버 글꼴을 확인해 주세요.":error.status===409?"원본 또는 메모가 변경되었습니다. 파일을 다시 선택한 뒤 저장해 주세요."
        :error.status===413?"저장 크기 제한을 초과했습니다. 더 작은 영역을 선택해 주세요."
        :error.status===429?"다른 저장 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요.":"편집 이미지를 저장하지 못했습니다.");
    }finally{if(ticket===epoch){busy=false;controller=null;refresh();}}
  }
  el["export-full"].addEventListener("click",()=>save(false));el["export-region"].addEventListener("click",()=>save(true));
  for(const id of ["export-notes","export-brightness","export-rotation"])el[id].addEventListener("change",refresh);
  for(const event of ["imagegate:annotations","imagegate:cropchange"])viewport.addEventListener(event,refresh);
  document.addEventListener("keydown",event=>{if(event.key==="Escape"&&busy){clear();refresh();}});
  refresh();return {clear,refresh};
}
