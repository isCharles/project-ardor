import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {chromium} from "playwright";
const origin="http://127.0.0.1:3113";
const server=spawn(process.execPath,["node_modules/next/dist/bin/next","start","-p","3113"],{stdio:"pipe"});
let logs="",browser;
server.stdout.on("data",x=>logs+=x);server.stderr.on("data",x=>logs+=x);
const summary={learningPlans:7,recordedLearningAttempts:9,flashcards:3,replayAttempts:5};
function track(id) {
  return {question:{id,questionText:"Synthetic gap "+id,recapId:"record-"+id,recapTitle:"Source interview "+id,
    observation:"A bounded source observation.",performance:"WEAK",interviewId:"source-interview",roundId:"source-round"},
    learningPlans:[{id:"plan-a-"+id,concept:"First exact plan "+id,status:"COMPLETED",attemptCount:2,lastScore:81,targetInterviewId:"another-interview",targetRoundId:"target-round"},
      {id:"plan-b-"+id,concept:"Second exact plan "+id,status:"SCHEDULED",attemptCount:0,lastScore:null,targetInterviewId:null,targetRoundId:null}],
    interviewLearningPlans:[{id:"broad-"+id,concept:"Whole interview plan "+id,status:"SCHEDULED",attemptCount:0,lastScore:null,targetInterviewId:null,targetRoundId:null}],
    cards:[{id:"card-a-"+id,status:"REVIEW",repetitions:3},{id:"card-b-"+id,status:"SUSPENDED",repetitions:1}],
    replay:{attemptCount:4,latestVerdict:"CLEARER",latestAt:"2026-10-20T01:00:00Z"}};
}
try {
  let ready=false;
  for(let i=0;i<100;i++){if(server.exitCode!==null)throw new Error(logs);try{if((await fetch(origin)).ok){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}
  assert.ok(ready,logs);
  browser=await chromium.launch({headless:true,...(process.env.ARDOR_UI_BROWSER_CHANNEL?{channel:process.env.ARDOR_UI_BROWSER_CHANNEL}:{})});
  const context=await browser.newContext({viewport:{width:1440,height:1100}}),calls=[];
  let responseDelay=0,fail=false;
  await context.route("**/api/**",async route=>{
    const url=new URL(route.request().url()),path=url.pathname;calls.push(path+url.search);
    let json=[];
    if(path==="/api/auth/me")json={id:"progress-fixture",email:"fixture@ardor.invalid",admin:false};
    else if(path==="/api/profile/sidebar")json={userId:"progress-fixture",hrefs:["/app"],revision:0,configured:true};
    else if(path==="/api/background-tasks/summary")json={activeCount:0,unreadByModule:{}};
    else if(path==="/api/agent/permissions")json={globalLevel:0,modules:[]};
    else if(path==="/api/learning-progress"){
      if(responseDelay)await new Promise(r=>setTimeout(r,responseDelay));
      if(fail)return route.fulfill({status:503,json:{message:"SyntheticLongError".repeat(400)}});
      const page=Number(url.searchParams.get("page")),empty=url.searchParams.has("sourceInterviewId");
      json={page,hasNext:!empty&&page===0,totalQuestions:empty?0:2,unresolvedSources:0,accountSummary:summary,
        tracks:empty?[]:[track(page===0?"first":"second")]};
    }
    await route.fulfill({json});
  });
  const page=await context.newPage(),errors=[];page.on("pageerror",e=>errors.push(e.message));
  await page.goto(origin+"/app/learning/progress");
  await page.getByRole("heading",{name:"Synthetic gap first",exact:true}).waitFor();
  assert.equal(await page.getByRole("link",{name:/Question practice attempts/}).count(),0,"An account total must not link to a question-required route without a question");
  assert.equal(await page.getByRole("link",{name:"Try again",exact:true}).getAttribute("href"),"/app/learning/practice/questions?question=first");
  assert.equal(await page.locator('[data-progress-question="first"]').count(),1);
  for(const name of ["First exact plan first","Second exact plan first"])await page.getByRole("link",{name,exact:true}).waitFor();
  assert.match(await page.getByRole("link",{name:"Preparing for another interview · Specific round",exact:true}).getAttribute("href"),/selected=another-interview/);
  await page.getByText("Interview-wide plans · 1",{exact:true}).click();
  await page.getByRole("link",{name:"Whole interview plan first",exact:true}).waitFor();
  const first=page.locator('[data-progress-question="first"]');
  assert.equal(await first.locator('a[href^="/app/cards?card="]').count(),2);
  assert.match(await first.innerText(),/Clearer/);
  responseDelay=300;
  await page.getByRole("button",{name:"Load more",exact:true}).click();
  await page.getByRole("button",{name:"Loading…",exact:true}).waitFor();
  await page.getByRole("heading",{name:"Synthetic gap second",exact:true}).waitFor();
  assert.equal(await page.locator("[data-progress-question]").count(),2);
  assert.equal(await page.getByRole("button",{name:"Load more",exact:true}).count(),0);
  const count=calls.filter(x=>x.startsWith("/api/learning-progress")).length;
  await page.evaluate(()=>{localStorage.setItem("ardor:locale","zh-CN");window.dispatchEvent(new Event("ardor:locale-change"));});
  await page.getByRole("heading",{name:"看见每一步的进展。",exact:true}).waitFor();
  assert.equal(await page.locator("[data-progress-question]").count(),2);
  assert.equal(calls.filter(x=>x.startsWith("/api/learning-progress")).length,count,"Language switch must not reload or reset pages");
  await page.getByRole("button",{name:"刷新",exact:true}).click();
  await page.getByRole("button",{name:"刷新",exact:true}).locator("svg.animate-spin").waitFor();
  await page.getByRole("heading",{name:"Synthetic gap first",exact:true}).waitFor();
  assert.equal(await page.locator("[data-progress-question]").count(),1,"Refresh restarts the paged view");
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:".next/learning-progress-mobile.png",fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true);
  responseDelay=600;
  const oldResponse=page.waitForResponse(r=>new URL(r.url()).pathname==="/api/learning-progress"&&!new URL(r.url()).searchParams.has("sourceInterviewId"));
  const requestSent=page.waitForRequest(r=>new URL(r.url()).pathname==="/api/learning-progress");
  await page.getByRole("button",{name:"刷新",exact:true}).click();
  await requestSent;
  await page.evaluate(()=>window.history.pushState(null,"","/app/learning/progress?sourceInterviewId=empty"));
  await page.getByRole("heading",{name:"这里还没有面试补强记录。",exact:true}).waitFor();
  await oldResponse;
  assert.equal(await page.locator("[data-progress-question]").count(),0,"A late response from a previous source must not replace the current scope");
  await page.goto(origin+"/app/evidence?sourceInterviewId=empty");
  await page.getByRole("heading",{name:"这里还没有面试补强记录。",exact:true}).waitFor();
  assert.match(await page.getByRole("region",{name:"账户活动",exact:true}).innerText(),/7/,"Autonomous account activity remains visible");
  assert.equal(await page.locator("[data-progress-question]").count(),0);
  fail=true;
  await page.getByRole("button",{name:"刷新",exact:true}).click();
  await page.getByRole("alert").waitFor();
  assert.equal(await page.getByRole("alert").locator("details").getAttribute("open"),null);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true,"Long failure must not overflow");
  const forbidden=["/api/interview-recaps","/api/memory-cards","/api/learning-plans"];
  assert.deepEqual(calls.filter(x=>forbidden.some(path=>x===path)),[],"Progress must not download whole domain lists");
  assert.deepEqual(errors,[]);
  console.log("PASS: paged owned progress, all linked plans/cards, distinct preparation targets, replay facts, locale stability, refresh feedback, legacy URL, autonomous totals and bounded errors");
} catch(error){console.error(logs.slice(-1500));throw error;}
finally{await browser?.close();server.kill();}
