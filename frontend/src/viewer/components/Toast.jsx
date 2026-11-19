import Icon from './Icon.jsx';

// Non-blocking feedback for actions (U2, U13): the image stays visible and usable.
// Errors use role="alert"; info and success are announced politely.
export default function Toast({toast,onClose}){
  if(!toast)return null;
  const icon=toast.type==='success'?'check':toast.type==='error'?'alert':'info';
  return <div className={'toast '+toast.type} role={toast.type==='error'?'alert':'status'}>
    <Icon name={icon}/><span>{toast.text}</span>
    {toast.action&&<button type="button" className="text-button" onClick={()=>{onClose();toast.action.run();}}>{toast.action.label}</button>}
    <button type="button" className="icon-button" title="알림 닫기" aria-label="알림 닫기" onClick={onClose}><Icon name="close"/></button>
  </div>;
}
