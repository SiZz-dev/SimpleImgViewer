import {UUID,clamp,screenToImage,toOriginal,formatBytes} from "./viewer-core.js";

export function createEditingUI({state,api,toast,onFatal,viewport,overlay}){
  const $=id=>document.getElementById(id);
  const el=Object.fromEntries(["add-note","add-stamp","stamp-upload","stamp-input","stamp-select","stamp-preview",
    "editing-hint","editing-status","annotation-undo","annotation-redo","history-hint","annotation-list","annotation-count","annotation-editor","note-label","note-text",
    "save-note","annotation-width","annotation-height","save-size","delete-annotation"].map(id=>[id,$(id)]));
  let context=null,documentState=null,selected=null,mode=null,busy=false,epoch=0,library=[],drag=null;
  const selectedItem=()=>documentState?.items.find(i=>i.id===selected);
  const canType=type=>Boolean(context&&documentState&&state.ready&&state.pageInfo?.originalCoordinatesAvailable
    &&state.bootstrap?.features[type==="note"?"annotations":"stamps"]
    &&state.current?.permissions[type==="note"?"annotate":"stamp"]);
  const canEdit=item=>item&&canType(item.type);
  const root=ctx=>"/api/v1/media/"+ctx.id+"/pages/"+ctx.page+"/annotations";
  function assetUrl(asset){
    if(!UUID.test(asset.stampId)||asset.contentUrl!=="/api/v1/stamps/"+asset.stampId+"/content"
      ||!Number.isInteger(asset.width)||!Number.isInteger(asset.height)||asset.width<1||asset.height<1
      ||asset.width>2048||asset.height>2048)throw new Error("INVALID_STAMP");
    return asset.contentUrl;
  }
  function validateDoc(doc,ctx){
    if(doc.mediaVersion!==ctx.version||doc.coordinateSpaceId!==ctx.space||doc.page!==ctx.page
      ||!Number.isInteger(doc.revision)||doc.revision<0||!Array.isArray(doc.items)||doc.items.length>100)
      throw new Error("INVALID_ANNOTATIONS");
    if(doc.history&&(["enabled","canUndo","canRedo"].some(k=>typeof doc.history[k]!=="boolean")
      ||["undoDepth","redoDepth"].some(k=>!Number.isInteger(doc.history[k])||doc.history[k]<0||doc.history[k]>50)
      ||["undoType","redoType"].some(k=>doc.history[k]!=null&&!["note","stamp"].includes(doc.history[k]))))throw new Error("INVALID_ANNOTATIONS");
    const ids=new Set();
    for(const item of doc.items){
      if(!UUID.test(item.id)||ids.has(item.id)||!["note","stamp"].includes(item.type))throw new Error("INVALID_ANNOTATIONS");
      ids.add(item.id);
      if(![item.x,item.y,item.width,item.height].every(Number.isFinite)||item.x<0||item.y<0
        ||item.width<1||item.height<1||item.width>ctx.width-item.x||item.height>ctx.height-item.y)
        throw new Error("INVALID_ANNOTATIONS");
      if(item.type==="note"&&(typeof item.text!=="string"||item.text.length>2000))throw new Error("INVALID_ANNOTATIONS");
      if(item.type==="stamp"&&(!UUID.test(item.stampId)||item.stampUrl!==root(ctx)+"/"+item.id+"/stamp/content"))
        throw new Error("INVALID_ANNOTATIONS");
    }
    return doc;
  }
  function dirty(){const item=selectedItem();if(!item)return false;
    return (item.type==="note"&&el["note-text"].value!==item.text)
      ||Number(el["annotation-width"].value)!==Math.round(item.width*100)/100
      ||Number(el["annotation-height"].value)!==Math.round(item.height*100)/100;
  }
  function status(text){el["editing-status"].textContent=text;}
  function refresh(){
    el["add-note"].disabled=busy||!canType("note");
    el["add-stamp"].disabled=busy||!canType("stamp")||!library.length;
    el["stamp-upload"].disabled=busy||!canType("stamp")||!state.bootstrap?.features.stampUpload;
    el["stamp-select"].disabled=busy||!canType("stamp")||!library.length;
    el["add-note"].setAttribute("aria-pressed",String(mode==="note"));
    el["add-stamp"].setAttribute("aria-pressed",String(mode==="stamp"));
    viewport.classList.toggle("placing-annotation",Boolean(mode));
    const item=selectedItem(),editable=canEdit(item)&&!busy;
    el["annotation-editor"].hidden=!item;
    el["note-label"].hidden=item?.type!=="note";
    el["note-text"].hidden=item?.type!=="note";el["note-text"].readOnly=!editable;
    el["save-note"].hidden=item?.type!=="note";el["save-note"].disabled=!editable;
    for(const name of ["annotation-width","annotation-height","save-size","delete-annotation"])el[name].disabled=!editable;
    const history=documentState?.history,blocked=busy||Boolean(drag)||Boolean(mode)||dirty()||!state.ready||!context;
    el["annotation-undo"].disabled=blocked||!history?.enabled||!history.canUndo||!canType(history.undoType);
    el["annotation-redo"].disabled=blocked||!history?.enabled||!history.canRedo||!canType(history.redoType);
    el["history-hint"].textContent=dirty()?"적용 전 입력을 먼저 저장해 주세요.":history?.enabled
      ?"저장된 변경 · 취소 "+history.undoDepth+" / 다시 실행 "+history.redoDepth:"이 페이지에서 변경 기록을 사용할 수 없습니다.";
    const limits=state.bootstrap?.limits;
    el["note-text"].maxLength=limits?.maxNoteLength||2000;
    if(!state.ready||!state.pageInfo?.originalCoordinatesAvailable)
      el["editing-hint"].textContent="원본 좌표가 확인된 이미지에서 메모·스탬프를 사용할 수 있습니다.";
    else if(mode)el["editing-hint"].textContent=(mode==="note"?"메모":"스탬프")+"를 놓을 위치를 이미지에서 클릭하세요. Esc로 취소합니다.";
    else if(!(state.bootstrap?.features.annotations||state.bootstrap?.features.stamps))el["editing-hint"].textContent="설치 정책에서 메모·스탬프 기능이 비활성화되어 있습니다.";
    else if(!documentState)el["editing-hint"].textContent="메모·스탬프를 불러오고 있습니다.";
    else el["editing-hint"].textContent="클릭으로 추가 · 드래그로 이동 · 선택 후 Delete로 삭제";
    for(const node of overlay.children)node.classList.toggle("read-only",!canEdit(documentState?.items.find(i=>i.id===node.dataset.id)));
    viewport.dispatchEvent(new Event("imagegate:annotations"));
  }
  function exportState(){const item=selectedItem();return {ready:Boolean(documentState)&&!busy&&!drag&&!mode,revision:documentState?.revision??0,
    dirty:Boolean(item?.type==="note"&&el["note-text"].value!==item.text)};}
  el["note-text"].addEventListener("input",refresh);
  for(const name of ["annotation-width","annotation-height"])el[name].addEventListener("input",refresh);
  function setMode(value){mode=mode===value?null:value;if(mode)viewport.dispatchEvent(new CustomEvent("imagegate:tool",{detail:mode}));refresh();}
  viewport.addEventListener("imagegate:tool",event=>{if(event.detail==="crop"){mode=null;drag=null;refresh();}});
  function fillEditor(){
    const item=selectedItem();
    if(item){
      el["note-text"].value=item.text||"";
      el["annotation-width"].value=String(Math.round(item.width*100)/100);
      el["annotation-height"].value=String(Math.round(item.height*100)/100);
    }
    refresh();
  }
  function select(id){selected=id;mode=null;render();fillEditor();}
  function placeStyle(node,item){
    if(!context)return;
    node.style.left=item.x*context.sx+"px";node.style.top=item.y*context.sy+"px";
    node.style.width=item.width*context.sx+"px";node.style.height=item.height*context.sy+"px";
    node.style.fontSize=clamp(item.width/15,8,256)*context.sy+"px";
  }
  function render(){
    overlay.replaceChildren();el["annotation-list"].replaceChildren();
    for(const [index,item] of (documentState?.items||[]).entries()){
      const node=document.createElement("div");node.className="annotation annotation-"+item.type;
      node.dataset.id=item.id;node.tabIndex=0;node.setAttribute("role","group");
      node.setAttribute("aria-label",item.type==="note"?"메모: "+item.text:"이미지 스탬프");
      node.title=item.type==="note"?item.text:"이미지 스탬프";
      node.classList.toggle("selected",selected===item.id);placeStyle(node,item);
      if(item.type==="note"){
        const text=document.createElement("span");text.className="annotation-text";text.textContent=item.text;node.append(text);
      }else{
        const image=document.createElement("img");image.alt="사용자 스탬프";image.src=item.stampUrl;image.draggable=false;
        node.append(image);
      }
      const handle=document.createElement("span");handle.className="annotation-resize";handle.setAttribute("aria-hidden","true");node.append(handle);
      node.addEventListener("click",event=>{event.stopPropagation();if(!busy)select(item.id);});
      node.addEventListener("dblclick",event=>{event.stopPropagation();if(item.type==="note"){select(item.id);el["note-text"].focus();}});
      node.addEventListener("keydown",event=>{if(event.key==="Enter"){event.preventDefault();select(item.id);}});
      node.addEventListener("pointerdown",event=>beginDrag(event,item,node));
      node.addEventListener("pointermove",event=>moveDrag(event,node));
      node.addEventListener("pointerup",()=>finishDrag());
      node.addEventListener("pointercancel",()=>{drag=null;render();});
      overlay.append(node);
      const row=document.createElement("button");row.type="button";row.className="annotation-row";
      row.textContent=(index+1)+". "+(item.type==="note"?item.text:"이미지 스탬프");
      row.title=item.type==="note"?item.text:"이미지 스탬프";
      row.setAttribute("aria-pressed",String(selected===item.id));row.addEventListener("click",()=>select(item.id));
      el["annotation-list"].append(row);
    }
    el["annotation-count"].textContent=String(documentState?.items.length||0);refresh();
  }
  function clear({forget=false}={}){
    epoch++;context=null;documentState=null;selected=null;mode=null;busy=false;drag=null;
    overlay.replaceChildren();el["annotation-list"].replaceChildren();el["note-text"].value="";
    el["annotation-count"].textContent="0";status("메모·스탬프");
    if(forget){library=[];el["stamp-select"].replaceChildren();el["stamp-preview"].removeAttribute("src");el["stamp-preview"].hidden=true;}
    refresh();
  }
  function showLibrary(){
    const current=el["stamp-select"].value;el["stamp-select"].replaceChildren();
    for(const asset of library){
      const option=document.createElement("option");option.value=asset.stampId;option.textContent=asset.fileName;el["stamp-select"].append(option);
    }
    if(library.some(a=>a.stampId===current))el["stamp-select"].value=current;
    previewAsset();refresh();
  }
  function previewAsset(){
    const asset=library.find(a=>a.stampId===el["stamp-select"].value);
    if(asset){el["stamp-preview"].src=assetUrl(asset);el["stamp-preview"].hidden=false;}
    else{el["stamp-preview"].removeAttribute("src");el["stamp-preview"].hidden=true;}
  }
  async function load(page,file,signal){
    clear();
    if(!page.originalCoordinatesAvailable||!(state.bootstrap?.features.annotations||state.bootstrap?.features.stamps))return;
    const ticket=epoch;
    context={id:file.mediaId,page:page.page,version:page.mediaVersion,space:page.coordinateSpaceId,
      width:page.coordinateWidth,height:page.coordinateHeight,sx:page.displayWidth/page.coordinateWidth,sy:page.displayHeight/page.coordinateHeight};
    const ctx=context;status("불러오는 중");
    try{
      const doc=validateDoc(await api(root(ctx),{signal}),ctx);
      if(ticket!==epoch||signal.aborted)return;documentState=doc;status("저장됨");render();
      if(canType("stamp")){
        const assets=await api("/api/v1/stamps",{signal});
        if(ticket!==epoch||signal.aborted)return;
        if(!Array.isArray(assets)||assets.length>100)throw new Error("INVALID_STAMPS");
        for(const asset of assets)assetUrl(asset);
        library=assets;showLibrary();
      }
    }catch(error){
      if(error.name==="AbortError"||ticket!==epoch)return;
      if(error.status===401||error.status===403||(error.status===404&&error.code==="MEDIA_NOT_FOUND"))onFatal(error);
      else{status("불러오기 실패");el["editing-hint"].textContent="메모·스탬프를 불러오지 못했습니다. 파일을 다시 선택해 주세요.";toast("메모·스탬프를 불러오지 못했습니다.");}
    }
    refresh();
  }
  function snapshot(item){
    return {mediaVersion:context.version,coordinateSpaceId:context.space,expectedRevision:documentState.revision,
      type:item.type,x:item.x,y:item.y,width:item.width,height:item.height,
      ...(item.type==="note"?{text:item.text}:{stampId:item.stampId})};
  }
  async function write(method,item,id=null){
    if(busy||!context||!documentState)return;
    const ticket=epoch,ctx=context,draft=el["note-text"].value;busy=true;status("저장 중");refresh();
    const body=method==="DELETE"?{mediaVersion:ctx.version,coordinateSpaceId:ctx.space,expectedRevision:documentState.revision}:snapshot(item);
    try{
      const before=new Set(documentState.items.map(i=>i.id));
      const result=validateDoc(await api(root(ctx)+(id?"/"+id:""),{method,json:body}),ctx);
      if(ticket!==epoch)return;documentState=result;
      if(method==="POST")selected=result.items.find(i=>!before.has(i.id))?.id||null;
      if(method==="DELETE")selected=null;
      status("저장됨");render();fillEditor();
      if(method==="POST"&&item.type==="note"){el["note-text"].focus();el["note-text"].select();}
    }catch(error){
      if(ticket!==epoch)return;
      if(error.status===401||error.status===403||(error.status===404&&error.code==="MEDIA_NOT_FOUND"))onFatal(error);
      else{
        if(error.status===409||error.code==="ANNOTATION_NOT_FOUND"){
          try{const latest=validateDoc(await api(root(ctx)),ctx);if(ticket!==epoch)return;documentState=latest;render();fillEditor();
            if(selectedItem()?.type==="note")el["note-text"].value=draft;
          }catch{if(ticket!==epoch)return;documentState=null;}
          toast("다른 변경을 불러왔습니다. 내용을 확인하고 다시 저장해 주세요.");
        }else toast(error.status===429?"페이지 또는 스탬프 개수 제한에 도달했습니다.":"변경 사항을 저장하지 못했습니다. 다시 시도해 주세요.");
        status("저장 확인 필요");render();
      }
    }finally{if(ticket===epoch){busy=false;refresh();}}
  }
  async function travel(direction){
    const button=el[direction==="undo"?"annotation-undo":"annotation-redo"];
    if(button.disabled||busy||!context||!documentState)return;
    const ctx=context,ticket=epoch,before=new Set(documentState.items.map(i=>i.id));busy=true;status(direction==="undo"?"실행 취소 중":"다시 실행 중");refresh();
    try{
      const result=validateDoc(await api(root(ctx)+"/history/"+direction,{method:"POST",json:{mediaVersion:ctx.version,coordinateSpaceId:ctx.space,expectedRevision:documentState.revision}}),ctx);
      if(ticket!==epoch)return;documentState=result;
      if(!selectedItem())selected=result.items.find(i=>!before.has(i.id))?.id||null;
      status("저장됨");render();fillEditor();
    }catch(error){
      if(ticket!==epoch)return;
      if(error.status===401||error.status===403||(error.status===404&&error.code==="MEDIA_NOT_FOUND")){onFatal(error);return;}
      // A lost response may follow a committed mutation. Read current state; never replay automatically.
      try{const latest=validateDoc(await api(root(ctx)),ctx);if(ticket!==epoch)return;documentState=latest;
        if(!selectedItem())selected=null;render();fillEditor();
      }catch(reload){if(ticket!==epoch)return;documentState=null;selected=null;
        if(reload.status===401||reload.status===403||(reload.status===404&&reload.code==="MEDIA_NOT_FOUND")){onFatal(reload);return;}render();
      }
      status("변경 기록 확인 필요");toast(error.code==="EDIT_HISTORY_UNAVAILABLE"?"사용 가능한 변경 기록이 없습니다.":error.status===409?"다른 변경을 불러왔습니다. 내용을 확인해 주세요.":"처리 결과를 확인해 주세요. 자동으로 다시 실행하지 않습니다.");
    }finally{if(ticket===epoch){busy=false;refresh();}}
  }
  function point(event){
    if(!context)return null;
    const box=viewport.getBoundingClientRect();
    const pixel=screenToImage({x:event.clientX-box.left,y:event.clientY-box.top},state.view,box,
      {width:state.pageInfo.displayWidth,height:state.pageInfo.displayHeight});
    return toOriginal(pixel,state.pageInfo);
  }
  viewport.addEventListener("pointerdown",event=>{
    if(!mode||event.button!==0||event.target.closest("button,input,textarea,select,.page-navigation,.tile-status"))return;
    event.preventDefault();event.stopImmediatePropagation();
    if(busy||!canType(mode))return;const pos=point(event);
    if(!pos||pos.x<0||pos.y<0||pos.x>context.width||pos.y>context.height)return;
    const type=mode,asset=library.find(a=>a.stampId===el["stamp-select"].value);
    if(type==="stamp"&&!asset)return;
    let w=type==="note"?220/(state.view.scale*context.sx):140/(state.view.scale*context.sx);
    let h=type==="note"?90/(state.view.scale*context.sy):w*asset.height/asset.width;
    const shrink=Math.min(1,context.width/w,context.height/h);w=Math.max(1,w*shrink);h=Math.max(1,h*shrink);
    const item={type,x:clamp(pos.x,0,context.width-w),y:clamp(pos.y,0,context.height-h),width:w,height:h,
      ...(type==="note"?{text:"새 메모"}:{stampId:asset.stampId})};
    mode=null;refresh();write("POST",item);
  },true);
  function beginDrag(event,item,node){
    event.stopPropagation();if(busy||event.button!==0)return;
    selected=item.id;fillEditor();
    for(const child of overlay.children)child.classList.toggle("selected",child===node);
    if(!canEdit(item))return;
    const pos=point(event);if(!pos)return;
    drag={id:item.id,item:{...item},start:pos,client:{x:event.clientX,y:event.clientY},
      resize:event.target.classList.contains("annotation-resize"),draft:{...item},ticket:epoch,moved:false};
    node.setPointerCapture(event.pointerId);event.preventDefault();refresh();
  }
  function moveDrag(event,node){
    if(!drag||drag.id!==node.dataset.id||drag.ticket!==epoch)return;
    const pos=point(event);if(!pos)return;
    drag.moved=drag.moved||Math.hypot(event.clientX-drag.client.x,event.clientY-drag.client.y)>3;
    const dx=pos.x-drag.start.x,dy=pos.y-drag.start.y,item=drag.item,next={...item};
    if(drag.resize){
      next.width=clamp(item.width+dx,1,context.width-item.x);next.height=clamp(item.height+dy,1,context.height-item.y);
      if(item.type==="stamp"){
        const ratio=item.height/item.width;next.height=Math.max(1,next.width*ratio);
        if(next.height>context.height-item.y){next.height=context.height-item.y;next.width=Math.max(1,next.height/ratio);}
      }
    }else{next.x=clamp(item.x+dx,0,context.width-item.width);next.y=clamp(item.y+dy,0,context.height-item.height);}
    drag.draft=next;placeStyle(node,next);
  }
  function finishDrag(){
    if(!drag)return;const current=drag;drag=null;
    if(current.ticket===epoch){if(current.moved)write("PUT",current.draft,current.id);else{render();fillEditor();}}
  }
  async function upload(file){
    if(!file||el["stamp-upload"].disabled||!context)return;
    if(file.size>(state.bootstrap.limits.maxStampUploadBytes||5242880)){
      toast("스탬프 파일 크기는 "+formatBytes(state.bootstrap.limits.maxStampUploadBytes||5242880)+" 이하여야 합니다.");return;
    }
    const ticket=epoch,ctx=context;busy=true;status("업로드 중");refresh();
    try{
      const body=new FormData();body.append("file",file);
      const asset=await api("/api/v1/media/"+ctx.id+"/stamps",{method:"POST",body});assetUrl(asset);
      if(ticket!==epoch)return;library=[...library,asset];showLibrary();el["stamp-select"].value=asset.stampId;previewAsset();
      mode="stamp";viewport.dispatchEvent(new CustomEvent("imagegate:tool",{detail:"stamp"}));status("위치 선택");toast("이미지에서 스탬프를 놓을 위치를 클릭하세요.");
    }catch(error){
      if(ticket!==epoch)return;
      if(error.status===401||error.status===403||(error.status===404&&error.code==="MEDIA_NOT_FOUND"))onFatal(error);
      else toast(error.status===413?"스탬프 크기 제한을 초과했습니다.":error.status===429?"스탬프 개수 제한에 도달했습니다.":"PNG/JPG 이미지인지, 파일이 손상되지 않았는지 확인해 주세요.");
      status("업로드 확인 필요");
    }finally{if(ticket===epoch){busy=false;refresh();}el["stamp-input"].value="";}
  }
  el["add-note"].addEventListener("click",()=>{if(!el["add-note"].disabled)setMode("note");});
  el["add-stamp"].addEventListener("click",()=>{if(!el["add-stamp"].disabled)setMode("stamp");});
  el["stamp-upload"].addEventListener("click",()=>el["stamp-input"].click());
  el["stamp-input"].addEventListener("change",()=>upload(el["stamp-input"].files[0]));
  el["stamp-select"].addEventListener("change",()=>{previewAsset();if(canType("stamp"))setMode("stamp");});
  el["save-note"].addEventListener("click",()=>{
    const item=selectedItem();if(!canEdit(item)||!el["note-text"].value.trim())return;
    write("PUT",{...item,text:el["note-text"].value},item.id);
  });
  el["save-size"].addEventListener("click",()=>{
    const item=selectedItem(),width=Number(el["annotation-width"].value),height=Number(el["annotation-height"].value);
    if(!canEdit(item))return;
    if(!Number.isFinite(width)||!Number.isFinite(height)||width<1||height<1||width>context.width-item.x||height>context.height-item.y){
      toast("이미지 범위 안의 크기를 입력해 주세요.");return;
    }
    write("PUT",{...item,width,height},item.id);
  });
  el["delete-annotation"].addEventListener("click",()=>{const item=selectedItem();if(canEdit(item))write("DELETE",item,item.id);});
  el["annotation-undo"].addEventListener("click",()=>travel("undo"));
  el["annotation-redo"].addEventListener("click",()=>travel("redo"));
  document.addEventListener("keydown",event=>{
    if(event.isComposing||event.target.closest("input,textarea,select,[contenteditable=true]"))return;
    const key=event.key.toLowerCase();
    if(context&&documentState&&!event.altKey&&(event.ctrlKey||event.metaKey)&&(key==="z"||key==="y")){
      event.preventDefault();event.stopImmediatePropagation();if(!event.repeat)travel(key==="y"||event.shiftKey?"redo":"undo");return;
    }
    if(event.key==="Escape"){mode=null;selected=null;render();fillEditor();}
    if(event.key==="Delete"&&canEdit(selectedItem())){event.preventDefault();event.stopImmediatePropagation();write("DELETE",selectedItem(),selected);}
  },true);
  refresh();return {clear,load,refresh,exportState};
}
