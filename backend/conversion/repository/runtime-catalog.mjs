import fs from 'node:fs/promises';
import path from 'node:path';
import {DatabaseSync} from 'node:sqlite';
import {safeRuntimePath} from '../util/runtime-inventory.mjs';
import {requireThat} from '../../system/util/errors.mjs';
export class RuntimeCatalog {
  constructor(root){this.root=root;}
  async open(){
    // Reject unrecognised SQLite sidecars before SQLite can open/follow them.
    for(const name of await fs.readdir(this.root))requireThat(['owner.sqlite','owner.sqlite-journal','catalog.sqlite','catalog.sqlite-journal','versions'].includes(name),500,'UNKNOWN_RUNTIME_ENTRY');
    for(const name of ['owner.sqlite','owner.sqlite-journal','catalog.sqlite','catalog.sqlite-journal']){const file=path.join(this.root,name);await safeRuntimePath(file,{missing:true});try{const stat=await fs.lstat(file);requireThat(stat.isFile()&&stat.nlink===1&&stat.size<=4194304,500,'UNSAFE_RUNTIME_CATALOG');}catch(error){if(error.code!=='ENOENT')throw error;}}
    this.owner=new DatabaseSync(path.join(this.root,'owner.sqlite'),{timeout:0,allowExtension:false});
    try{
      this.owner.exec('BEGIN EXCLUSIVE');
      this.db=new DatabaseSync(path.join(this.root,'catalog.sqlite'),{timeout:0,allowExtension:false});
      this.db.exec("PRAGMA trusted_schema=OFF; PRAGMA synchronous=FULL; CREATE TABLE IF NOT EXISTS versions(id TEXT PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL, descriptor TEXT NOT NULL); CREATE TABLE IF NOT EXISTS active(kind TEXT PRIMARY KEY, id TEXT NOT NULL);");
    }catch(error){this.db?.close();this.owner.close();this.owner=null;throw error;}
  }
  list(){return this.db.prepare('SELECT * FROM versions ORDER BY id').all();}
  get(id){return this.db.prepare('SELECT * FROM versions WHERE id=?').get(id);}
  active(kind){return this.db.prepare('SELECT id FROM active WHERE kind=?').get(kind)?.id;}
  add(id,descriptor){this.db.prepare("INSERT INTO versions VALUES (?,?,'publishing',?)").run(id,descriptor.kind,JSON.stringify(descriptor));}
  state(id,state){this.db.prepare('UPDATE versions SET state=? WHERE id=?').run(state,id);}
  activate(kind,id){this.db.exec('BEGIN IMMEDIATE');try{this.db.prepare('INSERT INTO active VALUES (?,?) ON CONFLICT(kind) DO UPDATE SET id=excluded.id').run(kind,id);this.db.exec('COMMIT');}catch(error){this.db.exec('ROLLBACK');throw error;}}
  close(){this.db?.close();this.owner?.close();this.db=null;this.owner=null;}
}
