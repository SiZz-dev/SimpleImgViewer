import path from 'node:path';
import {requireThat,integer,finite} from '../../system/util/errors.mjs';
import {ffmpegProfile} from '../../playback/util/ffmpeg-profile.mjs';

const reject=condition=>requireThat(condition,500,'INVALID_LPAC_BINDING');
function location(value){
  reject(typeof value==='string'&&value.length>0&&value.length<230&&!/[\0\r\n]/.test(value)&&path.isAbsolute(value)&&path.normalize(value)===value);
  // The native driver supports local fixed volumes only, without ADS or device paths.
  if(process.platform==='win32')reject(/^[a-z]:\\/i.test(value)&&!value.slice(2).includes(':'));
  return value;
}
function contains(parent,file){const relative=path.relative(parent,file);return relative===''||!relative.startsWith('..'+path.sep)&&relative!=='..'&&!path.isAbsolute(relative);}
const same=(a,b)=>path.relative(a,b)==='';
function separate(root,file){location(file);reject(!contains(root,file)&&!contains(file,root));return file;}
function keys(value,names){reject(value&&Object.getPrototypeOf(value)===Object.prototype&&Object.keys(value).every(key=>names.includes(key)));}
function freeze(value){if(value&&typeof value==='object'){for(const child of Object.values(value))freeze(child);Object.freeze(value);}return value;}
function finish(plan){reject(Buffer.byteLength(JSON.stringify(plan))<=1048576);return freeze(plan);}

// A plan is an internal contract, not proof of containment or authorization.
// The native consumer must validate real paths, handles, hashes, ACLs and leases.
export function imageLpacBinding(job,root,{maxPixels=16000000}={}){
  location(root);keys(job,['operation','input','output','thumbnail','page','edge','maxPixels','brightness','rotation','crop','items']);
  reject(['preview','export'].includes(job.operation)&&integer(maxPixels,1,40000000));
  const outputName=job.operation==='preview'?'preview.png':'export.png';
  separate(root,job.output);reject(path.basename(job.output)===outputName);
  const input=separate(root,job.input);reject(!same(input,job.output));
  const inputs=[{source:input,name:'source.cjs',maxBytes:104857600}];
  const outputs=[{name:outputName,target:job.output,maxBytes:33554432}];
  const document={operation:job.operation,input:path.join(root,'input','source.cjs'),output:path.join(root,'out',outputName),page:job.page??1,maxPixels:Math.min(job.maxPixels??maxPixels,maxPixels)};
  reject(integer(document.page,1,10000)&&integer(document.maxPixels,1,40000000)&&(job.maxPixels===undefined||integer(job.maxPixels,1,40000000)));
  if(job.operation==='preview'){
    reject(job.brightness===undefined&&job.rotation===undefined&&job.crop===undefined&&job.items===undefined&&integer(job.edge,64,4096));
    reject(job.thumbnail===path.join(path.dirname(job.output),'thumbnail.png')&&!same(job.thumbnail,input));separate(root,job.thumbnail);
    document.edge=job.edge;document.thumbnail=path.join(root,'out','thumbnail.png');
    outputs.push({name:'thumbnail.png',target:job.thumbnail,maxBytes:33554432});
  }else{
    reject(job.thumbnail===undefined&&job.edge===undefined&&finite(job.brightness??100,25,200)&&[0,90,180,270].includes(job.rotation??0));
    document.brightness=job.brightness??100;document.rotation=job.rotation??0;
    if(job.crop!==undefined){keys(job.crop,['left','top','width','height']);reject(integer(job.crop.left,0,40000000)&&integer(job.crop.top,0,40000000)&&integer(job.crop.width,1,40000000)&&integer(job.crop.height,1,40000000)&&job.crop.width*job.crop.height<=8000000);document.crop={...job.crop};}
    reject(job.items===undefined||Array.isArray(job.items)&&job.items.length<=200);
    const stamps=new Map();let pixels=0;
    document.items=(job.items??[]).map(item=>{
      keys(item,['id','type','x','y','width','height','text','stampId','file']);
      reject(['note','stamp'].includes(item.type)&&finite(item.x,0,40000000)&&finite(item.y,0,40000000)&&finite(item.width,1,40000000)&&finite(item.height,1,40000000)&&item.width*item.height<=4194304);
      pixels+=item.width*item.height;reject(pixels<=16000000);
      const bound={type:item.type,x:item.x,y:item.y,width:item.width,height:item.height};
      if(item.type==='note'){reject(item.file===undefined&&typeof item.text==='string'&&item.text.length<=500);bound.text=item.text;}
      else{
        const file=separate(root,item.file);reject(!outputs.some(output=>same(output.target,file)));
        if(!stamps.has(file)){const name='stamp-'+String(stamps.size).padStart(3,'0')+'.png';stamps.set(file,name);inputs.push({source:file,name,maxBytes:2097152});}
        bound.file=path.join(root,'input',stamps.get(file));
      }
      return bound;
    });
  }
  for(const file of [document.input,document.output,document.thumbnail,...document.items?.filter(item=>item.file).map(item=>item.file)??[]].filter(Boolean))location(file);
  return finish({schemaVersion:1,kind:'image',root,inputs,outputs,document,manifest:location(path.join(root,'out','job.json')),stdoutMaxBytes:8192,stderrMaxBytes:8192});
}

export function mediaLpacBinding(request,root){
  location(root);keys(request,['operation','format','input','output','tool']);
  const inputs=[],outputs=[];
  const bound={...request};
  if(request.input!==undefined){const source=separate(root,request.input);inputs.push({source,name:'source.media',maxBytes:request.operation==='probe-output'?268435456:104857600});bound.input=location(path.join(root,'input','source.media'));}
  if(request.output!==undefined){const target=separate(root,request.output);reject(path.basename(target)==='playback'&&!same(target,request.input));outputs.push({name:'playback',target,maxBytes:268435456});bound.output=location(path.join(root,'out','playback'));}
  const command=ffmpegProfile(bound);
  return finish({schemaVersion:1,kind:'media',root,operation:request.operation,format:request.format??null,inputs,outputs,...command,stdoutMaxBytes:1048576,stderrMaxBytes:1048576});
}
