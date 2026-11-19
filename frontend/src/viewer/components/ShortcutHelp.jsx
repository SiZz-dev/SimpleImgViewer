import {useEffect,useRef} from 'react';

// Keyboard shortcut reference (U14, U17), opened from the header or with "?".
// Keep this list in step with App.handleKey, which implements the keys.
export const shortcuts=[
  [['+','−'],'확대 · 축소'],
  [['0'],'화면에 맞추기'],
  [['R'],'오른쪽으로 회전 (Shift+R 왼쪽)'],
  [['←','→'],'이전 · 다음 페이지'],
  [['V','N','S','C'],'이동 · 메모 · 스탬프 · 자르기 도구'],
  [['Tab'],'메모 · 스탬프를 차례로 선택'],
  [['←','↑','→','↓'],'선택한 메모 · 스탬프 옮기기 (Shift: 크게)'],
  [['Delete'],'선택한 메모 · 스탬프 삭제'],
  [['Enter'],'선택한 메모 글자 고치기 (메모를 두 번 눌러도 됨)'],
  [['Esc'],'도구 · 선택 · 자르기 영역 해제'],
  [['Ctrl+Z'],'실행 취소 (Ctrl+Shift+Z 다시 실행)'],
  [['?'],'이 도움말 열기 · 닫기'],
];

export default function ShortcutHelp({open,onClose}){
  const dialog=useRef(null);
  useEffect(()=>{if(open)dialog.current?.showModal();else dialog.current?.close();},[open]);
  return <dialog ref={dialog} className="help-dialog" aria-labelledby="help-title" onCancel={event=>{event.preventDefault();onClose();}}>
    <h2 id="help-title">단축키</h2>
    <dl className="shortcut-list">{shortcuts.map(([keys,label])=><div key={label}><dt>{keys.map(k=><kbd key={k}>{k}</kbd>)}</dt><dd>{label}</dd></div>)}</dl>
    <p>마우스: 드래그로 옮기고, 휠을 돌리면 마우스가 가리키는 곳을 중심으로 확대·축소합니다. 터치: 두 손가락으로 확대·축소하고, 두 번 탭하면 화면에 맞춥니다.</p>
    <p>파일은 화면에 끌어다 놓아도 열립니다. 메모·스탬프가 선택되어 있으면 방향키는 페이지 대신 그 항목을 옮깁니다. 입력란에 글자를 쓰는 중에는 단축키가 동작하지 않습니다.</p>
    <div className="button-row"><button type="button" onClick={onClose}>닫기</button></div>
  </dialog>;
}
