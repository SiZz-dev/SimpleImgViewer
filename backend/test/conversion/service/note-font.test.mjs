import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import path from 'node:path';import os from 'node:os';
import {approvedNoteFont} from '../../../conversion/service/note-font.mjs';import {ManagedRuntimes} from '../../../conversion/service/managed-runtimes.mjs';
test('runtime without a font profile remains compatible and does not claim note support',async()=>{const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-no-font-'));assert.equal(await approvedNoteFont(root),false);});
test('unapproved font profile releases an acquired generation instead of falling back',async()=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'siv-bad-font-'));await fs.mkdir(path.join(root,'backend/conversion/util'),{recursive:true});await fs.mkdir(path.join(root,'fonts'));await fs.writeFile(path.join(root,'node.exe'),'synthetic, never executed');await fs.writeFile(path.join(root,'backend/conversion/util/image-worker.mjs'),'synthetic, never executed');await fs.writeFile(path.join(root,'fonts/profile.json'),JSON.stringify({schemaVersion:1,id:'unapproved-font',family:'Unknown'}));
 let releases=0;const manager=new ManagedRuntimes({root:path.join(root,'unused')});manager.versions.acquire=async()=>({path:root,release:async()=>{releases++;}});await assert.rejects(manager.acquire('node'),{code:'NOTE_FONT_REJECTED'});assert.equal(releases,1);
});
