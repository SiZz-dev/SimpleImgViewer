import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {pathToFileURL} from "node:url";
import path from "node:path";
import {mkdir,writeFile} from "node:fs/promises";

// Invoked only by NativeViewerIntegrationTests. Identity is a random test-only HTTP header.
const [origin,token,mediaId]=process.argv.slice(2);
assert.match(origin,/^http:\/\/127\.0\.0\.1:\d+$/);assert.ok(token&&mediaId);
const require=createRequire(pathToFileURL(path.join(process.env.IMAGEGATE_NODE_MODULES,"package.json")));
const {chromium}=require("playwright");
const browser=await chromium.launch({headless:true,executablePath:process.env.IMAGEGATE_BROWSER});
const out=path.resolve("build/native-viewer-tests");await mkdir(out,{recursive:true});
try{
  const context=await browser.newContext({viewport:{width:1440,height:1000},extraHTTPHeaders:{Authorization:"Bearer "+token}});
  const page=await context.newPage(),errors=[],pages=[];page.on("pageerror",e=>errors.push(e.message));
  const fetchJson=async(url,options={})=>{const r=await context.request.fetch(origin+url,options);assert.ok(r.ok(),url+" "+r.status()+" "+await r.text());return r.json();};
  const bootstrap=await fetchJson("/api/v1/viewer/bootstrap");
  const session=await fetchJson("/api/v1/viewer/sessions",{method:"POST",headers:{[bootstrap.csrf.headerName]:bootstrap.csrf.token},data:{mediaIds:[mediaId]}});
  page.on("response",async response=>{if(response.url()===origin+"/api/v1/media/"+mediaId+"/pages/1"&&response.ok())try{pages.push(await response.json());}catch{}});
  await page.goto(origin+"/viewer?sessionId="+session.sessionId);
  await page.waitForFunction(()=>!document.querySelector("#add-note").disabled,{},{timeout:90000});
  await page.waitForFunction(()=>document.querySelectorAll("#tile-layer img").length>0);
  assert.ok(pages.some(p=>!p.originalCoordinatesAvailable),"native preview starts without editing geometry");
  const verified=pages.find(p=>p.originalCoordinatesAvailable);assert.ok(verified);assert.equal(verified.coordinateWidth,1026);assert.equal(verified.coordinateHeight,514);
  await page.locator("#actual-size").click();await page.locator("#rotate-right").click();
  const box=await page.locator("#viewport").boundingBox();
  await page.locator("#add-note").click();await page.mouse.click(box.x+box.width/2,box.y+box.height/2);await page.locator(".annotation-note").waitFor();
  const endpoint="/api/v1/media/"+mediaId+"/pages/1/annotations";
  let doc=await fetchJson(endpoint);const note=doc.items.find(i=>i.type==="note");assert.ok(note);assert.ok(Math.abs(note.x-513)<3&&Math.abs(note.y-257)<3);
  const stamp=await page.evaluate(()=>{const c=document.createElement("canvas");c.width=32;c.height=24;const g=c.getContext("2d");g.fillStyle="#e11d48";g.fillRect(0,0,32,24);return c.toDataURL("image/png").split(",")[1];});
  await page.locator("#stamp-input").setInputFiles({name:"synthetic-stamp.png",mimeType:"image/png",buffer:Buffer.from(stamp,"base64")});
  await page.waitForFunction(()=>document.querySelector("#add-stamp").getAttribute("aria-pressed")==="true");
  await page.mouse.click(box.x+box.width/2,box.y+box.height/2);await page.locator(".annotation-stamp").waitFor();
  doc=await fetchJson(endpoint);const item=doc.items.find(i=>i.type==="stamp");assert.ok(item);assert.ok(Math.abs(item.x-513)<3&&Math.abs(item.y-257)<3);
  await page.screenshot({path:path.join(out,"native-tiff-editing.png")});assert.deepEqual(errors,[]);
  await writeFile(path.join(out,"results.json"),JSON.stringify({passed:true,source:"synthetic RGB8 orientation-1 TIFF",nativePreviewInitiallyUnverified:true,width:1026,height:514,rotation:90,note:{x:note.x,y:note.y},stamp:{x:item.x,y:item.y},errors},null,2));
  console.log("PASS real img2tile -> HTTP -> Chrome: verified geometry, rotated click note and uploaded stamp");await context.close();
}finally{await browser.close();}
