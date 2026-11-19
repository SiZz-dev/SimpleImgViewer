import {useEffect,useRef} from 'react';

// In-app confirmation (U6) instead of the browser's confirm(). Focus starts on the safe choice;
// Esc or the cancel button closes without acting.
export default function ConfirmDialog({request,onCancel}){
  const dialog=useRef(null),cancel=useRef(null);
  useEffect(()=>{if(request){dialog.current?.showModal();cancel.current?.focus();}else dialog.current?.close();},[request]);
  return <dialog ref={dialog} className="confirm-dialog" role="alertdialog" aria-labelledby="confirm-title" aria-describedby="confirm-body" onCancel={event=>{event.preventDefault();onCancel();}}>
    {request&&<><h2 id="confirm-title">{request.title}</h2><p id="confirm-body">{request.body}</p>
      <div className="button-row"><button ref={cancel} type="button" onClick={onCancel}>취소</button><button type="button" className="danger-button" onClick={()=>{const run=request.onConfirm;onCancel();run();}}>{request.confirmLabel}</button></div></>}
  </dialog>;
}
