import Icon from './Icon.jsx';

// Video/audio element. mediaProps (built in App) carries the source, start time, seek/speed limits
// and session-position saving; pointer events stop here so the image pan/crop logic never sees them.
export default function PlaybackStage({current,mediaProps}){
  return <div className="playback-stage" onPointerDown={event=>event.stopPropagation()}>{current.mediaType==='video'?<video key={current.mediaId} {...mediaProps}/>:<div className="audio-card"><div><Icon name="audio"/></div><h2>{current.fileName}</h2><audio {...mediaProps}/></div>}</div>;
}
