import Icon from './Icon.jsx';

// Narrow screens: panels open as bottom sheets from this bar instead of covering the image.
export default function MobileBar({left,right,current,onFiles,onTools}){
  return <nav className="mobile-bar" aria-label="화면 메뉴">
    <button type="button" aria-pressed={left} onClick={onFiles}><Icon name="panel-left"/><span>파일 목록</span></button>
    {current&&<button type="button" aria-pressed={right} onClick={onTools}><Icon name="sliders"/><span>{current.mediaType==='image'?'속성':'재생 정보'}</span></button>}
  </nav>;
}
