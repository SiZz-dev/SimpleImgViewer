import {useEffect,useMemo,useRef,useState} from 'react';
import {createApi} from './api.js';
import {brief,describe} from './messages.js';
import {clamp} from './format.js';
import {incomingUrl,initialMedia,initialSession,invalidLaunch} from './launch.js';
import Header from './components/Header.jsx';
import FilePanel from './components/FilePanel.jsx';
import Viewport from './components/Viewport.jsx';
import PlaybackStage from './components/PlaybackStage.jsx';
import ToolsPanel,{EmptyTools,ImageProperties,PlaybackInfo,SaveBar} from './components/ToolsPanel.jsx';
import CanvasToolbar from './components/CanvasToolbar.jsx';
import MobileBar from './components/MobileBar.jsx';
import Lens from './components/Lens.jsx';
import UrlDialog from './components/UrlDialog.jsx';
import Toast from './components/Toast.jsx';
import ConfirmDialog from './components/ConfirmDialog.jsx';
import ShortcutHelp from './components/ShortcutHelp.jsx';
import DropOverlay from './components/DropOverlay.jsx';

// Below this width the side panels become bottom sheets opened from MobileBar (matches viewer.css).
const narrowQuery='(max-width:760px)';
const isNarrow=()=>matchMedia(narrowQuery).matches;

