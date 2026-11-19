import Icon from './Icon.jsx';

// Layout C: edit modes and history float over the image, next to where they act.
// Pointer events stop here so the viewport never pans or places an item through the toolbar.
const tools=[
  ['move','이동','move','V','드래그로 이동 · 휠로 확대/축소'],
  ['note','메모','note','N','이미지를 누르면 메모가 놓입니다'],
  ['stamp','스탬프','stamp','S','이미지를 누르면 고른 스탬프가 놓입니다'],
  ['crop','자르기','crop','C','드래그해 저장할 영역을 고릅니다'],
];

export default function CanvasToolbar({tool,onTool,features,history,onHistory}){
  const allowed={move:true,note:features.annotations,stamp:features.stamps,crop:features.cropAndSave};
  return <div className="canvas-toolbar" role="toolbar" aria-label="편집 도구" onPointerDown={event=>event.stopPropagation()}>
    {tools.map(([key,label,icon,shortcut,hint])=><button key={key} type="button" className={'tool-button'+(tool===key?' active':'')} aria-pressed={tool===key}
      aria-keyshortcuts={shortcut} title={`${label} (${shortcut}) — ${hint}`} disabled={!allowed[key]} onClick={()=>onTool(tool===key&&key!=='move'?'move':key)}><Icon name={icon}/><span>{label}</span></button>)}
    <span className="toolbar-separator" aria-hidden="true"/>
    <button type="button" className="icon-button" title="실행 취소 (Ctrl+Z)" aria-label="실행 취소" disabled={!history?.canUndo} onClick={()=>onHistory('undo')}><Icon name="undo"/></button>
    <button type="button" className="icon-button" title="다시 실행 (Ctrl+Shift+Z)" aria-label="다시 실행" disabled={!history?.canRedo} onClick={()=>onHistory('redo')}><Icon name="redo"/></button>
  </div>;
}
