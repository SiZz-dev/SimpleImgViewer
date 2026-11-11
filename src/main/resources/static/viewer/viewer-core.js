export const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const clamp=(value,min,max)=>Math.min(max,Math.max(min,value));
export function fitScale(width,height,viewWidth,viewHeight,angle=0){
  const swapped=Math.abs(angle%180)===90;
  return clamp(Math.min(Math.max(40,viewWidth-(viewWidth<600?24:90))/(swapped?height:width),
    Math.max(40,viewHeight-150)/(swapped?width:height)),.01,4);
}
export function screenToImage(point,view,box,image){
  const r=-view.angle*Math.PI/180,x=(point.x-box.width/2-view.panX)/view.scale,
    y=(point.y-box.height/2-view.panY)/view.scale;
  return {x:x*Math.cos(r)-y*Math.sin(r)+image.width/2,y:x*Math.sin(r)+y*Math.cos(r)+image.height/2};
}
export function anchoredZoom(view,next,point,box,maxScale=16){
  next=clamp(next,.01,clamp(maxScale,16,4096));const ratio=next/view.scale;
  return {...view,scale:next,panX:point.x-box.width/2-(point.x-box.width/2-view.panX)*ratio,
    panY:point.y-box.height/2-(point.y-box.height/2-view.panY)*ratio};
}
export function validatePyramid(reply,file,page){
  const m=reply?.pyramid;
  if(reply?.state!=="ready"||reply.page!==page.page||reply.mediaVersion!==file.mediaVersion||page.mediaVersion!==file.mediaVersion||page.mediaId!==file.mediaId
    ||!UUID.test(file.mediaId)||!m||m.schemaVersion!==1||!UUID.test(m.tileSetId)||m.mimeType!=="image/png"
    ||![m.rasterWidth,m.rasterHeight].every(n=>Number.isInteger(n)&&n>0&&n<=40000000)
    ||m.rasterWidth*m.rasterHeight>40000000||m.tileSize!==256||m.tileOverlap!==0||m.minLevel!==0
    ||m.maxLevel!==Math.ceil(Math.log2(Math.max(m.rasterWidth,m.rasterHeight)))
    ||m.coordinateSpaceId!=="original:"+file.mediaVersion+":"+page.page+":upright-v1")throw new Error("INVALID_PYRAMID");
  const matrix=m.rasterToOriginal;
  if(!matrix||!["a","b","c","d","e","f"].every((k,i)=>matrix[k]===[1,0,0,1,0,0][i]))throw new Error("INVALID_PYRAMID");
  let count=0;for(let z=0;z<=m.maxLevel;z++){const d=2**(m.maxLevel-z);count+=Math.ceil(Math.ceil(m.rasterWidth/d)/256)*Math.ceil(Math.ceil(m.rasterHeight/d)/256);}
  const url="/api/v1/media/"+file.mediaId+"/pages/"+page.page+"/pyramids/"+m.tileSetId+"/tiles/{level}/{column}/{row}";
  if(count>4096||m.tileUrlTemplate!==url||!Number.isInteger(page.displayWidth)||!Number.isInteger(page.displayHeight)
    ||page.displayWidth<1||page.displayHeight<1||Math.abs(m.rasterWidth*page.displayHeight-m.rasterHeight*page.displayWidth)>Math.max(m.rasterWidth,m.rasterHeight))throw new Error("INVALID_PYRAMID");
  if(page.originalCoordinatesAvailable){const p=page.previewToOriginal;
    if(page.coordinateSpaceId!==m.coordinateSpaceId||page.coordinateWidth!==m.rasterWidth||page.coordinateHeight!==m.rasterHeight||page.exifOrientation!==1
      ||!p||Math.abs(p.a-m.rasterWidth/page.displayWidth)>1e-8||Math.abs(p.d-m.rasterHeight/page.displayHeight)>1e-8
      ||![p.b,p.c,p.e,p.f].every(n=>n===0))throw new Error("INVALID_PYRAMID");}
  return Object.freeze({...m,rasterToOriginal:Object.freeze({...matrix})});
}
export function pyramidTile(m,display,z,column,row){
  const d=2**(m.maxLevel-z),w=Math.ceil(m.rasterWidth/d),h=Math.ceil(m.rasterHeight/d);
  const x=column*256,y=row*256,originalX=x*d,originalY=y*d;
  return {key:z+":"+column+":"+row,z,column,row,width:Math.min(256,w-x),height:Math.min(256,h-y),
    left:originalX/m.rasterWidth*display.width,top:originalY/m.rasterHeight*display.height,
    displayWidth:Math.min(256*d,m.rasterWidth-originalX)/m.rasterWidth*display.width,
    displayHeight:Math.min(256*d,m.rasterHeight-originalY)/m.rasterHeight*display.height};
}
export function pyramidPlan(m,display,view,box,density=1){
  const corners=[[0,0],[box.width,0],[0,box.height],[box.width,box.height]].map(([x,y])=>screenToImage({x,y},view,box,display));
  const left=Math.max(0,Math.min(...corners.map(p=>p.x))),top=Math.max(0,Math.min(...corners.map(p=>p.y))),
    right=Math.min(display.width,Math.max(...corners.map(p=>p.x))),bottom=Math.min(display.height,Math.max(...corners.map(p=>p.y)));
  if(right<=left||bottom<=top)return [];
  let z=clamp(m.maxLevel+Math.ceil(Math.log2(view.scale*display.width/m.rasterWidth*clamp(density,1,4))),0,m.maxLevel);
  while(true){
    const d=2**(m.maxLevel-z),columns=Math.ceil(Math.ceil(m.rasterWidth/d)/256),rows=Math.ceil(Math.ceil(m.rasterHeight/d)/256);
    const x0=clamp(Math.floor(left/display.width*m.rasterWidth/d/256),0,columns-1),x1=clamp(Math.floor((right/display.width*m.rasterWidth-1e-7)/d/256),0,columns-1),
      y0=clamp(Math.floor(top/display.height*m.rasterHeight/d/256),0,rows-1),y1=clamp(Math.floor((bottom/display.height*m.rasterHeight-1e-7)/d/256),0,rows-1);
    if((x1-x0+1)*(y1-y0+1)<=48||z===0){const tiles=[];for(let c=x0;c<=x1;c++)for(let r=y0;r<=y1;r++)tiles.push(pyramidTile(m,display,z,c,r));return tiles;}
    z--;
  }
}
export function sourceUrl(source,mediaId,page){
  if(!UUID.test(mediaId)||!source||!/^[0-9a-f]{64}$/.test(source.sourceId)||source.page!==page
    ||source.mimeType!=="image/png"||source.width<1||source.height<1||source.width>4096||source.height>4096
    ||!Number.isInteger(source.width)||!Number.isInteger(source.height))throw new Error("INVALID_SOURCE");
  const expected="/api/v1/media/"+mediaId+"/sources/"+source.sourceId+"/content?page="+page;
  if(source.url!==expected)throw new Error("INVALID_SOURCE");
  return expected;
}
export function thumbnailUrl(source,mediaId,page){
  if(!UUID.test(mediaId)||!source||source.role!=="thumbnail"||!/^[0-9a-f]{64}$/.test(source.sourceId)||source.page!==page
    ||source.mimeType!=="image/png"||!Number.isInteger(source.width)||!Number.isInteger(source.height)
    ||source.width<1||source.height<1||source.width>512||source.height>512)throw new Error("INVALID_THUMBNAIL");
  const expected="/api/v1/media/"+mediaId+"/sources/"+source.sourceId+"/thumbnail?page="+page;
  if(source.url!==expected)throw new Error("INVALID_THUMBNAIL");return expected;
}
export function toOriginal(point,page){
  const m=page?.previewToOriginal;
  if(!page?.originalCoordinatesAvailable||!m)return null;
  return {x:m.a*point.x+m.c*point.y+m.e,y:m.b*point.x+m.d*point.y+m.f};
}
export function formatBytes(bytes){
  return bytes>=1048576?(bytes/1048576).toFixed(1)+" MB":Math.max(1,Math.round(bytes/1024))+" KB";
}
