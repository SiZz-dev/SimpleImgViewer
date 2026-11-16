import path from 'node:path';
import {settings} from '../../system/config/settings.mjs';
import {ImageEngine} from '../../conversion/service/image-engine.mjs';
import {WorkDirectories} from '../../conversion/service/work-directories.mjs';
const config=await settings({development:true});const work=new WorkDirectories(process.argv[2],config.temporary);await work.init();
const directory=await work.create(),images=new ImageEngine(config);
images.run({input:process.argv[3],operation:'preview',page:1,edge:64,output:path.join(directory,'preview.png'),thumbnail:path.join(directory,'thumbnail.png')}).catch(()=>{});
const timer=setInterval(()=>{const child=[...images.children][0];if(child?.pid){clearInterval(timer);process.send({launcherPid:child.pid,work:directory});}},10);
