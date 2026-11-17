// 2x magnifier that follows the pointer; reuses the preview image as its background.
export default function Lens({lens,info,brightness}){
  return <div className="lens" style={{left:lens.x+20,top:lens.y+20,backgroundImage:`url("${info.source.url}")`,backgroundSize:`${info.displayWidth*2}px ${info.displayHeight*2}px`,backgroundPosition:`${90-lens.point.x/info.coordinateWidth*info.displayWidth*2}px ${90-lens.point.y/info.coordinateHeight*info.displayHeight*2}px`,filter:`brightness(${brightness}%)`}}/>;
}
