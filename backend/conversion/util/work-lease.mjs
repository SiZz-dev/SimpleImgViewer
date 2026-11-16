import {DatabaseSync} from 'node:sqlite';
import {lstatSync,realpathSync} from 'node:fs';
import path from 'node:path';

export function openWorkLease(directory,{create=false}={}){
  if(realpathSync(directory)!==directory||!lstatSync(directory).isDirectory())throw Error('unsafe-work');
  const file=path.join(directory,'lease.sqlite');
  if(!create){const stat=lstatSync(file);if(!stat.isFile()||stat.isSymbolicLink()||stat.size>262144||realpathSync(file)!==file)throw Error('unsafe-lease');}
  const database=new DatabaseSync(file,{timeout:0,allowExtension:false});
  try{
    database.exec('BEGIN EXCLUSIVE');
    if(create)database.exec("CREATE TABLE lifecycle (state TEXT NOT NULL); INSERT INTO lifecycle VALUES ('active'); COMMIT; BEGIN EXCLUSIVE;");
    const rows=database.prepare('SELECT state FROM lifecycle').all();
    if(rows.length!==1||!['active','retired'].includes(rows[0].state))throw Error('invalid-lease');
    let state=rows[0].state;
    // Marker creation precedes the SQLite commit. A crash in between must also
    // prevent an image worker from resuming this retired work item.
    try{lstatSync(path.join(directory,'retired'));state='retired';}catch(error){if(error.code!=='ENOENT')throw error;}
    return {database,state,retire(){database.exec("UPDATE lifecycle SET state='retired'; COMMIT; BEGIN EXCLUSIVE;");},close(){database.close();}};
  }catch(error){database.close();throw error;}
}
