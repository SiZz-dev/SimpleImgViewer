import Icon from './Icon.jsx';
import {clamp} from '../format.js';

// One-line header: panel toggles, view controls (zoom/fit/rotate/magnifier), theme and file opening.
export default function Header({left,onToggleLeft,onToggleRight,current,imageReady,features,zoom,onZoom,onFit,onRotate,magnifier,onToggleMagnifier,theme,onToggleTheme,canOpen,canImportUrl,fileInput,onFilesChosen,onOpenUrl}){
  return <header className="header">
    <Icon label={left?'파일 목록 접기':'파일 목록 펼치기'} onClick={onToggleLeft}>☰</Icon>
    <a className="brand" href="/viewer"><span className="brand-mark">▧</span><span>SimpleImgViewer</span></a>
    <div className="document-title"><strong>{current?.fileName||'미디어 뷰어'}</strong>{current&&<span className="type-badge">{current.mediaType.toUpperCase()}</span>}</div>
    <div className="header-tools">
      <Icon label="축소" disabled={!imageReady||!features.zoom} onClick={()=>onZoom(z=>clamp(z/1.25,.01,16))}>−</Icon>
      <button className="zoom-value" disabled={!imageReady||!features.zoom} onClick={()=>onZoom(1)}>{Math.round(zoom*100)}%</button>
      <Icon label="확대" disabled={!imageReady||!features.zoom} onClick={()=>onZoom(z=>clamp(z*1.25,.01,16))}>+</Icon>
      <Icon label="화면에 맞추기" disabled={!imageReady} onClick={onFit}>⛶</Icon>
      <Icon label="왼쪽으로 회전" disabled={!imageReady||!features.rotation} onClick={()=>onRotate(270)}>↶</Icon>
      <Icon label="오른쪽으로 회전" disabled={!imageReady||!features.rotation} onClick={()=>onRotate(90)}>↷</Icon>
      <Icon label="돋보기" active={magnifier} disabled={!imageReady||!features.magnifier} onClick={onToggleMagnifier}>⌕</Icon>
    </div>
    <Icon label="도구 패널 전환" onClick={onToggleRight}>◫</Icon>
    <Icon label={theme==='dark'?'라이트 테마로 전환':'다크 테마로 전환'} onClick={onToggleTheme}>{theme==='dark'?'☼':'☾'}</Icon>
    <button className="primary-button" disabled={!canOpen} onClick={()=>fileInput.current.click()}>＋ <span>파일 열기</span></button>
    <Icon label="URL로 파일 열기" disabled={!canImportUrl} onClick={onOpenUrl}>↗</Icon>
    <input ref={fileInput} data-testid="file-input" type="file" multiple hidden onChange={onFilesChosen}/>
  </header>;
}
