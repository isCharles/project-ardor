import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {chromium} from "playwright";
const origin="http://127.0.0.1:3119";
const server=spawn(process.execPath,["node_modules/next/dist/bin/next","start","-p","3119"],{stdio:"pipe"});
let logs="",browser;
server.stdout.on("data",x=>logs+=x);server.stderr.on("data",x=>logs+=x);
try {
  for(let i=0;i<100;i++){try{if((await fetch(origin)).ok)break;}catch{} await new Promise(r=>setTimeout(r,100));}
  browser=await chromium.launch({headless:true,...(process.env.ARDOR_UI_BROWSER_CHANNEL?{channel:process.env.ARDOR_UI_BROWSER_CHANNEL}:{})});
  const context=await browser.newContext({viewport:{width:1400,height:950}});
  const raw="RAW ORIGINAL: interviewer explains three options, candidate picks one. ".repeat(30);
  let draft={id:"draft-fixture",rawContent:raw,minutes:"## Interview\nInterviewer explains three options. Candidate answer is uncertain; please correct the attribution.",revision:1,status:"COMPLETED",reviews:[]};
  let writes=0,confirmations=0,offline=false,conflict=false;
  await context.route("**/api/**",async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;
    let json=[];
    if(path==="/api/auth/me")json={id:"minutes-fixture",email:"fixture@ardor.invalid",admin:false};
    else if(path==="/api/auth/csrf")json={token:"fixture",headerName:"X-XSRF-TOKEN"};
    else if(path==="/api/profile/sidebar")json={userId:"minutes-fixture",hrefs:["/app"],revision:0,configured:true};
    else if(path==="/api/background-tasks/summary")json={activeCount:0,unreadByModule:{}};
    else if(path==="/api/agent/permissions")json={globalLevel:0,modules:[]};
    else if(path==="/api/interview-recaps/drafts")json=[{id:draft.id,company:"Synthetic interview",status:"COMPLETED",revision:draft.revision}];
    else if(path==="/api/interview-recaps/drafts/draft-fixture"){
      if(request.method()==="PUT"){
        writes++;
        if(offline)return route.abort("failed");
        if(conflict)return route.fulfill({status:409,json:{message:"Version conflict"}});
        const body=request.postDataJSON();assert.equal(body.revision,draft.revision);
        draft={...draft,minutes:body.minutes,revision:draft.revision+1};
      }
      json=draft;
    } else if(path.endsWith("/draft-fixture/confirm")){
      confirmations++; const body=request.postDataJSON();assert.equal(body.revision,draft.revision);
      draft={...draft,reviews:[{id:"review-fixture",revision:draft.revision,status:"COMPLETED",recapId:"report-fixture",minutes:draft.minutes}]};json=draft;
    }
    await route.fulfill({json});
  });
  const page=await context.newPage(),errors=[];page.on("pageerror",e=>errors.push(e.message));
  await page.goto(`${origin}/app/recaps?draft=draft-fixture`);
  const editor=page.getByLabel("Editable minutes · auto-saved");
  await editor.waitFor();assert.equal(confirmations,0);
  const corrected="## Corrected interview\nThe interviewer described workflow, plan-executor and coding agents. I selected plan-executor; I did not propose all three.";
  await editor.fill(corrected);
  await page.waitForFunction(()=>document.body.textContent.includes("Saved"));
  assert.equal(draft.minutes,corrected);assert.equal(confirmations,0,"Auto-save must never confirm");
  await page.getByRole("button",{name:"Confirm minutes & generate review",exact:true}).click();
  await page.getByRole("link",{name:"Open detailed review",exact:true}).waitFor();
  assert.equal(confirmations,1);assert.equal(draft.reviews[0].minutes,corrected);
  await editor.fill(corrected+"\nA later correction.");
  await page.waitForFunction(()=>document.body.textContent.includes("Saved"));
  await page.getByText("Minutes changed since this review",{exact:true}).waitFor();
  assert.equal(draft.reviews[0].minutes,corrected);
  await page.reload();await editor.waitFor();assert.equal(await editor.inputValue(),draft.minutes);
  offline=true;
  const unsaved=corrected+"\nOffline draft must survive refresh.";
  await editor.fill(unsaved);await page.getByText("Not saved",{exact:true}).waitFor();
  offline=false;await page.reload();await editor.waitFor();assert.equal(await editor.inputValue(),unsaved);
  await page.waitForFunction(()=>document.body.textContent.includes("Saved"));
  conflict=true;await editor.fill(unsaved+"\nConflict edit stays here.");
  await page.getByRole("alert").filter({hasText:"Another edit changed"}).waitFor();
  assert.match(await editor.inputValue(),/Conflict edit stays here/);
  assert.equal(confirmations,1);
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),true,"Editor must fit narrow screens");
  await editor.scrollIntoViewIfNeeded();
  await page.screenshot({path:".next/interview-minutes-mobile.png"});
  assert.deepEqual(errors,[]);assert.ok(writes>=4);
  console.log("PASS: editable minutes, auto-save, explicit confirmation, immutable snapshot, refresh/offline recovery, conflict preservation, narrow layout");
} catch(error){console.error(logs.slice(-1500));throw error;}
finally{await browser?.close();server.kill();}
