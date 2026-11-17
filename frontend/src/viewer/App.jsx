import React,{useEffect,useRef,useState} from 'react';

const params=new URLSearchParams(location.search),fragment=new URLSearchParams(location.hash.slice(1));
const incomingUrl=params.get('url')??fragment.get('url');
const invalidLaunch=incomingUrl!==null&&(params.getAll('url').length+fragment.getAll('url').length!==1||params.has('sessionId')||params.has('mediaId'));
const initialSession=params.get('sessionId'),initialMedia=params.get('mediaId');
if(incomingUrl!==null)history.replaceState(null,'','/viewer');
const clamp=(n,min,max)=>Math.max(min,Math.min(max,n));
const bytes=n=>n>1048576?(n/1048576).toFixed(1)+' MB':Math.round(n/1024)+' KB';
const messages={AUTHENTICATION_REQUIRED:'로그인이 필요합니다. 개발 환경은 SimpleImgViewer 실행 구성을 사용해 주세요.',CSRF_INVALID:'보안 세션이 만료됐습니다. 새로고침해 주세요.',
  CACHE_CAPACITY_REACHED:'미리보기 저장 공간이 부족합니다. 관리자에게 공간 정리를 요청해 주세요. 원본 파일은 보관되어 있습니다.',
  ORIGINAL_CAPACITY_REACHED:'원본 보관 공간의 한도에 도달했습니다. 관리자에게 문의해 주세요. 새 파일은 저장하지 않았습니다.',
  INSUFFICIENT_STORAGE:'서버의 남은 저장 공간이 부족합니다. 관리자에게 문의해 주세요.',
  RUNTIME_NOT_ACTIVE:'파일 처리 모듈이 준비되지 않았습니다. 관리자에게 문의해 주세요.',
  RUNTIME_TAMPERED:'파일 처리 환경의 검증에 실패했습니다. 관리자에게 문의해 주세요.',
  RUNTIME_EXECUTION_REJECTED:'파일 처리 모듈을 시작할 수 없습니다. 관리자에게 문의해 주세요.',
  PLAYBACK_DISABLED:'재생 모듈이 비활성화되어 있습니다. FFmpeg 설치·설정을 확인해 주세요.',URL_NOT_ALLOWED:'허용된 파일 서버 주소와 경로인지 확인해 주세요.',URL_IMPORT_DISABLED:'URL 파일 열기가 설정에서 꺼져 있습니다.',
  FILE_TOO_LARGE:'파일 크기가 허용 범위를 초과했습니다.',UNSUPPORTED_MEDIA:'현재 처리할 수 없는 파일 형식입니다.',CONVERSION_UNSUPPORTED_PROFILE:'디코더가 지원하지 않는 이미지 구성입니다.',EDIT_CONFLICT:'다른 변경이 저장되었습니다. 파일을 다시 선택해 최신 상태를 읽어 주세요.',PREVIEW_QUEUE_FULL:'이미지 처리 중입니다. 잠시 후 다시 시도해 주세요.',
  INVALID_LAUNCH_URL:'열기 주소가 올바르지 않습니다. url은 하나만 지정하고 sessionId·mediaId와 함께 쓰지 마세요.',FILE_COUNT_LIMIT:'한 번에 열 수 있는 파일은 최대 100개입니다.',
  UPLOAD_BUSY:'다른 파일을 올리는 중입니다. 잠시 후 다시 시도해 주세요.',PLAYBACK_QUEUE_FULL:'다른 재생 파일을 준비하는 중입니다. 잠시 후 다시 시도해 주세요.',
  MEDIA_CHANGED:'파일이 변경되었습니다. 파일을 다시 선택해 주세요.',STALE_COORDINATES:'편집 정보가 현재 파일과 맞지 않습니다. 파일을 다시 선택해 주세요.',FEATURE_DISABLED:'이 기능은 설정에서 꺼져 있습니다.',
  INVALID_MEDIA:'파일을 해석할 수 없거나 허용 범위(길이·해상도·채널 등)를 넘습니다.',INVALID_PLAYBACK_OUTPUT:'재생용 사본을 만들지 못했습니다.',PLAYBACK_FAILED:'재생 준비에 실패했습니다.',
  PLAYBACK_TIMEOUT:'재생 준비 시간이 초과되었습니다.',CONVERSION_TIMEOUT:'이미지 처리 시간이 초과되었습니다.'};
