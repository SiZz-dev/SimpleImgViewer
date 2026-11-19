import Icon from './Icon.jsx';
import {bytes} from '../format.js';

// Right panel frame (layout C): heading, scrolling content and an optional footer that never scrolls away.
export default function ToolsPanel({title,footer,children}){
  return <aside className="tools-panel" aria-label={title}><div className="panel-heading">{title}</div><div className="tools-scroll">{children}</div>{footer}</aside>;
}

export function PlaybackInfo({playback,player,speedAllowed,seekAllowed}){
  return <div className="tool-section"><h3>재생 설정</h3>{speedAllowed&&<label>배속<select defaultValue="1" onChange={e=>{if(player.current)player.current.playbackRate=Number(e.target.value);}}>{[.5,1,1.25,1.5,2].map(n=><option key={n} value={n}>{n}×</option>)}</select></label>}{!seekAllowed&&<p>재생 위치 이동이 설정에서 꺼져 있습니다.</p>}<p>{playback.mimeType}</p><p>{playback.durationSeconds.toFixed(1)}초 · {bytes(playback.sizeBytes)}</p></div>;
}

export function EmptyTools(){
  return <div className="tool-section"><p>파일을 열면 메모·스탬프 속성과 파일 정보가 여기에 나타납니다.</p></div>;
}

// Properties of the image and the selected item. Edit modes and history live in CanvasToolbar.
// Feature switches disable controls here; the server enforces the same switches independently.
export function ImageProperties({ready,imageReady,features,current,info,brightness,onBrightness,noteText,onNoteText,noteInput,selectedItem,revision,itemCount,onSaveItem,stamps,stampId,onStampId,stampInput,stampSelect,onStampChosen}){
  const selectedNote=selectedItem?.type==='note';
  const applyNote=()=>{if(selectedNote&&imageReady&&noteText!==selectedItem.text)onSaveItem({...selectedItem,text:noteText},'replace');};
  return <>
    <section className="tool-section"><h3>메모 내용</h3>
      <textarea ref={noteInput} aria-label="메모 내용" maxLength="500" value={noteText} disabled={!features.annotations} onChange={e=>onNoteText(e.target.value)}
        onKeyDown={e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();applyNote();}}}/>
      <p>{!features.annotations?'메모가 설정에서 꺼져 있습니다.':selectedNote?'고친 뒤 아래 버튼이나 Ctrl+Enter로 선택한 메모에 반영합니다.':'도구 막대에서 메모를 고른 뒤 이미지를 누르면 이 내용으로 메모가 놓입니다. 놓인 메모를 두 번 누르면 글자를 고칠 수 있습니다.'}</p>
      {selectedNote&&<button disabled={!imageReady||noteText===selectedItem.text} onClick={applyNote}>선택 메모 수정</button>}
    </section>
    {selectedItem&&<section className="tool-section"><h3>선택한 {selectedNote?'메모':'스탬프'}</h3>
      <div className="dimensions">{['width','height'].map(key=><label key={selectedItem.id+key+revision}>{key==='width'?'너비':'높이'}<input type="number" defaultValue={Math.round(selectedItem[key])} min="1" onBlur={event=>{const value=Number(event.target.value);const item={...selectedItem,[key]:value};if(value!==selectedItem[key]&&value>=1&&item.x+item.width<=info.coordinateWidth&&item.y+item.height<=info.coordinateHeight)onSaveItem(item,'replace');}}/></label>)}</div>
      <button className="danger" onClick={()=>onSaveItem(selectedItem,'delete')}><Icon name="trash"/>선택 항목 삭제</button>
    </section>}
    <section className="tool-section"><h3>내 스탬프 <button disabled={!ready||!features.stamps||!features.stampUpload} onClick={()=>stampInput.current.click()}><Icon name="upload"/>업로드</button></h3>
      <input ref={stampInput} data-testid="stamp-input" type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={onStampChosen}/>
      <select ref={stampSelect} aria-label="스탬프 선택" value={stampId} disabled={!features.stamps} onChange={e=>onStampId(e.target.value)}><option value="">스탬프 선택</option>{stamps.map(s=><option key={s.stampId} value={s.stampId}>{s.fileName}</option>)}</select>
      <p>이 페이지 메모·스탬프 {itemCount}개</p>
    </section>
    <section className="tool-section"><h3>밝기 조절 <button disabled={!imageReady||!features.brightness} onClick={()=>onBrightness(100)}>초기화</button></h3><div className="range-label"><span><Icon name="sun"/></span><span>{brightness}%</span></div><input aria-label="이미지 밝기" type="range" min="25" max="200" value={brightness} disabled={!imageReady||!features.brightness} onChange={e=>onBrightness(Number(e.target.value))}/><p>{features.brightness?'밝기 조절은 현재 이미지 보기에 적용됩니다.':'밝기 조절이 설정에서 꺼져 있습니다.'}</p></section>
    <section className="tool-section"><h3>파일 정보</h3><dl><dt>원본 형식</dt><dd>{current?.originalMimeType||'—'}</dd><dt>파일 크기</dt><dd>{current?bytes(current.sizeBytes):'—'}</dd><dt>원본 해상도</dt><dd>{info?`${info.coordinateWidth} × ${info.coordinateHeight}`:'—'}</dd></dl></section>
  </>;
}

// Fixed panel footer: the result actions stay visible however long the properties get (U15).
// H-d: the two saves differ in what they contain, so the labels say it — original pixels only vs. with notes/stamps.
export function SaveBar({imageReady,features,crop,onClearCrop,onDownload}){
  const hasCrop=crop&&crop.width>=1&&crop.height>=1;
  return <div className="save-bar">
    <div className="save-bar-info">{hasCrop?<><span>선택 영역 {Math.round(crop.width)} × {Math.round(crop.height)} px</span><button type="button" className="text-button" onClick={onClearCrop}>해제</button></>:<span>편집본에는 메모·스탬프가 들어갑니다. 자르기로 영역을 고르면 그 부분만 저장합니다.</span>}</div>
    <div className="button-row">
      <button disabled={!imageReady||!features.cropAndSave||!hasCrop} title="메모·스탬프 없이 원본에서 선택 영역만 PNG로 저장" onClick={()=>onDownload(false)}><Icon name="download"/>원본 잘라 저장</button>
      <button className="primary-button" disabled={!imageReady||!features.exportEdited} title="메모·스탬프·밝기·회전을 넣어 PNG로 저장" onClick={()=>onDownload(true)}><Icon name="download"/>편집본 저장</button>
    </div>
  </div>;
}