// Owns viewer state and workflows (select, upload/import, delete, edit, session position).
// Components under ./components only render from props and report user actions back.
export default function App(){
  const [bootstrap,setBootstrap]=useState(null),[files,setFiles]=useState([]),[current,setCurrent]=useState(null),[page,setPage]=useState(1),[info,setInfo]=useState(null),[playback,setPlayback]=useState(null);
  const [loading,setLoading]=useState(false),[error,setError]=useState(''),[theme,setTheme]=useState(()=>{try{return localStorage.getItem('simpleimgviewer.theme')==='light'?'light':'dark';}catch{return 'dark';}});
  const [narrow,setNarrow]=useState(isNarrow),[left,setLeft]=useState(()=>!isNarrow()),[right,setRight]=useState(()=>!isNarrow()&&innerWidth>1000),[zoom,setZoom]=useState(1),[angle,setAngle]=useState(0),[pan,setPan]=useState({x:0,y:0}),[brightness,setBrightness]=useState(100),[magnifier,setMagnifier]=useState(false),[lens,setLens]=useState(null);
  const [tool,setTool]=useState('move'),[document,setDocument]=useState(null),[selected,setSelected]=useState(null),[noteText,setNoteText]=useState('메모'),[stamps,setStamps]=useState([]),[stampId,setStampId]=useState(''),[crop,setCrop]=useState(null);
  const [urlOpen,setUrlOpen]=useState(false),[notice,setNotice]=useState(''),[toast,setToast]=useState(null),[confirmRequest,setConfirmRequest]=useState(null),[helpOpen,setHelpOpen]=useState(false);
  const csrf=useRef(null),viewport=useRef(null),fileInput=useRef(null),stampInput=useRef(null),stampSelect=useRef(null),noteInput=useRef(null),player=useRef(null),abort=useRef(null),drag=useRef(null),keyHandler=useRef(null),keyUpHandler=useRef(null);
  // nudge: an annotation moved by arrow keys, saved once the key is released. touches/lastTap: pinch and double-tap (M2).
  const nudge=useRef(null),nudgeTimer=useRef(null),touches=useRef(new Map()),lastTap=useRef(null),dragDepth=useRef(0),editQueue=useRef(Promise.resolve()),lastNoteDown=useRef(null);
  // Latest annotation document, updated as soon as a save returns, so a save that starts before React
  // re-renders still sends the current revision (avoids false EDIT_CONFLICT on quick successive edits).
  const docRef=useRef(null);
  const [dropping,setDropping]=useState(false);
  const autoFit=useRef(true),lastTime=useRef(0),sessionRef=useRef(initialSession),currentRef=useRef(null);
  const imageReady=current?.mediaType==='image'&&info&&!loading,features=bootstrap?.features||{};
  // Seek/speed switches are viewer UX limits; Range delivery stays available for playback itself.
  const isVideo=current?.mediaType==='video',seekAllowed=isVideo?features.videoSeek:features.audioSeek,speedAllowed=isVideo?features.videoPlaybackSpeed:features.audioPlaybackSpeed;
  const mediaProps={ref:player,src:playback?.url,controls:true,preload:'metadata',controlsList:speedAllowed?undefined:'noplaybackrate',
    onLoadStart:()=>{lastTime.current=0;},onEnded:()=>{lastTime.current=0;},
    onPause:e=>saveSession(playbackPosition(e.currentTarget)),
    // Session restore applies the saved start time to both video and audio.
    onLoadedMetadata:e=>{lastTime.current=playback?.startTime||0;e.currentTarget.currentTime=lastTime.current;},
    onTimeUpdate:e=>{if(!e.currentTarget.seeking)lastTime.current=e.currentTarget.currentTime;},
    onSeeking:e=>{const media=e.currentTarget;if(!seekAllowed&&Math.abs(media.currentTime-lastTime.current)>0.5)media.currentTime=lastTime.current;},
    onRateChange:e=>{if(!speedAllowed&&e.currentTarget.playbackRate!==1)e.currentTarget.playbackRate=1;}};
  const api=useMemo(()=>createApi(csrf),[]);
  // Best effort: a failed position save must never interrupt viewing.
  function saveSession(state,keepalive=false){const id=sessionRef.current;if(!id||!csrf.current)return;api('/api/v1/viewer/sessions/'+id,{method:'PATCH',json:state,keepalive}).catch(()=>{});}
  const playbackPosition=media=>({selectedMediaId:currentRef.current.mediaId,initialPage:1,initialTimeSeconds:media.ended?0:clamp(Math.floor(media.currentTime||0),0,7200)});
  // Two failure channels (U13): `run` is for showing a file — its failure replaces the stage with an error;
  // `act` is for actions on a shown file (edit, save, stamp) — its failure is a toast and the file stays visible.
  const report=e=>{if(e.name!=='AbortError')setError(describe(e.message));};
  const run=action=>Promise.resolve().then(action).catch(report);
  const notify=(type,text,action)=>setToast({type,text,action,id:Date.now()});
  const act=action=>Promise.resolve().then(action).catch(e=>{if(e.name!=='AbortError')notify('error',describe(e.message));});
  // A toast with an action (undo, choose a stamp) stays longer so there is time to reach the button.
  useEffect(()=>{if(!toast)return;const timer=setTimeout(()=>setToast(null),toast.action?10000:toast.type==='error'?8000:5000);return()=>clearTimeout(timer);},[toast]);
  useEffect(()=>{
    const down=event=>keyHandler.current?.(event),up=event=>keyUpHandler.current?.(event);
    addEventListener('keydown',down);addEventListener('keyup',up);return()=>{removeEventListener('keydown',down);removeEventListener('keyup',up);};
  },[]);
  useEffect(()=>{globalThis.document.documentElement.dataset.theme=theme;try{localStorage.setItem('simpleimgviewer.theme',theme);}catch{}},[theme]);
  useEffect(()=>{
    // Entering the narrow layout closes both sheets so the image stays visible (M1).
    const query=matchMedia(narrowQuery),change=()=>{setNarrow(query.matches);if(query.matches){setLeft(false);setRight(false);}};
    query.addEventListener('change',change);return()=>query.removeEventListener('change',change);
  },[]);
  useEffect(()=>{
    // A refresh while playing keeps the position; keepalive lets the request outlive the page.
    const hide=()=>{const media=player.current;if(media&&currentRef.current&&currentRef.current.mediaType!=='image'&&!media.paused)saveSession(playbackPosition(media),true);};
    addEventListener('pagehide',hide);return()=>removeEventListener('pagehide',hide);
  },[]);
  useEffect(()=>{
    if(!info||!viewport.current)return;const observer=new ResizeObserver(()=>{if(autoFit.current)fit(info);});observer.observe(viewport.current);return()=>observer.disconnect();
  },[info,angle]);
  useEffect(()=>{
    let alive=true;
    run(async()=>{
      const boot=await api('/api/v1/viewer/bootstrap');if(!alive)return;csrf.current=boot.csrf;setBootstrap(boot);
      if(boot.features.stamps)setStamps(await api('/api/v1/stamps'));
      if(invalidLaunch)throw Error('INVALID_LAUNCH_URL');
      if(incomingUrl!==null){await openUrl(incomingUrl);return;}
      if(initialSession){const session=await api('/api/v1/viewer/sessions/'+initialSession);setFiles(session.media);setTheme(session.theme);if(!isNarrow())setLeft(session.leftPanelOpen);await select(session.media.find(m=>m.mediaId===session.selectedMediaId)||session.media[0],session.initialPage,session.initialTimeSeconds,boot);}
      else if(initialMedia){const record=await api('/api/v1/media/'+initialMedia);setFiles([record]);await select(record,1,0,boot);}
    });
    return ()=>{alive=false;abort.current?.abort();};
  },[]);
  function fit(pageInfo=info,rotation=angle){
    if(!pageInfo||!viewport.current)return;autoFit.current=true;const rect=viewport.current.getBoundingClientRect();const swapped=rotation%180!==0;
    // Vertical reserve keeps the fitted image clear of the canvas toolbar (top) and page navigation (bottom).
    setZoom(Math.min(1,Math.max(.01,Math.min((rect.width-60)/(swapped?pageInfo.displayHeight:pageInfo.displayWidth),(rect.height-160)/(swapped?pageInfo.displayWidth:pageInfo.displayHeight)))));setPan({x:0,y:0});
  }
  function manualZoom(value){autoFit.current=false;setZoom(value);}
  // Wheel zoom keeps the image point under the cursor in place (H-a). viewRef carries zoom/pan between
  // wheel events that arrive before React re-renders. Pan is the image-center offset from the viewport
  // center, so scaling about the cursor offset c gives pan' = c - (c - pan) * zoom'/zoom (rotation-independent).
  const viewRef=useRef({zoom:1,pan:{x:0,y:0}});viewRef.current={zoom,pan};
  function wheelZoom(event){
    if(!imageReady||!features.zoom)return;
    const delta=event.deltaY*(event.deltaMode===1?33:event.deltaMode===2?400:1);
    const {zoom:from,pan:p}=viewRef.current,to=clamp(from*clamp(Math.exp(-delta*.002),.5,2),.01,16);if(to===from)return;
    const rect=viewport.current.getBoundingClientRect(),cx=event.clientX-rect.left-rect.width/2,cy=event.clientY-rect.top-rect.height/2,k=to/from;
    const next={x:cx-(cx-p.x)*k,y:cy-(cy-p.y)*k};viewRef.current={zoom:to,pan:next};autoFit.current=false;setZoom(to);setPan(next);
  }
  async function select(record,nextPage=1,startTime=0,boot=bootstrap){
    if(!record)return;abort.current?.abort();const controller=new AbortController();abort.current=controller;
    currentRef.current=record;setCurrent(record);setPage(nextPage);setInfo(null);setPlayback(null);setDocument(null);setCrop(null);setSelected(null);setError('');setLoading(true);setAngle(0);setBrightness(100);setTool('move');
    try{
      if(record.mediaType==='image'){
        const p=await api('/api/v1/media/'+record.mediaId+'/pages/'+nextPage,{signal:controller.signal});setInfo(p);setZoom(1);setPan({x:0,y:0});
        if(nextPage===1)setFiles(previous=>previous.map(file=>file.mediaId===record.mediaId?{...file,thumbnailUrl:p.thumbnail.url}:file));
        autoFit.current=true;requestAnimationFrame(()=>fit(p,0));
        if(boot?.features.annotations||boot?.features.stamps||!boot)setDocument(await api('/api/v1/media/'+record.mediaId+'/pages/'+nextPage+'/annotations',{signal:controller.signal}));
      }else{
        const p=await api('/api/v1/media/'+record.mediaId+'/playback',{method:'POST',signal:controller.signal});
        if(p.state==='disabled')throw Error('PLAYBACK_DISABLED');if(p.state!=='ready')throw Error('PLAYBACK_FAILED');
        setPlayback({...p,startTime});
      }
      if(!controller.signal.aborted)saveSession({selectedMediaId:record.mediaId,initialPage:nextPage,initialTimeSeconds:clamp(startTime,0,7200)});
    }finally{if(!controller.signal.aborted)setLoading(false);}
  }
  async function sessionFor(records){const s=await api('/api/v1/viewer/sessions',{method:'POST',json:{mediaIds:records.map(f=>f.mediaId),theme,leftPanelOpen:left}});sessionRef.current=s.sessionId;history.replaceState(null,'','/viewer?sessionId='+s.sessionId);}
  // Shared by the file picker and drag-and-drop (U17).
  async function openFiles(chosen){
    if(!chosen.length)return;
    setLoading(true);setError('');setNotice('');try{
      if(files.length+chosen.length>100)throw Error('FILE_COUNT_LIMIT');const added=[],failed=[];
      // One rejected file must not hide the files that were already stored.
      for(const file of chosen){const form=new FormData();form.append('file',file);try{added.push(await api('/api/v1/media',{method:'POST',body:form}));}catch(e){failed.push(e);}}
      if(!added.length)throw failed[0];
      const all=[...files,...added];setFiles(all);
      if(failed.length){setLeft(true);setNotice(`${chosen.length}개 중 ${failed.length}개를 열지 못했습니다. `+brief(failed[0].message));}
      await sessionFor(all);await select(added[0]);
    }finally{setLoading(false);}
  }
  async function openUrl(value){
    setLoading(true);setError('');setNotice('');setUrlOpen(false);try{const file=await api('/api/v1/media/import-url',{method:'POST',json:{url:value}});const all=[...files,file];setFiles(all);await sessionFor(all);await select(file);}finally{setLoading(false);}
  }
  function removeFile(file){
    setConfirmRequest({title:`'${file.fileName}' 파일을 삭제할까요?`,body:'원본과 이 파일에 놓은 메모·스탬프가 함께 삭제되며 되돌릴 수 없습니다.',confirmLabel:'삭제',onConfirm:()=>deleteFile(file)});
  }
  async function deleteFile(file){
    setNotice('');
    // Delete failures (e.g. MEDIA_BUSY) are shown in the file panel so the current view stays usable.
    try{await api('/api/v1/media/'+file.mediaId,{method:'DELETE'});}catch(e){setLeft(true);setNotice('삭제하지 못했습니다. '+brief(e.message));return;}
    notify('success',`'${file.fileName}' 파일을 삭제했습니다.`);
    const index=files.findIndex(f=>f.mediaId===file.mediaId),remaining=files.filter(f=>f.mediaId!==file.mediaId);setFiles(remaining);
    if(remaining.length)await sessionFor(remaining).catch(()=>{});else{sessionRef.current=null;history.replaceState(null,'','/viewer');}
    if(currentRef.current?.mediaId!==file.mediaId)return;
    if(remaining.length)return run(()=>select(remaining[Math.min(index,remaining.length-1)]));
    abort.current?.abort();currentRef.current=null;setCurrent(null);setInfo(null);setPlayback(null);setDocument(null);setCrop(null);setSelected(null);setError('');setLoading(false);
  }
  function point(event){
    const rect=viewport.current.getBoundingClientRect(),r=-angle*Math.PI/180;
    const x=(event.clientX-rect.left-rect.width/2-pan.x)/zoom,y=(event.clientY-rect.top-rect.height/2-pan.y)/zoom;
    return {x:clamp((x*Math.cos(r)-y*Math.sin(r)+info.displayWidth/2)*info.coordinateWidth/info.displayWidth,0,info.coordinateWidth),
      y:clamp((x*Math.sin(r)+y*Math.cos(r)+info.displayHeight/2)*info.coordinateHeight/info.displayHeight,0,info.coordinateHeight)};
  }
  const annotationsPath=()=>'/api/v1/media/'+current.mediaId+'/pages/'+page+'/annotations';
  docRef.current=document;
  const binding=()=>({mediaVersion:info.mediaVersion,coordinateSpaceId:info.coordinateSpaceId,expectedRevision:docRef.current?.revision||0});
  const applyDocument=result=>{docRef.current=result;setDocument(result);};
  // Edits go out one at a time: each request reads docRef's revision only after the previous one has
  // answered, so quick successive edits (double clicks, drag after drag, undo during a save) never send a
  // stale expectedRevision and hit EDIT_CONFLICT.
  const queueEdit=work=>{const next=editQueue.current.then(work);editQueue.current=next.catch(()=>{});return next;};
  const saveItem=(item,action='create')=>queueEdit(async()=>{
    const result=await api(annotationsPath()+(action==='create'?'':'/'+item.id),{method:action==='create'?'POST':action==='delete'?'DELETE':'PUT',json:{...binding(),...item}});applyDocument(result);setSelected(action==='delete'?null:item.id||result.items.at(-1)?.id);
  });
  // Capture can throw when the pointer is already gone (fast release, synthetic events); the gesture still works without it.
  const capturePointer=event=>{try{viewport.current.setPointerCapture(event.pointerId);}catch{}};
  // Touch (M2): a second finger turns the gesture into a pinch; a quick double tap in move mode fits the image.
  const touchSpread=()=>{const [a,b]=[...touches.current.values()];return {distance:Math.hypot(a.x-b.x,a.y-b.y),x:(a.x+b.x)/2,y:(a.y+b.y)/2};};
  function pointerDown(event){
    if(!imageReady||event.button!==0)return;capturePointer(event);
    if(event.pointerType==='touch'){
      touches.current.set(event.pointerId,{x:event.clientX,y:event.clientY});
      if(touches.current.size===2){drag.current={kind:'pinch',start:touchSpread(),zoom,pan};return;}
      if(tool==='move'){const now=Date.now(),last=lastTap.current;
        if(last&&now-last.time<300&&Math.hypot(last.x-event.clientX,last.y-event.clientY)<30){lastTap.current=null;fit();return;}
        lastTap.current={time:now,x:event.clientX,y:event.clientY};}
    }
    const p=point(event);
    if(tool==='crop'){setCrop({x:p.x,y:p.y,width:0,height:0});drag.current={kind:'crop',start:p};}
    else if(tool==='note'||tool==='stamp'){
      if(tool==='stamp'&&!stampId){askForStamp();setTool('move');return;}
      const stamp=stamps.find(s=>s.stampId===stampId),width=Math.min(200,info.coordinateWidth),height=Math.min(tool==='stamp'?width*(stamp?.height||1)/(stamp?.width||1):100,info.coordinateHeight);
      // The clicked point becomes the item's center (H-c), kept inside the image.
      act(()=>saveItem({type:tool,text:noteText,stampId:tool==='stamp'?stampId:null,x:clamp(p.x-width/2,0,info.coordinateWidth-width),y:clamp(p.y-height/2,0,info.coordinateHeight-height),width,height}));setTool('move');
    }else{drag.current={kind:'pan',client:{x:event.clientX,y:event.clientY},pan};setSelected(null);}
  }
  function pointerMove(event){
    if(!imageReady)return;
    if(touches.current.has(event.pointerId))touches.current.set(event.pointerId,{x:event.clientX,y:event.clientY});
    const d=drag.current;
    if(d?.kind==='pinch'){
      if(touches.current.size<2)return;const now=touchSpread();autoFit.current=false;
      if(features.zoom&&d.start.distance>0)setZoom(clamp(d.zoom*now.distance/d.start.distance,.01,16));
      setPan({x:d.pan.x+now.x-d.start.x,y:d.pan.y+now.y-d.start.y});return;
    }
    const p=point(event);
    if(magnifier)setLens({x:event.clientX,y:event.clientY,point:p});
    if(!d)return;
    if(d.kind==='pan'){autoFit.current=false;setPan({x:d.pan.x+event.clientX-d.client.x,y:d.pan.y+event.clientY-d.client.y});}
    if(d.kind==='crop')setCrop({x:Math.min(p.x,d.start.x),y:Math.min(p.y,d.start.y),width:Math.abs(p.x-d.start.x),height:Math.abs(p.y-d.start.y)});
    if(d.kind==='annotation'){
      const item={...d.item,x:clamp(d.item.x+p.x-d.start.x,0,info.coordinateWidth-d.item.width),y:clamp(d.item.y+p.y-d.start.y,0,info.coordinateHeight-d.item.height)};
      // A click without movement only selects; it must not save (and bump the revision).
      d.changed=item.x===d.item.x&&item.y===d.item.y?null:item;
      setDocument(doc=>({...doc,items:doc.items.map(i=>i.id===item.id?item:i)}));
    }
  }
  function pointerUp(event){
    touches.current.delete(event?.pointerId);const d=drag.current;
    // A pinch ends when either finger lifts; the remaining finger does not start a pan.
    if(d?.kind==='pinch'){if(touches.current.size<2)drag.current=null;return;}
    // Fast drags can end before the last pointermove reaches the release point; apply it so the crop
    // region or moved item matches where the pointer was released.
    if(event?.type==='pointerup'&&(d?.kind==='crop'||d?.kind==='annotation'))pointerMove(event);
    drag.current=null;if(d?.kind==='annotation'&&d.changed)act(()=>saveItem(d.changed,'replace'));if(d?.kind==='crop')setTool('move');
  }
  function annotationDown(event,item){
    event.stopPropagation();if(!imageReady)return;
    // Pointer capture sends the browser's dblclick to the viewport, so a second press on the same note
    // within 400 ms is detected here (mouse and touch alike) and opens the text for editing (H-b).
    const now=Date.now(),last=lastNoteDown.current;lastNoteDown.current=item.type==='note'?{id:item.id,time:now}:null;
    if(item.type==='note'&&last?.id===item.id&&now-last.time<400){lastNoteDown.current=null;drag.current=null;editNote(item);return;}
    setSelected(item.id);if(item.type==='note')setNoteText(item.text||'');capturePointer(event);drag.current={kind:'annotation',item,start:point(event)};
  }
  async function uploadStamp(event){const file=event.target.files?.[0];event.target.value='';if(!file)return;const form=new FormData();form.append('file',file);const stamp=await api('/api/v1/stamps',{method:'POST',body:form});setStamps(await api('/api/v1/stamps'));setStampId(stamp.stampId);setTool('stamp');}
  const historyChange=direction=>queueEdit(async()=>{applyDocument(await api(annotationsPath()+'/history/'+direction,{method:'POST',json:binding()}));setSelected(null);});
  async function download(edited){
    const cropped=!!(crop&&crop.width>=1&&crop.height>=1),region=cropped?crop:{x:0,y:0,width:info.coordinateWidth,height:info.coordinateHeight};
    const blob=await api('/api/v1/media/'+current.mediaId+'/pages/'+page+'/'+(edited?'export':'crop'),{method:'POST',json:{...binding(),...region,brightness:features.brightness?brightness:100,rotation:features.rotation?angle:0,includeAnnotations:true},binary:true});
    // H-e: name the download after the original so several saves stay distinguishable.
    const base=current.fileName.replace(/\.[^.]*$/,'').replace(/[\\/:*?"<>|\u0000-\u001f]+/g,'_').trim().slice(0,80)||'image';
    const name=base+(edited?'-편집본':'-원본')+(cropped?'-부분':'')+'.png';
    const url=URL.createObjectURL(blob),a=globalThis.document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
    notify('success',(edited?'편집본을 저장했습니다':'원본에서 잘라 저장했습니다(메모·스탬프 제외)')+' · '+name);
  }
  const visibleItems=(document?.items||[]).filter(item=>features[item.type==='stamp'?'stamps':'annotations']);
  const selectedItem=visibleItems.find(i=>i.id===selected);
  // On narrow screens the panels are mutually exclusive bottom sheets.
  const toggleLeft=()=>{setLeft(!left);if(narrow)setRight(false);},toggleRight=()=>{setRight(!right);if(narrow)setLeft(false);};
  const isImage=current?.mediaType==='image';
  const toolAllowed={move:true,note:features.annotations,stamp:features.stamps,crop:features.cropAndSave};
  // U10: the stamp tool needs a chosen stamp, so ask for one before the click instead of failing after it.
  function askForStamp(){notify('info','놓을 스탬프를 먼저 고르거나 올려 주세요.',{label:'스탬프 고르기',run:()=>{setRight(true);if(narrow)setLeft(false);requestAnimationFrame(()=>stampSelect.current?.focus());}});}
  function chooseTool(next){if(!toolAllowed[next])return;if(next==='stamp'&&!stampId){askForStamp();return;}setTool(next);}
  // A1: keyboard editing of notes/stamps. Focus selects; arrows move in screen direction (rotation and zoom
  // are converted to original pixels like point()); the move is saved once when the key is released.
  function annotationFocus(item){setSelected(item.id);if(item.type==='note')setNoteText(item.text||'');}
  // H-b: double-click (or Enter on a focused note) jumps to the note text in the properties panel.
  function editNote(item){
    if(item.type!=='note'||!features.annotations)return;setSelected(item.id);setNoteText(item.text||'');setRight(true);if(narrow)setLeft(false);
    requestAnimationFrame(()=>{noteInput.current?.focus();noteInput.current?.select();});
  }
  // H-f: deleting says what went and offers the undo right there.
  function removeItem(item){
    return act(async()=>{await saveItem(item,'delete');notify('info',(item.type==='note'?'메모':'스탬프')+'를 지웠습니다.',{label:'실행 취소',run:()=>act(()=>historyChange('undo'))});});
  }
  function nudgeSelected(dx,dy){
    const item=nudge.current||visibleItems.find(i=>i.id===selected);if(!item)return;
    const r=-angle*Math.PI/180,sx=info.coordinateWidth/(info.displayWidth*zoom),sy=info.coordinateHeight/(info.displayHeight*zoom);
    const moved={...item,x:clamp(item.x+(dx*Math.cos(r)-dy*Math.sin(r))*sx,0,info.coordinateWidth-item.width),y:clamp(item.y+(dx*Math.sin(r)+dy*Math.cos(r))*sy,0,info.coordinateHeight-item.height)};
    nudge.current=moved;setDocument(doc=>({...doc,items:doc.items.map(i=>i.id===moved.id?moved:i)}));
  }
  // Save once the arrows have been idle for 400 ms, so a burst of key presses becomes one revision.
  keyUpHandler.current=event=>{
    if(!event.key.startsWith('Arrow')||!nudge.current)return;clearTimeout(nudgeTimer.current);
    nudgeTimer.current=setTimeout(()=>{const moved=nudge.current;nudge.current=null;if(moved)act(()=>saveItem(moved,'replace'));},400);
  };
  // U17: drop files anywhere on the viewer. dragDepth ignores enter/leave pairs from child elements.
  const hasFiles=event=>[...(event.dataTransfer?.types||[])].includes('Files');
  const dropHandlers={
    onDragEnter:event=>{if(!hasFiles(event))return;event.preventDefault();dragDepth.current++;setDropping(true);},
    onDragOver:event=>{if(!hasFiles(event))return;event.preventDefault();event.dataTransfer.dropEffect=bootstrap&&!loading?'copy':'none';},
    onDragLeave:event=>{if(!hasFiles(event))return;dragDepth.current=Math.max(0,dragDepth.current-1);if(!dragDepth.current)setDropping(false);},
    onDrop:event=>{if(!hasFiles(event))return;event.preventDefault();dragDepth.current=0;setDropping(false);
      if(!bootstrap||loading){notify('info','지금은 파일을 받을 수 없습니다. 잠시 후 다시 놓아 주세요.');return;}
      const chosen=[...event.dataTransfer.files];if(chosen.length)run(()=>openFiles(chosen));},
  };
  // Keyboard shortcuts (U14, U5); listed for users in components/ShortcutHelp.jsx.
  keyHandler.current=function handleKey(event){
    if(event.defaultPrevented||event.altKey||event.metaKey||confirmRequest||urlOpen)return;
    if(event.target.closest?.('input,textarea,select,[contenteditable="true"]'))return;
    const key=event.key;
    if(key==='?'){event.preventDefault();setHelpOpen(open=>!open);return;}
    if(helpOpen)return;
    if(key==='Escape'){if(tool!=='move')setTool('move');else if(selected){setSelected(null);if(globalThis.document.activeElement?.classList.contains('annotation'))globalThis.document.activeElement.blur();}else if(crop)setCrop(null);return;}
    if(!imageReady)return;
    if(event.ctrlKey){
      const lower=key.toLowerCase(),redo=lower==='y'||lower==='z'&&event.shiftKey;
      if(lower==='z'&&!event.shiftKey&&document?.history?.canUndo){event.preventDefault();act(()=>historyChange('undo'));}
      else if(redo&&document?.history?.canRedo){event.preventDefault();act(()=>historyChange('redo'));}
      return;
    }
    // With a note/stamp selected, arrows move it (Shift: larger steps) and Delete removes it; otherwise arrows page.
    if(selectedItem&&key.startsWith('Arrow')){event.preventDefault();const step=event.shiftKey?50:10;
      const [dx,dy]={ArrowLeft:[-step,0],ArrowRight:[step,0],ArrowUp:[0,-step],ArrowDown:[0,step]}[key];nudgeSelected(dx,dy);return;}
    if(selectedItem&&(key==='Delete'||key==='Backspace')){event.preventDefault();removeItem(selectedItem);return;}
    if(selectedItem?.type==='note'&&key==='Enter'&&event.target.classList?.contains('annotation')){event.preventDefault();editNote(selectedItem);return;}
    const zoomBy=factor=>{if(features.zoom){event.preventDefault();manualZoom(z=>clamp(z*factor,.01,16));}};
    switch(key){
      case '+':case '=':zoomBy(1.25);break;
      case '-':case '_':zoomBy(1/1.25);break;
      case '0':fit();break;
      case 'r':case 'R':if(features.rotation)setAngle(value=>(value+(event.shiftKey?270:90))%360);break;
      case 'ArrowLeft':if(page>1){event.preventDefault();run(()=>select(current,page-1));}break;
      case 'ArrowRight':if(page<(info.pageCount||1)){event.preventDefault();run(()=>select(current,page+1));}break;
      default:{const next={v:'move',n:'note',s:'stamp',c:'crop'}[key.toLowerCase()];if(next)chooseTool(next);}
    }
  };
  return <div className={'app'+(narrow?' narrow':'')} {...dropHandlers}>
    <Header narrow={narrow} left={left} onToggleLeft={toggleLeft} onToggleRight={toggleRight} current={current} imageReady={imageReady} features={features}
      zoom={zoom} onZoom={manualZoom} onFit={()=>fit()} onRotate={delta=>setAngle((angle+delta)%360)} magnifier={magnifier} onToggleMagnifier={()=>{setMagnifier(!magnifier);setLens(null);}}
      theme={theme} onToggleTheme={()=>setTheme(theme==='dark'?'light':'dark')} onHelp={()=>setHelpOpen(true)} canOpen={!!bootstrap&&!loading} canImportUrl={!!bootstrap?.features.remoteImport&&!loading}
      fileInput={fileInput} onFilesChosen={event=>{const chosen=Array.from(event.target.files||[]);event.target.value='';run(()=>openFiles(chosen));}} onOpenUrl={()=>setUrlOpen(true)}/>
    <main className="workspace">
      {left&&<FilePanel files={files} current={current} notice={notice} loading={loading} onSelect={file=>{if(narrow)setLeft(false);run(()=>select(file));}} onRemove={removeFile}/>}
      <Viewport viewportRef={viewport} tool={tool} onPointerDown={pointerDown}
        toolbar={<CanvasToolbar tool={tool} onTool={chooseTool} features={features} history={document?.history} onHistory={direction=>act(()=>historyChange(direction))}/>} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerLeave={()=>setLens(null)}
        onWheel={wheelZoom}
        current={current} info={info} imageReady={imageReady} pan={pan} zoom={zoom} angle={angle} brightness={brightness} items={visibleItems} selected={selected} crop={crop} onAnnotationDown={annotationDown}
        onAnnotationFocus={annotationFocus} canOpen={!!bootstrap&&!loading} onOpenFiles={()=>fileInput.current?.click()} maxUploadBytes={bootstrap?.limits?.maxUploadBytes}
        loading={loading} error={error} onRetry={()=>run(()=>select(current,page))} page={page} onPage={next=>run(()=>select(current,next))}>
        {playback&&!loading&&<PlaybackStage current={current} mediaProps={mediaProps}/>}
      </Viewport>
      {right&&<ToolsPanel title={!current?'도구':isImage?'속성':'재생 정보'}
        footer={isImage&&<SaveBar imageReady={imageReady} features={features} crop={crop} onClearCrop={()=>setCrop(null)} onDownload={edited=>act(()=>download(edited))}/>}>
        {!current?<EmptyTools/>:playback?<PlaybackInfo playback={playback} player={player} speedAllowed={speedAllowed} seekAllowed={seekAllowed}/>:isImage?
        <ImageProperties ready={!!bootstrap} imageReady={imageReady} features={features} current={current} info={info} brightness={brightness} onBrightness={setBrightness}
          noteText={noteText} onNoteText={setNoteText} noteInput={noteInput} selectedItem={selectedItem} revision={document?.revision} itemCount={visibleItems.length}
          onSaveItem={(item,action)=>action==='delete'?removeItem(item):act(()=>saveItem(item,action))}
          stamps={stamps} stampId={stampId} onStampId={setStampId} stampInput={stampInput} stampSelect={stampSelect} onStampChosen={event=>act(()=>uploadStamp(event))}/>:null}</ToolsPanel>}
    </main>
    {narrow&&<MobileBar left={left} right={right} current={current} onFiles={toggleLeft} onTools={toggleRight}/>}
    {magnifier&&lens&&imageReady&&<Lens lens={lens} info={info} brightness={brightness}/>}
    <UrlDialog open={urlOpen} onClose={()=>setUrlOpen(false)} onSubmit={value=>run(()=>openUrl(value))}/>
    <ConfirmDialog request={confirmRequest} onCancel={()=>setConfirmRequest(null)}/>
    <ShortcutHelp open={helpOpen} onClose={()=>setHelpOpen(false)}/>
    <Toast toast={toast} onClose={()=>setToast(null)}/>
    {dropping&&<DropOverlay/>}
  </div>;
}
