import Icon from './Icon.jsx';
import {bytes} from '../format.js';

const formats='이미지 PNG·JPEG·GIF·TIFF·WebP·BMP · 동영상 MP4·MPEG · 음성 MP3·WAV';

// Positions an original-coordinate rectangle on the preview surface as percentages.
const areaStyle=(item,info)=>({left:item.x/info.coordinateWidth*100+'%',top:item.y/info.coordinateHeight*100+'%',width:item.width/info.coordinateWidth*100+'%',height:item.height/info.coordinateHeight*100+'%'});

// Center stage: the edit toolbar, the transformed image with annotations/crop, the playback stage (children),
// loading/error/empty messages and page navigation. Pointer handling stays in App.
export default function Viewport({viewportRef,tool,toolbar,onPointerDown,onPointerMove,onPointerUp,onPointerLeave,onWheel,current,info,imageReady,pan,zoom,angle,brightness,items,selected,crop,onAnnotationDown,onAnnotationFocus,canOpen,onOpenFiles,maxUploadBytes,loading,error,onRetry,page,onPage,children}){
  return <section ref={viewportRef} className={'viewport tool-'+tool} aria-label="미디어 표시 영역" onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp} onPointerLeave={onPointerLeave} onWheel={onWheel}>
    {imageReady&&toolbar}
    {imageReady&&<div className="surface" style={{width:info.displayWidth,height:info.displayHeight,transform:`translate(-50%,-50%) translate(${pan.x}px,${pan.y}px) scale(${zoom}) rotate(${angle}deg)`}}>
      <img className="main-image" alt={current.fileName} src={info.source.url} draggable={false} style={{filter:`brightness(${brightness}%)`}}/>
      <div className="annotation-overlay">{items.map(item=><div key={item.id} className={'annotation '+item.type+(selected===item.id?' selected':'')} style={areaStyle(item,info)} onPointerDown={event=>onAnnotationDown(event,item)}
        tabIndex={0} role="button" aria-pressed={selected===item.id} aria-label={(item.type==='note'?'메모: '+item.text:'스탬프')+' — 방향키로 이동, Delete로 삭제'} onFocus={()=>onAnnotationFocus(item)}>
        {item.type==='note'?<span>{item.text}</span>:<img alt="사용자 스탬프" src={'/api/v1/stamps/'+item.stampId+'/content'} draggable={false}/>}
      </div>)}{crop&&<div className="crop-selection" style={areaStyle(crop,info)}/>}</div>
    </div>}
    {children}
    {(loading||error||!current)&&<div className={'stage-message'+(error?' error':'')} role="status"><div className="message-icon">{loading?<Icon name="loader" className="spin"/>:<Icon name={error?'alert':'image'}/>}</div><h2>{loading?'파일을 준비하고 있습니다':error?'파일을 표시할 수 없습니다':'파일을 여기에 끌어다 놓으세요'}</h2><p>{error||(loading?'이미지·동영상·음성을 안전하게 열람합니다.':'또는 아래 버튼으로 고를 수 있습니다. 여러 파일을 한 번에 열 수 있습니다.')}</p>
      {error&&current&&<button className="secondary-button" onClick={onRetry}>다시 시도</button>}
      {!loading&&!error&&!current&&<><button className="primary-button" disabled={!canOpen} onClick={onOpenFiles}><Icon name="plus"/>파일 열기</button><p className="formats">{formats}{maxUploadBytes?' · 파일당 최대 '+bytes(maxUploadBytes):''}</p></>}</div>}
    {imageReady&&<nav className="page-navigation" aria-label="페이지"><button disabled={page<=1} title="이전 페이지" aria-label="이전 페이지" onClick={()=>onPage(page-1)}><Icon name="chevron-left"/></button><span>페이지</span><strong>{page}</strong><span>/ {info.pageCount||'—'}</span><button disabled={page>=(info.pageCount||1)} title="다음 페이지" aria-label="다음 페이지" onClick={()=>onPage(page+1)}><Icon name="chevron-right"/></button></nav>}
  </section>;
}
