import {useEffect,useMemo,useRef,useState} from 'react';
import {createApi} from './api.js';
import {brief,describe} from './messages.js';
import {clamp} from './format.js';
import {incomingUrl,initialMedia,initialSession,invalidLaunch} from './launch.js';
import Header from './components/Header.jsx';
import FilePanel from './components/FilePanel.jsx';
import Viewport from './components/Viewport.jsx';
import PlaybackStage from './components/PlaybackStage.jsx';
import ToolsPanel,{ImageTools,PlaybackInfo} from './components/ToolsPanel.jsx';
import Lens from './components/Lens.jsx';
import UrlDialog from './components/UrlDialog.jsx';

// Owns viewer state and workflows (select, upload/import, delete, edit, session position).
// Components under ./components only render from props and report user actions back.
export default function App(){
  const [bootstrap,setBootstrap]=useState(null),[files,setFiles]=useState([]),[current,setCurrent]=useState(null),[page,setPage]=useState(1),[info,setInfo]=useState(null),[playback,setPlayback]=useState(null);
  const [loading,setLoading]=useState(false),[error,setError]=useState(''),[theme,setTheme]=useState(()=>{try{return localStorage.getItem('simpleimgviewer.theme')==='light'?'light':'dark';}catch{return 'dark';}});
  const [left,setLeft]=useState(innerWidth>700),[right,setRight]=useState(innerWidth>1000),[zoom,setZoom]=useState(1),[angle,setAngle]=useState(0),[pan,setPan]=useState({x:0,y:0}),[brightness,setBrightness]=useState(100),[magnifier,setMagnifier]=useState(false),[lens,setLens]=useState(null);
  const [tool,setTool]=useState('move'),[document,setDocument]=useState(null),[selected,setSelected]=useState(null),[noteText,setNoteText]=useState('메모'),[stamps,setStamps]=useState([]),[stampId,setStampId]=useState(''),[crop,setCrop]=useState(null);
  const [urlOpen,setUrlOpen]=useState(false),[notice,setNotice]=useState('');
  const csrf=useRef(null),viewport=useRef(null),fileInput=useRef(null),stampInput=useRef(null),player=useRef(null),abort=useRef(null),drag=useRef(null);
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
  const report=e=>{if(e.name!=='AbortError')setError(describe(e.message));};
  const run=action=>Promise.resolve().then(action).catch(report);
  useEffect(()=>{globalThis.document.documentElement.dataset.theme=theme;try{localStorage.setItem('simpleimgviewer.theme',theme);}catch{}},[theme]);
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
      if(initialSession){const session=await api('/api/v1/viewer/sessions/'+initialSession);setFiles(session.media);setTheme(session.theme);setLeft(session.leftPanelOpen);await select(session.media.find(m=>m.mediaId===session.selectedMediaId)||session.media[0],session.initialPage,session.initialTimeSeconds,boot);}
      else if(initialMedia){const record=await api('/api/v1/media/'+initialMedia);setFiles([record]);await select(record,1,0,boot);}
    });
    return ()=>{alive=false;abort.current?.abort();};
  },[]);
  function fit(pageInfo=info,rotation=angle){
    if(!pageInfo||!viewport.current)return;autoFit.current=true;const rect=viewport.current.getBoundingClientRect();const swapped=rotation%180!==0;
    setZoom(Math.min(1,Math.max(.01,Math.min((rect.width-60)/(swapped?pageInfo.displayHeight:pageInfo.displayWidth),(rect.height-100)/(swapped?pageInfo.displayWidth:pageInfo.displayHeight)))));setPan({x:0,y:0});
  }
  function manualZoom(value){autoFit.current=false;setZoom(value);}
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
  async function openFiles(event){
    const chosen=Array.from(event.target.files||[]);event.target.value='';if(!chosen.length)return;
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
  async function removeFile(file){
    if(!confirm(`'${file.fileName}' 파일을 삭제할까요?\n원본과 이 파일의 메모·스탬프 배치가 함께 삭제되며 되돌릴 수 없습니다.`))return;
    setNotice('');
    // Delete failures (e.g. MEDIA_BUSY) are shown in the file panel so the current view stays usable.
    try{await api('/api/v1/media/'+file.mediaId,{method:'DELETE'});}catch(e){setLeft(true);setNotice('삭제하지 못했습니다. '+brief(e.message));return;}
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
  const binding=()=>({mediaVersion:info.mediaVersion,coordinateSpaceId:info.coordinateSpaceId,expectedRevision:document?.revision||0});
  async function saveItem(item,action='create'){
    const result=await api(annotationsPath()+(action==='create'?'':'/'+item.id),{method:action==='create'?'POST':action==='delete'?'DELETE':'PUT',json:{...binding(),...item}});setDocument(result);setSelected(action==='delete'?null:item.id||result.items.at(-1)?.id);
  }
  function pointerDown(event){
    if(!imageReady||event.button!==0)return;viewport.current.setPointerCapture(event.pointerId);
    const p=point(event);
    if(tool==='crop'){setCrop({x:p.x,y:p.y,width:0,height:0});drag.current={kind:'crop',start:p};}
    else if(tool==='note'||tool==='stamp'){
      if(tool==='stamp'&&!stampId){setError('먼저 스탬프를 업로드하거나 선택해 주세요.');return;}
      const stamp=stamps.find(s=>s.stampId===stampId),width=Math.min(200,info.coordinateWidth),height=Math.min(tool==='stamp'?width*(stamp?.height||1)/(stamp?.width||1):100,info.coordinateHeight);
      run(()=>saveItem({type:tool,text:noteText,stampId:tool==='stamp'?stampId:null,x:clamp(p.x,0,info.coordinateWidth-width),y:clamp(p.y,0,info.coordinateHeight-height),width,height}));setTool('move');
    }else{drag.current={kind:'pan',client:{x:event.clientX,y:event.clientY},pan};setSelected(null);}
  }
  function pointerMove(event){
    if(!imageReady)return;const p=point(event);
    if(magnifier)setLens({x:event.clientX,y:event.clientY,point:p});
    const d=drag.current;if(!d)return;
    if(d.kind==='pan'){autoFit.current=false;setPan({x:d.pan.x+event.clientX-d.client.x,y:d.pan.y+event.clientY-d.client.y});}
    if(d.kind==='crop')setCrop({x:Math.min(p.x,d.start.x),y:Math.min(p.y,d.start.y),width:Math.abs(p.x-d.start.x),height:Math.abs(p.y-d.start.y)});
    if(d.kind==='annotation'){
      const item={...d.item,x:clamp(d.item.x+p.x-d.start.x,0,info.coordinateWidth-d.item.width),y:clamp(d.item.y+p.y-d.start.y,0,info.coordinateHeight-d.item.height)};d.changed=item;
      setDocument(doc=>({...doc,items:doc.items.map(i=>i.id===item.id?item:i)}));
    }
  }
  function pointerUp(){const d=drag.current;drag.current=null;if(d?.kind==='annotation'&&d.changed)run(()=>saveItem(d.changed,'replace'));if(d?.kind==='crop')setTool('move');}
  function annotationDown(event,item){event.stopPropagation();if(!imageReady)return;setSelected(item.id);if(item.type==='note')setNoteText(item.text||'');viewport.current.setPointerCapture(event.pointerId);drag.current={kind:'annotation',item,start:point(event)};}
  async function uploadStamp(event){const file=event.target.files?.[0];event.target.value='';if(!file)return;const form=new FormData();form.append('file',file);const stamp=await api('/api/v1/stamps',{method:'POST',body:form});setStamps(await api('/api/v1/stamps'));setStampId(stamp.stampId);setTool('stamp');}
  async function historyChange(direction){setDocument(await api(annotationsPath()+'/history/'+direction,{method:'POST',json:binding()}));setSelected(null);}
  async function download(edited){
    const region=crop&&crop.width>=1&&crop.height>=1?crop:{x:0,y:0,width:info.coordinateWidth,height:info.coordinateHeight};
    const blob=await api('/api/v1/media/'+current.mediaId+'/pages/'+page+'/'+(edited?'export':'crop'),{method:'POST',json:{...binding(),...region,brightness:features.brightness?brightness:100,rotation:features.rotation?angle:0,includeAnnotations:true},binary:true});
    const url=URL.createObjectURL(blob),a=globalThis.document.createElement('a');a.href=url;a.download='simpleimgviewer-'+(edited?'edited':'crop')+'.png';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  const visibleItems=(document?.items||[]).filter(item=>features[item.type==='stamp'?'stamps':'annotations']);
  const selectedItem=visibleItems.find(i=>i.id===selected);
  return <div className={'app'+(!left?' list-collapsed':'')+(!right?' tools-collapsed':'')}>
    <Header left={left} onToggleLeft={()=>setLeft(!left)} onToggleRight={()=>setRight(!right)} current={current} imageReady={imageReady} features={features}
      zoom={zoom} onZoom={manualZoom} onFit={()=>fit()} onRotate={delta=>setAngle((angle+delta)%360)} magnifier={magnifier} onToggleMagnifier={()=>{setMagnifier(!magnifier);setLens(null);}}
      theme={theme} onToggleTheme={()=>setTheme(theme==='dark'?'light':'dark')} canOpen={!!bootstrap&&!loading} canImportUrl={!!bootstrap?.features.remoteImport&&!loading}
      fileInput={fileInput} onFilesChosen={event=>run(()=>openFiles(event))} onOpenUrl={()=>setUrlOpen(true)}/>
    <main className="workspace">
      {left&&<FilePanel files={files} current={current} notice={notice} loading={loading} onSelect={file=>run(()=>select(file))} onRemove={removeFile}/>}
      <Viewport viewportRef={viewport} tool={tool} onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerLeave={()=>setLens(null)}
        onWheel={event=>{if(imageReady&&features.zoom)manualZoom(z=>clamp(z*(event.deltaY<0?1.1:1/1.1),.01,16));}}
        current={current} info={info} imageReady={imageReady} pan={pan} zoom={zoom} angle={angle} brightness={brightness} items={visibleItems} selected={selected} crop={crop} onAnnotationDown={annotationDown}
        loading={loading} error={error} onRetry={()=>run(()=>select(current,page))} page={page} onPage={next=>run(()=>select(current,next))}>
        {playback&&!loading&&<PlaybackStage current={current} mediaProps={mediaProps}/>}
      </Viewport>
      {right&&<ToolsPanel current={current}>{playback?<PlaybackInfo playback={playback} player={player} speedAllowed={speedAllowed} seekAllowed={seekAllowed}/>:
        <ImageTools ready={!!bootstrap} imageReady={imageReady} features={features} current={current} info={info} brightness={brightness} onBrightness={setBrightness} tool={tool} onTool={setTool}
          noteText={noteText} onNoteText={setNoteText} selectedItem={selectedItem} revision={document?.revision} history={document?.history} itemCount={visibleItems.length}
          onSaveItem={(item,action)=>run(()=>saveItem(item,action))} onHistory={direction=>run(()=>historyChange(direction))}
          stamps={stamps} stampId={stampId} onStampId={setStampId} stampInput={stampInput} onStampChosen={event=>run(()=>uploadStamp(event))}
          crop={crop} onClearCrop={()=>setCrop(null)} onDownload={edited=>run(()=>download(edited))}/>}</ToolsPanel>}
    </main>
    {magnifier&&lens&&imageReady&&<Lens lens={lens} info={info} brightness={brightness}/>}
    <UrlDialog open={urlOpen} onClose={()=>setUrlOpen(false)} onSubmit={value=>run(()=>openUrl(value))}/>
  </div>;
}
