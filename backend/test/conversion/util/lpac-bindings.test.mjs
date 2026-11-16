import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {imageLpacBinding,mediaLpacBinding} from '../../../conversion/util/lpac-bindings.mjs';
import {ImageEngine} from '../../../conversion/service/image-engine.mjs';
import {settings} from '../../../system/config/settings.mjs';
import {openWorkLease} from '../../../conversion/util/work-lease.mjs';

const base=path.resolve('build/binding-fixture'),root=path.join(base,'sandbox'),host=path.join(base,'host');
const preview=()=>({operation:'preview',input:path.join(host,'original.tif'),output:path.join(host,'preview.png'),thumbnail:path.join(host,'thumbnail.png'),edge:1600,page:2,maxPixels:16000000});
const note={type:'note',x:0,y:0,width:60,height:24,text:'@INPUT@ @OUTPUT@ '+path.join(host,'original.tif')};
const stamp=file=>({type:'stamp',x:0,y:30,width:10,height:10,file});
const edit=()=>({operation:'export',input:path.join(host,'original.png'),output:path.join(host,'export.png'),brightness:125,rotation:90,crop:{left:0,top:0,width:64,height:64},items:[note,stamp(path.join(host,'a.png')),stamp(path.join(host,'b.png')),stamp(path.join(host,'a.png'))]});

test('image binding confines worker paths, clamps policy and freezes independent copies',()=>{
  const job=preview(),binding=imageLpacBinding(job,root,{maxPixels:8000000});
  assert.equal(binding.document.input,path.join(root,'input','source.cjs'));
  assert.equal(binding.document.thumbnail,path.join(root,'out','thumbnail.png'));
  assert.equal(binding.document.maxPixels,8000000);assert.equal(binding.document.page,2);
  assert.deepEqual(binding.outputs.map(value=>value.name),['preview.png','thumbnail.png']);
  assert.ok(!JSON.stringify(binding.document).includes(host.replaceAll('\\','\\\\')));
  job.edge=64;assert.equal(binding.document.edge,1600);assert.throws(()=>{binding.inputs[0].source='changed';},TypeError);
});
test('multiple stamps bind by input identity while note text and original jobs stay unchanged',()=>{
  const job=edit(),before=structuredClone(job),binding=imageLpacBinding(job,root);
  assert.deepEqual(job,before);assert.equal(binding.inputs.length,3);
  assert.equal(binding.document.items[0].text,note.text);
  assert.equal(binding.document.items[1].file,binding.document.items[3].file);
  assert.notEqual(binding.document.items[1].file,binding.document.items[2].file);
  assert.equal(binding.inputs[1].maxBytes,2097152);
  job.crop.left=20;job.items[0]={...note,text:'changed'};
  assert.equal(binding.document.crop.left,0);assert.equal(binding.document.items[0].text,note.text);
});
test('image binding rejects unknown fields, aliases, unbounded geometry and malformed paths',()=>{
  const mutations=[j=>({...j,args:['-e','code']}),j=>({...j,input:'relative.png'}),j=>({...j,input:j.output}),j=>({...j,input:path.join(root,'input','source.cjs')}),j=>({...j,output:path.join(host,'evil.png')}),j=>({...j,thumbnail:path.join(host,'other.png')}),j=>({...j,page:0}),j=>({...j,maxPixels:Infinity}),j=>({...j,edge:8192}),j=>({...j,input:j.input+'\0'})];
  for(const change of mutations)assert.throws(()=>imageLpacBinding(change(preview()),root),{code:'INVALID_LPAC_BINDING'});
  for(const change of [j=>({...j,crop:{...j.crop,file:'injected'}}),j=>({...j,items:[{...note,file:j.input}]}),j=>({...j,items:[{...note,type:'svg'}]}),j=>({...j,items:[{...note,width:Infinity}]}),j=>({...j,items:Array(201).fill(note)}),j=>({...j,items:[stamp(j.output)]})])assert.throws(()=>imageLpacBinding(change(edit()),root),{code:'INVALID_LPAC_BINDING'});
  if(process.platform==='win32')for(const input of ['\\\\server\\share\\file.png',preview().input+':stream',preview().input.toUpperCase().replace('ORIGINAL.TIF','PREVIEW.PNG')])assert.throws(()=>imageLpacBinding({...preview(),input},root));
});
test('all four media profiles constrain input formats, codecs and local protocols',()=>{
  for(const [format,operation,codec] of [['mov','video','libvpx'],['mpeg','video','libvpx'],['wav','audio','libmp3lame'],['mp3','audio','libmp3lame']]){
    const binding=mediaLpacBinding({operation,format,input:path.join(host,'input.media'),output:path.join(host,'playback')},root);
    assert.equal(binding.program,'ffmpeg.exe');assert.ok(binding.args.includes(codec));
    assert.equal(binding.args[binding.args.indexOf('-protocol_whitelist')+1],'file');
    assert.equal(binding.args[binding.args.indexOf('-format_whitelist')+1],format);
    assert.equal(binding.args[binding.args.indexOf('-i')+1],path.join(root,'input','source.media'));
    assert.equal(binding.args.at(-1),path.join(root,'out','playback'));
    assert.equal(binding.outputs[0].maxBytes,268435456);
    if(format==='mov'){assert.equal(binding.args[binding.args.indexOf('-enable_drefs')+1],'0');assert.equal(binding.args[binding.args.indexOf('-use_absolute_path')+1],'0');}
  }
});
test('probe and metadata profiles do not gain conversion outputs or arbitrary commands',()=>{
  for(const format of ['mov','mpeg','wav','mp3']){const binding=mediaLpacBinding({operation:'probe-input',format,tool:'ffprobe',input:path.join(host,'input.media')},root);assert.equal(binding.outputs.length,0);assert.equal(binding.inputs[0].maxBytes,104857600);assert.ok(binding.args.includes('json'));}
  for(const format of ['matroska,webm','mp3']){const binding=mediaLpacBinding({operation:'probe-output',format,tool:'ffprobe',input:path.join(host,'playback')},root);assert.equal(binding.inputs[0].maxBytes,268435456);}
  for(const tool of ['ffmpeg','ffprobe'])for(const operation of ['version','license']){const binding=mediaLpacBinding({operation,tool},root);assert.equal(binding.program,tool+'.exe');assert.deepEqual(binding.inputs,[]);assert.deepEqual(binding.outputs,[]);}
  for(const request of [{operation:'exec',tool:'cmd'},{operation:'version',input:path.join(host,'x')},{operation:'version',args:['-i','http://x']},{operation:'video',format:'hls',input:path.join(host,'x'),output:path.join(host,'playback')},{operation:'probe-output',tool:'ffprobe',format:'mov',input:path.join(host,'playback')},{operation:'audio',format:'mp3',input:path.join(host,'playback'),output:path.join(host,'playback')}])assert.throws(()=>mediaLpacBinding(request,root));
});

