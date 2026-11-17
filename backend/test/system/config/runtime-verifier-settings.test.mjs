import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import path from 'node:path';import os from 'node:os';
import {settings} from '../../../system/config/settings.mjs';import {ManagedRuntimes} from '../../../conversion/service/managed-runtimes.mjs';
test('default main verification creates no worker pool',async()=>{const config=await settings();assert.equal(config.runtimeVersions.verification.mode,'main');const runtimes=new ManagedRuntimes(config.runtimeVersions);assert.equal(runtimes.verifier,undefined);await runtimes.close();});
test('worker verification requires managed versions and rejects unknown or unsafe policy',async()=>{
 await assert.rejects(settings({overrides:{runtimeVersions:{verification:{mode:'worker'}}}}),{code:'RUNTIME_VERIFIER_REQUIRES_MANAGED_RUNTIMES'});
 for(const verification of [null,false,[],{mode:'none'},{threads:2},{workerUrl:'bad'},{mode:1},{maxQueue:-1},{maxQueue:33},{maxQueue:'8'},{timeoutMs:999},{timeoutMs:120001}])await assert.rejects(settings({overrides:{runtimeVersions:{enabled:true,verification}}}),{code:'INVALID_CONFIGURATION'});
});
test('verification YAML and overrides merge without dropping the chosen worker mode',async()=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-verify-settings-')),file=path.join(root,'install.yml'),previous=process.env.SIMPLEIMGVIEWER_CONFIG;
 await fs.writeFile(file,'runtimeVersions:\n  enabled: true\n  verification:\n    mode: worker\n    maxQueue: 3\n');process.env.SIMPLEIMGVIEWER_CONFIG=file;
 try{const config=await settings({overrides:{runtimeVersions:{verification:{timeoutMs:12000}}}});assert.deepEqual(config.runtimeVersions.verification,{mode:'worker',maxQueue:3,timeoutMs:12000});const runtimes=new ManagedRuntimes(config.runtimeVersions);assert.equal(runtimes.verifier.policy.threads,1);assert.equal(runtimes.verifier.slots.size,0);await runtimes.close();}
 finally{if(previous===undefined)delete process.env.SIMPLEIMGVIEWER_CONFIG;else process.env.SIMPLEIMGVIEWER_CONFIG=previous;}
});
