import test from 'node:test';import assert from 'node:assert/strict';import path from 'node:path';
import {PlaybackService} from '../../../playback/service/ffmpeg.mjs';import {mediaLpacBinding} from '../../../conversion/util/lpac-bindings.mjs';import {encodeMediaLpacRequest} from '../../../playback/util/lpac-media-request.mjs';
test('media binary protocol encodes only fixed operations, tool and format selectors',()=>{
 const root=path.resolve('build/media-wire/sandbox'),work=path.resolve('build/media-wire/work'),runtime={kind:'ffmpeg',identity:'b'.repeat(64),path:path.resolve('build/media-wire/version/payload'),useFile:path.resolve('build/media-wire/version/runtime.use')},limits={timeoutMs:120000,jobMemoryMiB:512,jobCpuSeconds:30,jobCpuRatePercent:100};
 const binding=mediaLpacBinding({operation:'video',format:'mov',input:path.resolve('build/media-wire/source.mp4'),output:path.join(work,'playback')},root),bytes=encodeMediaLpacRequest(binding,runtime,work,limits);
 assert.equal(bytes.subarray(0,8).toString(),'SIVLPAC1');assert.equal(bytes.readUInt32LE(8),4);assert.equal(bytes.readUInt32LE(12),0);assert.equal(bytes.readUInt32LE(16),1);
 assert.ok(!bytes.includes(Buffer.from('libvpx')));assert.ok(!bytes.includes(Buffer.from('-vf')));
 assert.throws(()=>encodeMediaLpacRequest(binding,{...runtime,kind:'node'},work,limits),{code:'RUNTIME_REFERENCE_REQUIRED'});assert.throws(()=>encodeMediaLpacRequest(binding,runtime,work,{...limits,timeoutMs:120001}),{code:'INVALID_LPAC_LIMITS'});
});
test('LPAC selection rejects direct argv and releases owned probe work on failure',async()=>{
 let created=0,released=0,ran=0;const work=path.resolve('build/media-probe-work'),store={playbackWork:{create:async()=>{created++;return work;},release:async file=>{assert.equal(file,work);released++;}}},transport={run:async(job,{runtime})=>{ran++;assert.equal(job.work,work);assert.equal(runtime.kind,'ffmpeg');throw Error('probe failed');},close:async()=>{}},service=new PlaybackService({playback:{}},store,{},transport);
 await assert.rejects(service.run('ignored.exe',['-version']),{code:'LPAC_PROFILE_REQUIRED'});assert.equal(ran,0);
 await assert.rejects(service.runProfile({operation:'version'},30000,null,{kind:'ffmpeg'}),/probe failed/);assert.equal(created,1);assert.equal(released,1);await service.close();await assert.rejects(service.runProfile({operation:'version'}),{code:'PROCESSING_CLOSING'});
});
test('invalid profile is rejected before allocating work or invoking a transport',async()=>{
 const service=new PlaybackService({playback:{}},{playbackWork:{create:()=>assert.fail('no work')}},{},{run:()=>assert.fail('no spawn'),close:async()=>{}});
 await assert.rejects(service.runProfile({operation:'video',format:'hls',input:path.resolve('build/x'),output:path.resolve('build/playback')},30000,null,{kind:'ffmpeg'}),{code:'INVALID_MEDIA_PROFILE'});await service.close();
});