function Icon({label,children,onClick,disabled=false,active=false}){return <button type="button" title={label} aria-label={label} className={'icon-button'+(active?' active':'')} disabled={disabled} onClick={onClick}>{children}</button>;}

export default function App(){
  const [bootstrap,setBootstrap]=useState(null),[files,setFiles]=useState([]),[current,setCurrent]=useState(null),[page,setPage]=useState(1),[info,setInfo]=useState(null),[playback,setPlayback]=useState(null);
  const [loading,setLoading]=useState(false),[error,setError]=useState(''),[theme,setTheme]=useState(()=>{try{return localStorage.getItem('simpleimgviewer.theme')==='light'?'light':'dark';}catch{return 'dark';}});
  const [left,setLeft]=useState(innerWidth>700),[right,setRight]=useState(innerWidth>1000),[zoom,setZoom]=useState(1),[angle,setAngle]=useState(0),[pan,setPan]=useState({x:0,y:0}),[brightness,setBrightness]=useState(100),[magnifier,setMagnifier]=useState(false),[lens,setLens]=useState(null);
  const [tool,setTool]=useState('move'),[document,setDocument]=useState(null),[selected,setSelected]=useState(null),[noteText,setNoteText]=useState('메모'),[stamps,setStamps]=useState([]),[stampId,setStampId]=useState(''),[crop,setCrop]=useState(null);
  const [urlOpen,setUrlOpen]=useState(false),[url,setUrl]=useState(''),[notice,setNotice]=useState('');
  const csrf=useRef(null),viewport=useRef(null),fileInput=useRef(null),stampInput=useRef(null),player=useRef(null),abort=useRef(null),drag=useRef(null),dialog=useRef(null);
  const autoFit=useRef(true),lastTime=useRef(0);
  const imageReady=current?.mediaType==='image'&&info&&!loading,features=bootstrap?.features||{};
  // Seek/speed switches are viewer UX limits; Range delivery stays available for playback itself.
  const isVideo=current?.mediaType==='video',seekAllowed=isVideo?features.videoSeek:features.audioSeek,speedAllowed=isVideo?features.videoPlaybackSpeed:features.audioPlaybackSpeed;
  const mediaProps={ref:player,src:playback?.url,controls:true,preload:'metadata',controlsList:speedAllowed?undefined:'noplaybackrate',
    onLoadStart:()=>{lastTime.current=0;},onEnded:()=>{lastTime.current=0;},
    // Session restore applies the saved start time to both video and audio.
    onLoadedMetadata:e=>{lastTime.current=playback?.startTime||0;e.currentTarget.currentTime=lastTime.current;},
    onTimeUpdate:e=>{if(!e.currentTarget.seeking)lastTime.current=e.currentTarget.currentTime;},
    onSeeking:e=>{const media=e.currentTarget;if(!seekAllowed&&Math.abs(media.currentTime-lastTime.current)>0.5)media.currentTime=lastTime.current;},
    onRateChange:e=>{if(!speedAllowed&&e.currentTarget.playbackRate!==1)e.currentTarget.playbackRate=1;}};
  async function api(path,{method='GET',json,body,binary=false,signal}={}){
    const headers={};if(method!=='GET'){if(!csrf.current)throw Error('CSRF_INVALID');headers[csrf.current.headerName]=csrf.current.token;}
    if(json!==undefined){headers['Content-Type']='application/json';body=JSON.stringify(json);}
    const response=await fetch(path,{method,headers,body,credentials:'same-origin',cache:'no-store',redirect:'error',signal});
    if(!response.ok){let code='REQUEST_FAILED';try{code=(await response.json()).code||code;}catch{}throw Error(code);}
    return binary?response.blob():response.status===204?null:response.json();
  }
  const report=e=>{if(e.name!=='AbortError')setError(messages[e.message]||'요청을 완료하지 못했습니다. '+e.message);};
  const run=action=>Promise.resolve().then(action).catch(report);
  useEffect(()=>{globalThis.document.documentElement.dataset.theme=theme;try{localStorage.setItem('simpleimgviewer.theme',theme);}catch{}},[theme]);
  useEffect(()=>{if(urlOpen){dialog.current?.showModal();}else dialog.current?.close();},[urlOpen]);
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
    setCurrent(record);setPage(nextPage);setInfo(null);setPlayback(null);setDocument(null);setCrop(null);setSelected(null);setError('');setLoading(true);setAngle(0);setBrightness(100);setTool('move');
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
    }finally{if(!controller.signal.aborted)setLoading(false);}
  }
  async function sessionFor(records){const s=await api('/api/v1/viewer/sessions',{method:'POST',json:{mediaIds:records.map(f=>f.mediaId),theme,leftPanelOpen:left}});history.replaceState(null,'','/viewer?sessionId='+s.sessionId);}
  async function openFiles(event){
    const chosen=Array.from(event.target.files||[]);event.target.value='';if(!chosen.length)return;
    setLoading(true);setError('');setNotice('');try{
      if(files.length+chosen.length>100)throw Error('FILE_COUNT_LIMIT');const added=[],failed=[];
      // One rejected file must not hide the files that were already stored.
      for(const file of chosen){const form=new FormData();form.append('file',file);try{added.push(await api('/api/v1/media',{method:'POST',body:form}));}catch(e){failed.push(e);}}
      if(!added.length)throw failed[0];
      const all=[...files,...added];setFiles(all);
      if(failed.length){setLeft(true);setNotice(`${chosen.length}개 중 ${failed.length}개를 열지 못했습니다. `+(messages[failed[0].message]||failed[0].message));}
      await sessionFor(all);await select(added[0]);
    }finally{setLoading(false);}
  }
  async function openUrl(value){
    setLoading(true);setError('');setNotice('');setUrlOpen(false);setUrl('');try{const file=await api('/api/v1/media/import-url',{method:'POST',json:{url:value}});const all=[...files,file];setFiles(all);await sessionFor(all);await select(file);}finally{setLoading(false);}
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
  const areaStyle=item=>({left:item.x/info.coordinateWidth*100+'%',top:item.y/info.coordinateHeight*100+'%',width:item.width/info.coordinateWidth*100+'%',height:item.height/info.coordinateHeight*100+'%'});
  return <div className={'app'+(!left?' list-collapsed':'')+(!right?' tools-collapsed':'')}>
    <header className="header">
      <Icon label={left?'파일 목록 접기':'파일 목록 펼치기'} onClick={()=>setLeft(!left)}>☰</Icon>
      <a className="brand" href="/viewer"><span className="brand-mark">▧</span><span>SimpleImgViewer</span></a>
      <div className="document-title"><strong>{current?.fileName||'미디어 뷰어'}</strong>{current&&<span className="type-badge">{current.mediaType.toUpperCase()}</span>}</div>
      <div className="header-tools">
        <Icon label="축소" disabled={!imageReady||!features.zoom} onClick={()=>manualZoom(z=>clamp(z/1.25,.01,16))}>−</Icon>
        <button className="zoom-value" disabled={!imageReady||!features.zoom} onClick={()=>manualZoom(1)}>{Math.round(zoom*100)}%</button>
        <Icon label="확대" disabled={!imageReady||!features.zoom} onClick={()=>manualZoom(z=>clamp(z*1.25,.01,16))}>+</Icon>
        <Icon label="화면에 맞추기" disabled={!imageReady} onClick={()=>fit()}>⛶</Icon>
        <Icon label="왼쪽으로 회전" disabled={!imageReady||!features.rotation} onClick={()=>setAngle((angle+270)%360)}>↶</Icon>
        <Icon label="오른쪽으로 회전" disabled={!imageReady||!features.rotation} onClick={()=>setAngle((angle+90)%360)}>↷</Icon>
        <Icon label="돋보기" active={magnifier} disabled={!imageReady||!features.magnifier} onClick={()=>{setMagnifier(!magnifier);setLens(null);}}>⌕</Icon>
      </div>
      <Icon label="도구 패널 전환" onClick={()=>setRight(!right)}>◫</Icon>
      <Icon label={theme==='dark'?'라이트 테마로 전환':'다크 테마로 전환'} onClick={()=>setTheme(theme==='dark'?'light':'dark')}>{theme==='dark'?'☼':'☾'}</Icon>
      <button className="primary-button" disabled={!bootstrap||loading} onClick={()=>fileInput.current.click()}>＋ <span>파일 열기</span></button>
      <Icon label="URL로 파일 열기" disabled={!bootstrap?.features.remoteImport||loading} onClick={()=>setUrlOpen(true)}>↗</Icon>
      <input ref={fileInput} data-testid="file-input" type="file" multiple hidden onChange={event=>run(()=>openFiles(event))}/>
    </header>
    <main className="workspace">
      {left&&<aside className="file-panel"><div className="panel-heading">파일 목록 <span className="count">{files.length}</span></div><p className={'list-caption'+(notice?' notice':'')} role={notice?'status':undefined}>{notice||'선택한 파일을 빠르게 살펴보세요'}</p><div className="file-list">
        {files.map((file,index)=><button key={file.mediaId} className={'file-row'+(current?.mediaId===file.mediaId?' selected':'')} onClick={()=>run(()=>select(file))}>
          <span className="file-thumb">{file.thumbnailUrl?<img src={file.thumbnailUrl} alt="" loading="lazy" decoding="async"/>:file.mediaType==='image'?'▧':file.mediaType==='video'?'▷':'♫'}<small>{String(index+1).padStart(2,'0')}</small></span><span className="file-description"><strong>{file.fileName}</strong><small>{file.originalMimeType.split('/')[1].toUpperCase()} · {bytes(file.sizeBytes)}</small><em>{file.mediaType==='image'?'이미지':file.mediaType==='video'?'동영상':'음성'}</em></span>
        </button>)}{!files.length&&<div className="list-empty">▧<p>열린 파일이 없습니다</p></div>}
      </div></aside>}
      <section ref={viewport} className={'viewport tool-'+tool} aria-label="미디어 표시 영역" onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={pointerUp} onPointerLeave={()=>setLens(null)} onWheel={event=>{if(imageReady&&features.zoom)manualZoom(z=>clamp(z*(event.deltaY<0?1.1:1/1.1),.01,16));}}>
        <div className="stage-heading"><span>SIMPLEIMGVIEWER WORKSPACE</span><span>{info?info.coordinateWidth+' × '+info.coordinateHeight+' · 미리보기':current?.originalMimeType||''}</span></div>
        {imageReady&&<div className="surface" style={{width:info.displayWidth,height:info.displayHeight,transform:`translate(-50%,-50%) translate(${pan.x}px,${pan.y}px) scale(${zoom}) rotate(${angle}deg)`}}>
          <img className="main-image" alt={current.fileName} src={info.source.url} draggable={false} style={{filter:`brightness(${brightness}%)`}}/>
          <div className="annotation-overlay">{visibleItems.map(item=><div key={item.id} className={'annotation '+item.type+(selected===item.id?' selected':'')} style={areaStyle(item)} onPointerDown={event=>annotationDown(event,item)}>
            {item.type==='note'?<span>{item.text}</span>:<img alt="사용자 스탬프" src={'/api/v1/stamps/'+item.stampId+'/content'} draggable={false}/>}
          </div>)}{crop&&<div className="crop-selection" style={areaStyle(crop)}/>}</div>
        </div>}
        {playback&&!loading&&<div className="playback-stage" onPointerDown={event=>event.stopPropagation()}>{current.mediaType==='video'?<video key={current.mediaId} {...mediaProps}/>:<div className="audio-card"><div>♫</div><h2>{current.fileName}</h2><audio {...mediaProps}/></div>}</div>}
        {(loading||error||!current)&&<div className={'stage-message'+(error?' error':'')} role="status"><div className="message-icon">{loading?'◌':error?'!':'▧'}</div><h2>{loading?'파일을 준비하고 있습니다':error?'파일을 표시할 수 없습니다':'파일을 열어 시작하세요'}</h2><p>{error||'이미지·동영상·음성을 안전하게 열람합니다.'}</p>{error&&current&&<button className="secondary-button" onClick={()=>run(()=>select(current,page))}>다시 시도</button>}</div>}
        {imageReady&&<><div className="stage-note">드래그로 이동 · 휠로 확대/축소</div><nav className="page-navigation"><button disabled={page<=1} onClick={()=>run(()=>select(current,page-1))}>‹</button><span>페이지</span><strong>{page}</strong><span>/ {info.pageCount||'—'}</span><button disabled={page>=(info.pageCount||1)} onClick={()=>run(()=>select(current,page+1))}>›</button></nav></>}
      </section>
      {right&&<aside className="tools-panel"><div className="panel-heading">{current?.mediaType==='image'||!current?'이미지 도구':'재생 정보'}<small>조정 · 편집</small></div>
        {playback?<div className="tool-section"><h3>재생 설정</h3>{speedAllowed&&<label>배속<select defaultValue="1" onChange={e=>{if(player.current)player.current.playbackRate=Number(e.target.value);}}>{[.5,1,1.25,1.5,2].map(n=><option key={n} value={n}>{n}×</option>)}</select></label>}{!seekAllowed&&<p>재생 위치 이동이 설정에서 꺼져 있습니다.</p>}<p>{playback.mimeType}</p><p>{playback.durationSeconds.toFixed(1)}초 · {bytes(playback.sizeBytes)}</p></div>:<>
          <section className="tool-section"><h3>밝기 조절 <button disabled={!imageReady||!features.brightness} onClick={()=>setBrightness(100)}>초기화</button></h3><div className="range-label"><span>☼</span><span>{brightness}%</span></div><input aria-label="이미지 밝기" type="range" min="25" max="200" value={brightness} disabled={!imageReady||!features.brightness} onChange={e=>setBrightness(Number(e.target.value))}/><p>{features.brightness?'밝기 조절은 현재 이미지 보기에 적용됩니다.':'밝기 조절이 설정에서 꺼져 있습니다.'}</p></section>
          <section className="tool-section"><h3>메모 · 스탬프</h3><div className="tool-grid"><button className={tool==='note'?'active':''} disabled={!imageReady||!features.annotations} onClick={()=>setTool(tool==='note'?'move':'note')}>▤<span>메모 추가</span></button><button className={tool==='stamp'?'active':''} disabled={!imageReady||!features.stamps} onClick={()=>setTool(tool==='stamp'?'move':'stamp')}>▣<span>스탬프 배치</span></button></div><p>도구를 선택한 뒤 이미지의 원하는 위치를 클릭하세요.</p>
            <label>메모 내용<textarea aria-label="메모 내용" maxLength="500" value={noteText} onChange={e=>setNoteText(e.target.value)}/></label>
            {selectedItem?.type==='note'&&<button disabled={!imageReady} onClick={()=>run(()=>saveItem({...selectedItem,text:noteText},'replace'))}>선택 메모 수정</button>}
            {selectedItem&&<><div className="dimensions">{['width','height'].map(key=><label key={selectedItem.id+key+document.revision}>{key==='width'?'너비':'높이'}<input type="number" defaultValue={Math.round(selectedItem[key])} min="1" onBlur={event=>{const value=Number(event.target.value);const item={...selectedItem,[key]:value};if(value!==selectedItem[key]&&value>=1&&item.x+item.width<=info.coordinateWidth&&item.y+item.height<=info.coordinateHeight)run(()=>saveItem(item,'replace'));}}/></label>)}</div><button className="danger" onClick={()=>run(()=>saveItem(selectedItem,'delete'))}>선택 항목 삭제</button></>}
            <div className="button-row"><button disabled={!imageReady||!document?.history.canUndo} onClick={()=>run(()=>historyChange('undo'))}>실행 취소</button><button disabled={!imageReady||!document?.history.canRedo} onClick={()=>run(()=>historyChange('redo'))}>다시 실행</button></div>
            <h3>내 스탬프 <button disabled={!bootstrap||!features.stamps||!features.stampUpload} onClick={()=>stampInput.current.click()}>＋ 업로드</button></h3><input ref={stampInput} data-testid="stamp-input" type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={event=>run(()=>uploadStamp(event))}/><select aria-label="스탬프 선택" value={stampId} onChange={e=>setStampId(e.target.value)}><option value="">스탬프 선택</option>{stamps.map(s=><option key={s.stampId} value={s.stampId}>{s.fileName}</option>)}</select><p>이 페이지 메모·스탬프 {visibleItems.length}개</p>
          </section>
          <section className="tool-section"><h3>영역 자르기 · 저장</h3><button className={tool==='crop'?'active':''} disabled={!imageReady||!features.cropAndSave} onClick={()=>setTool(tool==='crop'?'move':'crop')}>⌗ 영역 선택</button><p>{crop?`${Math.round(crop.width)} × ${Math.round(crop.height)} 원본 픽셀`:'이미지를 드래그해 저장 영역을 선택하세요.'}</p><div className="button-row"><button disabled={!imageReady||!features.cropAndSave||!crop?.width} onClick={()=>run(()=>download(false))}>자르기 저장</button><button disabled={!imageReady||!features.exportEdited} onClick={()=>run(()=>download(true))}>편집본 저장</button></div>{crop&&<button onClick={()=>setCrop(null)}>영역 해제</button>}</section>
          <section className="tool-section"><h3>파일 정보</h3><dl><dt>원본 형식</dt><dd>{current?.originalMimeType||'—'}</dd><dt>파일 크기</dt><dd>{current?bytes(current.sizeBytes):'—'}</dd><dt>원본 해상도</dt><dd>{info?`${info.coordinateWidth} × ${info.coordinateHeight}`:'—'}</dd></dl></section>
        </>}
      </aside>}
    </main>
    {magnifier&&lens&&imageReady&&<div className="lens" style={{left:lens.x+20,top:lens.y+20,backgroundImage:`url("${info.source.url}")`,backgroundSize:`${info.displayWidth*2}px ${info.displayHeight*2}px`,backgroundPosition:`${90-lens.point.x/info.coordinateWidth*info.displayWidth*2}px ${90-lens.point.y/info.coordinateHeight*info.displayHeight*2}px`,filter:`brightness(${brightness}%)`}}/>}
    <dialog ref={dialog} className="url-dialog" onCancel={()=>{setUrlOpen(false);setUrl('');}}><form onSubmit={event=>{event.preventDefault();run(()=>openUrl(url));}}><h2>URL로 파일 열기</h2><p>설정에서 허용한 파일 서버의 주소를 입력하세요.</p><input aria-label="파일 URL" type="url" required maxLength="8192" value={url} onChange={e=>setUrl(e.target.value)} placeholder="http://192.168.0.86:8080/viewer/resources/TestFiles/sample.jpg"/><div className="button-row"><button type="button" onClick={()=>{setUrlOpen(false);setUrl('');}}>취소</button><button className="primary-button" type="submit">파일 열기</button></div></form></dialog>
  </div>;
}
