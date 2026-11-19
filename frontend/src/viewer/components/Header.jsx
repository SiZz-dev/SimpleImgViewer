import Icon from './Icon.jsx';
import IconButton from './IconButton.jsx';
import {clamp} from '../format.js';

// One-line header: panel toggles, view controls (zoom/fit/rotate/magnifier), theme and file opening.
// View controls appear only for images (U9); on narrow screens MobileBar replaces the panel toggles.
export default function Header({narrow,left,onToggleLeft,onToggleRight,current,imageReady,features,zoom,onZoom,onFit,onRotate,magnifier,onToggleMagnifier,theme,onToggleTheme,onHelp,canOpen,canImportUrl,fileInput,onFilesChosen,onOpenUrl}){
  return <header className="header">
    {!narrow&&<IconButton label={left?'파일 목록 접기':'파일 목록 펼치기'} onClick={onToggleLeft}><Icon name="panel-left"/></IconButton>}
    <a className="brand" href="/viewer"><span className="brand-mark"><Icon name="image"/></span><span>SimpleImgViewer</span></a>
    <div className="document-title"><strong>{current?.fileName||'미디어 뷰어'}</strong>{current&&<span className="type-badge">{current.mediaType.toUpperCase()}</span>}</div>
    {current?.mediaType==='image'&&<div className="header-tools" role="group" aria-label="보기">
      <IconButton label="축소" disabled={!imageReady||!features.zoom} onClick={()=>onZoom(z=>clamp(z/1.25,.01,16))}><Icon name="zoom-out"/></IconButton>
      <button className="zoom-value" disabled={!imageReady||!features.zoom} onClick={()=>onZoom(1)}>{Math.round(zoom*100)}%</button>
      <IconButton label="확대" disabled={!imageReady||!features.zoom} onClick={()=>onZoom(z=>clamp(z*1.25,.01,16))}><Icon name="zoom-in"/></IconButton>
      <IconButton label="화면에 맞추기" disabled={!imageReady} onClick={onFit}><Icon name="fit"/></IconButton>
      <IconButton label="왼쪽으로 회전" disabled={!imageReady||!features.rotation} onClick={()=>onRotate(270)}><Icon name="rotate-left"/></IconButton>
      <IconButton label="오른쪽으로 회전" disabled={!imageReady||!features.rotation} onClick={()=>onRotate(90)}><Icon name="rotate-right"/></IconButton>
      <IconButton label="돋보기" active={magnifier} disabled={!imageReady||!features.magnifier} onClick={onToggleMagnifier}><Icon name="magnifier"/></IconButton>
    </div>}
    {!narrow&&<IconButton label="도구 패널 전환" onClick={onToggleRight}><Icon name="panel-right"/></IconButton>}
    <IconButton label="도움말" onClick={onHelp}><Icon name="help"/></IconButton>
    <IconButton label={theme==='dark'?'라이트 테마로 전환':'다크 테마로 전환'} onClick={onToggleTheme}><Icon name={theme==='dark'?'sun':'moon'}/></IconButton>
    <button className="primary-button" disabled={!canOpen} onClick={()=>fileInput.current.click()}><Icon name="plus"/><span>파일 열기</span></button>
    <IconButton label="URL로 파일 열기" disabled={!canImportUrl} onClick={onOpenUrl}><Icon name="link"/></IconButton>
    <input ref={fileInput} data-testid="file-input" type="file" multiple hidden onChange={onFilesChosen}/>
  </header>;
}
