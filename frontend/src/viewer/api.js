// Same-origin API client. Non-GET requests carry the CSRF token received from bootstrap.
// Failures throw Error(code) using the server's {code}; the caller maps it with messages.js.
export function createApi(csrf){
  return async function api(path,{method='GET',json,body,binary=false,signal,keepalive=false}={}){
    const headers={};if(method!=='GET'){if(!csrf.current)throw Error('CSRF_INVALID');headers[csrf.current.headerName]=csrf.current.token;}
    if(json!==undefined){headers['Content-Type']='application/json';body=JSON.stringify(json);}
    const response=await fetch(path,{method,headers,body,credentials:'same-origin',cache:'no-store',redirect:'error',signal,keepalive});
    if(!response.ok){let code='REQUEST_FAILED';try{code=(await response.json()).code||code;}catch{}throw Error(code);}
    return binary?response.blob():response.status===204?null:response.json();
  };
}