test('actual image worker renders bound multi-stamp export identically without changing sources',{skip:process.env.SIV_NATIVE_TESTS!=='1'||process.platform!=='win32'},async()=>{
  const sharp=(await import('sharp')).default,fixture=await fs.mkdtemp(path.join(os.tmpdir(),'siv-bind-')),source=path.join(fixture,'sources'),work=path.join(fixture,'host'),staging=path.join(fixture,'sandbox');
  await fs.mkdir(source);await fs.mkdir(work);await fs.mkdir(path.join(staging,'input'),{recursive:true});await fs.mkdir(path.join(staging,'out'));
  for(const directory of [work,path.join(staging,'out')])openWorkLease(directory,{create:true}).close();
  const input=path.join(source,'original.png'),a=path.join(source,'a.png'),b=path.join(source,'b.png');
  for(const [file,color] of [[input,'white'],[a,'red'],[b,'blue']])await sharp({create:{width:64,height:64,channels:4,background:color}}).png().toFile(file);
  const job={...edit(),input,output:path.join(work,'export.png'),items:[{...note,text:'@INPUT@ @OUTPUT@'},{...stamp(a),x:0},{...stamp(b),x:15},{...stamp(a),x:30}]};
  const binding=imageLpacBinding(job,staging),originals=await Promise.all(binding.inputs.map(value=>fs.readFile(value.source)));
  for(const entry of binding.inputs)await fs.copyFile(entry.source,path.join(staging,'input',entry.name));
  const engine=new ImageEngine(await settings({development:true}));
  try{await engine.run(job);await engine.run(binding.document);
    const direct=await sharp(job.output).raw().toBuffer(),bound=await sharp(binding.document.output).raw().toBuffer();assert.deepEqual(bound,direct);
    assert.deepEqual(await Promise.all(binding.inputs.map(value=>fs.readFile(value.source))),originals);
    assert.deepEqual((await fs.readdir(path.join(staging,'out'))).sort(),['export.png','lease.sqlite']);
  }finally{await engine.close();}
});
