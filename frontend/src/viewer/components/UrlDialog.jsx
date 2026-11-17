import {useEffect,useRef,useState} from 'react';

// Modal for importing a file from an allowed URL prefix. The typed value is cleared whenever it closes.
export default function UrlDialog({open,onClose,onSubmit}){
  const dialog=useRef(null),[url,setUrl]=useState('');
  useEffect(()=>{if(open)dialog.current?.showModal();else{dialog.current?.close();setUrl('');}},[open]);
  return <dialog ref={dialog} className="url-dialog" onCancel={onClose}><form onSubmit={event=>{event.preventDefault();onSubmit(url);}}><h2>URL로 파일 열기</h2><p>설정에서 허용한 파일 서버의 주소를 입력하세요.</p><input aria-label="파일 URL" type="url" required maxLength="8192" value={url} onChange={e=>setUrl(e.target.value)} placeholder="http://192.168.0.86:8080/viewer/resources/TestFiles/sample.jpg"/><div className="button-row"><button type="button" onClick={onClose}>취소</button><button className="primary-button" type="submit">파일 열기</button></div></form></dialog>;
}
