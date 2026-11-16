import {spawn} from 'node:child_process';
import fs from 'node:fs/promises';
const [launcher,request,output]=process.argv.slice(2),child=spawn(launcher,[request],{windowsHide:true,stdio:['pipe','pipe','pipe']});
child.stdin.on('error',()=>{});child.stdout.resume();child.stderr.resume();
const timer=setInterval(async()=>{try{const bytes=await fs.readFile(output),pid=bytes.toString('latin1').match(/\nBOUND-DENIED pid=(\d+)\n$/)?.[1];if(pid){clearInterval(timer);process.send({launcherPid:child.pid,workerPid:Number(pid)});}}catch(error){if(error.code!=='ENOENT')throw error;}},25);
child.once('error',error=>process.send({error:error.code}));
