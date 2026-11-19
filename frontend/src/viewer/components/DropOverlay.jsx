import Icon from './Icon.jsx';

// Shown while files are dragged over the viewer (U17). Purely visual: App handles the drop itself.
export default function DropOverlay(){
  return <div className="drop-overlay" aria-hidden="true"><div className="drop-card"><Icon name="upload"/><strong>여기에 놓으면 파일을 엽니다</strong><span>여러 파일을 한 번에 놓을 수 있습니다</span></div></div>;
}
