import {buildWeb} from './build-web.mjs';
import {start} from '../backend/main.mjs';
await buildWeb();await start(true);
