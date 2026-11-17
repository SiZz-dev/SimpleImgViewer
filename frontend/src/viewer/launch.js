// Entry parameters, read once when the module loads.
// /viewer?url= (or #url=) imports a remote file; ?sessionId= restores a session; ?mediaId= opens one file.
const params=new URLSearchParams(location.search),fragment=new URLSearchParams(location.hash.slice(1));
export const incomingUrl=params.get('url')??fragment.get('url');
export const invalidLaunch=incomingUrl!==null&&(params.getAll('url').length+fragment.getAll('url').length!==1||params.has('sessionId')||params.has('mediaId'));
export const initialSession=params.get('sessionId'),initialMedia=params.get('mediaId');
// The imported URL must not stay in the address bar or history.
if(incomingUrl!==null)history.replaceState(null,'','/viewer');
