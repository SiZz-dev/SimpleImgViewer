import {spawn} from 'node:child_process';import {settings} from '../../system/config/settings.mjs';import {WindowsJobRunner} from '../../conversion/util/windows-job.mjs';
const [executable,useFile]=process.argv.slice(2),config=await settings({development:true});
const command=await new WindowsJobRunner(config.processing).command(executable,['-e','console.log(JSON.stringify({pid:process.pid}));setInterval(()=>{},1000)'],null,useFile);
const child=spawn(command.executable,command.args,{windowsHide:true,stdio:['pipe','pipe','pipe']});child.stdin.on('error',()=>{});child.stderr.resume();let output='';
child.stdout.on('data',bytes=>{output+=bytes;try{const worker=JSON.parse(output);process.send({launcherPid:child.pid,workerPid:worker.pid});}catch{}});
process.on('message',message=>{if(message==='kill-launcher')child.kill('SIGKILL');});
