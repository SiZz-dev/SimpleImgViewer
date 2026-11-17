// Positions an original-coordinate rectangle on the preview surface as percentages.
const areaStyle=(item,info)=>({left:item.x/info.coordinateWidth*100+'%',top:item.y/info.coordinateHeight*100+'%',width:item.width/info.coordinateWidth*100+'%',height:item.height/info.coordinateHeight*100+'%'});

// Center stage: the transformed image with annotations/crop, the playback stage (children),
// loading/error/empty messages and page navigation. Pointer handling stays in App.
export default function Viewport({viewportRef,tool,onPointerDown,onPointerMove,onPointerUp,onPointerLeave,onWheel,current,info,imageReady,pan,zoom,angle,brightness,items,selected,crop,onAnnotationDown,loading,error,onRetry,page,onPage,children}){
  return <section ref={viewportRef} className={'viewport tool-'+tool} aria-label="미디어 표시 영역" onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp} onPointerLeave={onPointerLeave} onWheel={onWheel}>
    <div className="stage-heading"><span>SIMPLEIMGVIEWER WORKSPACE</span><span>{info?info.coordinateWidth+' × '+info.coordinateHeight+' · 미리보기':current?.originalMimeType||''}</span></div>
    {imageReady&&<div className="surface" style={{width:info.displayWidth,height:info.displayHeight,transform:`translate(-50%,-50%) translate(${pan.x}px,${pan.y}px) scale(${zoom}) rotate(${angle}deg)`}}>
      <img className="main-image" alt={current.fileName} src={info.source.url} draggable={false} style={{filter:`brightness(${brightness}%)`}}/>
      <div className="annotation-overlay">{items.map(item=><div key={item.id} className={'annotation '+item.type+(selected===item.id?' selected':'')} style={areaStyle(item,info)} onPointerDown={event=>onAnnotationDown(event,item)}>
        {item.type==='note'?<span>{item.text}</span>:<img alt="사용자 스탬프" src={'/api/v1/stamps/'+item.stampId+'/content'} draggable={false}/>}
      </div>)}{crop&&<div className="crop-selection" style={areaStyle(crop,info)}/>}</div>
    </div>}
    {children}
    {(loading||error||!current)&&<div className={'stage-message'+(error?' error':'')} role="status"><div className="message-icon">{loading?'◌':error?'!':'▧'}</div><h2>{loading?'파일을 준비하고 있습니다':error?'파일을 표시할 수 없습니다':'파일을 열어 시작하세요'}</h2><p>{error||'이미지·동영상·음성을 안전하게 열람합니다.'}</p>{error&&current&&<button className="secondary-button" onClick={onRetry}>다시 시도</button>}</div>}
    {imageReady&&<><div className="stage-note">드래그로 이동 · 휠로 확대/축소</div><nav className="page-navigation"><button disabled={page<=1} onClick={()=>onPage(page-1)}>‹</button><span>페이지</span><strong>{page}</strong><span>/ {info.pageCount||'—'}</span><button disabled={page>=(info.pageCount||1)} onClick={()=>onPage(page+1)}>›</button></nav></>}
  </section>;
}
