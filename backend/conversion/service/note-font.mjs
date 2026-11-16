import fs from 'node:fs/promises';import path from 'node:path';import {fileURLToPath} from 'node:url';
import {readJson,regular,digest} from '../../media/util/files.mjs';import {requireThat} from '../../system/util/errors.mjs';
const policyFile=fileURLToPath(new URL('../../../config/oss/note-font.windows-x64.json',import.meta.url)),configFile=fileURLToPath(new URL('../../../config/oss/note-fonts.conf',import.meta.url)),noticeFile=fileURLToPath(new URL('../../../config/oss/note-font-NOTICE.txt',import.meta.url));
export async function approvedNoteFont(runtime){
 const root=path.join(runtime,'fonts');try{await fs.lstat(root);}catch(error){if(error.code==='ENOENT')return false;throw error;}
 const policy=await readJson(policyFile),profile=await readJson(path.join(root,'profile.json'));
 requireThat(profile.schemaVersion===1&&profile.id===policy.id&&profile.family===policy.family,503,'NOTE_FONT_REJECTED');
 for(const {name,sha256} of [...policy.files,{name:'fonts.conf',sha256:await digest(configFile)},{name:'NOTICE.txt',sha256:await digest(noticeFile)}]){const file=path.join(root,name),stat=await regular(file,33554432);requireThat(stat.nlink===1&&await digest(file)===sha256,503,'NOTE_FONT_REJECTED');}
 return true;
}
