import {bytes} from '../format.js';

// Right panel frame; App chooses PlaybackInfo or ImageTools as its content.
export default function ToolsPanel({current,children}){
  return <aside className="tools-panel"><div className="panel-heading">{current?.mediaType==='image'||!current?'이미지 도구':'재생 정보'}<small>조정 · 편집</small></div>{children}</aside>;
}

export function PlaybackInfo({playback,player,speedAllowed,seekAllowed}){
  return <div className="tool-section"><h3>재생 설정</h3>{speedAllowed&&<label>배속<select defaultValue="1" onChange={e=>{if(player.current)player.current.playbackRate=Number(e.target.value);}}>{[.5,1,1.25,1.5,2].map(n=><option key={n} value={n}>{n}×</option>)}</select></label>}{!seekAllowed&&<p>재생 위치 이동이 설정에서 꺼져 있습니다.</p>}<p>{playback.mimeType}</p><p>{playback.durationSeconds.toFixed(1)}초 · {bytes(playback.sizeBytes)}</p></div>;
}

// Image adjustments, notes/stamps, crop/export and file facts. Feature switches disable controls here;
// the server enforces the same switches independently.
export function ImageTools({ready,imageReady,features,current,info,brightness,onBrightness,tool,onTool,noteText,onNoteText,selectedItem,revision,history,itemCount,onSaveItem,onHistory,stamps,stampId,onStampId,stampInput,onStampChosen,crop,onClearCrop,onDownload}){
  return <>
    <section className="tool-section"><h3>밝기 조절 <button disabled={!imageReady||!features.brightness} onClick={()=>onBrightness(100)}>초기화</button></h3><div className="range-label"><span>☼</span><span>{brightness}%</span></div><input aria-label="이미지 밝기" type="range" min="25" max="200" value={brightness} disabled={!imageReady||!features.brightness} onChange={e=>onBrightness(Number(e.target.value))}/><p>{features.brightness?'밝기 조절은 현재 이미지 보기에 적용됩니다.':'밝기 조절이 설정에서 꺼져 있습니다.'}</p></section>
    <section className="tool-section"><h3>메모 · 스탬프</h3><div className="tool-grid"><button className={tool==='note'?'active':''} disabled={!imageReady||!features.annotations} onClick={()=>onTool(tool==='note'?'move':'note')}>▤<span>메모 추가</span></button><button className={tool==='stamp'?'active':''} disabled={!imageReady||!features.stamps} onClick={()=>onTool(tool==='stamp'?'move':'stamp')}>▣<span>스탬프 배치</span></button></div><p>도구를 선택한 뒤 이미지의 원하는 위치를 클릭하세요.</p>
      <label>메모 내용<textarea aria-label="메모 내용" maxLength="500" value={noteText} onChange={e=>onNoteText(e.target.value)}/></label>
      {selectedItem?.type==='note'&&<button disabled={!imageReady} onClick={()=>onSaveItem({...selectedItem,text:noteText},'replace')}>선택 메모 수정</button>}
      {selectedItem&&<><div className="dimensions">{['width','height'].map(key=><label key={selectedItem.id+key+revision}>{key==='width'?'너비':'높이'}<input type="number" defaultValue={Math.round(selectedItem[key])} min="1" onBlur={event=>{const value=Number(event.target.value);const item={...selectedItem,[key]:value};if(value!==selectedItem[key]&&value>=1&&item.x+item.width<=info.coordinateWidth&&item.y+item.height<=info.coordinateHeight)onSaveItem(item,'replace');}}/></label>)}</div><button className="danger" onClick={()=>onSaveItem(selectedItem,'delete')}>선택 항목 삭제</button></>}
      <div className="button-row"><button disabled={!imageReady||!history?.canUndo} onClick={()=>onHistory('undo')}>실행 취소</button><button disabled={!imageReady||!history?.canRedo} onClick={()=>onHistory('redo')}>다시 실행</button></div>
      <h3>내 스탬프 <button disabled={!ready||!features.stamps||!features.stampUpload} onClick={()=>stampInput.current.click()}>＋ 업로드</button></h3><input ref={stampInput} data-testid="stamp-input" type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={onStampChosen}/><select aria-label="스탬프 선택" value={stampId} onChange={e=>onStampId(e.target.value)}><option value="">스탬프 선택</option>{stamps.map(s=><option key={s.stampId} value={s.stampId}>{s.fileName}</option>)}</select><p>이 페이지 메모·스탬프 {itemCount}개</p>
    </section>
    <section className="tool-section"><h3>영역 자르기 · 저장</h3><button className={tool==='crop'?'active':''} disabled={!imageReady||!features.cropAndSave} onClick={()=>onTool(tool==='crop'?'move':'crop')}>⌗ 영역 선택</button><p>{crop?`${Math.round(crop.width)} × ${Math.round(crop.height)} 원본 픽셀`:'이미지를 드래그해 저장 영역을 선택하세요.'}</p><div className="button-row"><button disabled={!imageReady||!features.cropAndSave||!crop?.width} onClick={()=>onDownload(false)}>자르기 저장</button><button disabled={!imageReady||!features.exportEdited} onClick={()=>onDownload(true)}>편집본 저장</button></div>{crop&&<button onClick={onClearCrop}>영역 해제</button>}</section>
    <section className="tool-section"><h3>파일 정보</h3><dl><dt>원본 형식</dt><dd>{current?.originalMimeType||'—'}</dd><dt>파일 크기</dt><dd>{current?bytes(current.sizeBytes):'—'}</dd><dt>원본 해상도</dt><dd>{info?`${info.coordinateWidth} × ${info.coordinateHeight}`:'—'}</dd></dl></section>
  </>;
}
