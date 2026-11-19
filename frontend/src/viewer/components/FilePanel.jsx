import Icon from './Icon.jsx';
import {bytes} from '../format.js';

// Left panel: the session's files, an inline notice (partial upload/delete failures) and per-file delete.
export default function FilePanel({files,current,notice,loading,onSelect,onRemove}){
  return <aside className="file-panel"><div className="panel-heading">파일 목록 <span className="count">{files.length}</span></div><p className={'list-caption'+(notice?' notice':'')} role={notice?'status':undefined}>{notice||'선택한 파일을 빠르게 살펴보세요'}</p><div className="file-list">
    {files.map((file,index)=><div key={file.mediaId} className="file-item"><button className={'file-row'+(current?.mediaId===file.mediaId?' selected':'')} onClick={()=>onSelect(file)}>
      <span className="file-thumb">{file.thumbnailUrl?<img src={file.thumbnailUrl} alt="" loading="lazy" decoding="async"/>:<Icon name={file.mediaType==='image'?'image':file.mediaType==='video'?'video':'audio'}/>}<small>{String(index+1).padStart(2,'0')}</small></span><span className="file-description"><strong>{file.fileName}</strong><small>{file.originalMimeType.split('/')[1].toUpperCase()} · {bytes(file.sizeBytes)}</small><em>{file.mediaType==='image'?'이미지':file.mediaType==='video'?'동영상':'음성'}</em></span>
    </button><button type="button" className="file-delete" title={file.fileName+' 삭제'} aria-label={file.fileName+' 삭제'} disabled={loading} onClick={()=>onRemove(file)}><Icon name="trash"/></button></div>)}{!files.length&&<div className="list-empty"><Icon name="image"/><p>열린 파일이 없습니다</p></div>}
  </div></aside>;
}
