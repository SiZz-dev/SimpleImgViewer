import {start} from '../backend/main.mjs';
if(Number(process.versions.node.split('.')[0])!==24)throw Error('Node.js 24 is required');
await start(process.argv.includes('--dev'));
