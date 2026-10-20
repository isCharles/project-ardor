import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";
const origin="http://127.0.0.1:3112";
const server=spawn(process.execPath,["node_modules/next/dist/bin/next","start","-p","3112"],{stdio:"pipe"});
let logs="",browser;
server.stdout.on("data",value=>{logs+=value;});server.stderr.on("data",value=>{logs+=value;});
try {
  let ready=false;
  for(let i=0;i<100;i++){if(server.exitCode!==null)throw new Error(logs);try{if((await fetch(origin)).ok){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}
  assert.ok(ready,logs);
  browser=await chromium.launch({headless:true,...(process.env.ARDOR_UI_BROWSER_CHANNEL?{channel:process.env.ARDOR_UI_BROWSER_CHANNEL}:{})});
  const context=await browser.newContext({viewport:{width:1440,height:1000}}),calls=[];
  const template={requestId:null,reason:"Synthetic preparation",sourceType:"RECAP",sourceId:"question-fixture",status:"SCHEDULED",
    lesson:{summary:"Synthetic lesson",keyPoints:[]},exercises:[],lastEvaluation:{},lastScore:null,attemptCount:0,
    scheduledAt:"2099-10-19T01:00:00Z",nextReviewAt:null,completedAt:null,createdAt:"2026-10-19T01:00:00Z"};
  const plans=[{...template,id:"context-plan",concept:"Canonical source lesson",originKind:"INTERVIEW_QUESTION",originResourceId:"question-fixture",originRecordId:"record-fixture",targetInterviewId:"target-fixture",targetRoundId:"round-fixture"},
    {...template,id:"legacy-plan",concept:"Unresolved historical lesson",originKind:"LEGACY_RECAP",originResourceId:"missing-fixture",originRecordId:null,targetInterviewId:null,targetRoundId:null}];
  await context.route("**/api/**",async route=>{
    const path=new URL(route.request().url()).pathname;calls.push(path);
    const json=path==="/api/auth/me"?{id:"context-ui-owner",email:"context@ardor.invalid",admin:false}
      :path==="/api/profile/sidebar"?{userId:"context-ui-owner",hrefs:["/app"],revision:0,configured:true}
      :path==="/api/background-tasks/summary"?{activeCount:0,unreadByModule:{}}
      :path==="/api/learning-plans"?plans
      :path.startsWith("/api/learning-plans/")?plans.find(plan=>path.endsWith(plan.id))??[]
      :path==="/api/interview-recaps/record-fixture"?{id:"record-fixture",title:"Original source interview",jobOpportunityId:"source-fixture",questions:[]}
      :path==="/api/opportunities/target-fixture"?{id:"target-fixture",company:"Next employer",targetRole:"Backend",rounds:[{id:"round-fixture",roundNumber:2}]}
      :[];
    await route.fulfill({json});
  });
  const page=await context.newPage(),errors=[];page.on("pageerror",error=>errors.push(error.message));
  await page.goto(`${origin}/app/learning?id=context-plan`);
  const source=page.getByRole("link",{name:"Source interview question",exact:true});
  await source.waitFor();assert.equal(await source.getAttribute("href"),"/app/job-search/notes?selected=record-fixture");
  await page.getByText("Next employer",{exact:false}).waitFor();
  const target=page.getByRole("link",{name:"Preparation target",exact:true});
  assert.equal(await target.getAttribute("href"),"/app/job-search/interviews?selected=target-fixture");
  assert.match(await target.innerText(),/Round 2/);
  assert.ok(!calls.includes("/api/interview-recaps"),"Canonical question source must not scan all reports");
  assert.ok(!calls.includes("/api/interview-recaps/question-fixture"),"Question identity must not be probed as a report identity");
  assert.ok(!calls.includes("/api/opportunities/source-fixture"),"Source interview is not the preparation target");
  await page.screenshot({path:".next/learning-context.png",fullPage:true});
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true);
  await page.goto(`${origin}/app/learning?id=legacy-plan`);
  await page.getByText("Historical source needs verification",{exact:true}).waitFor();
  assert.equal(await page.getByRole("link",{name:"Preparation target",exact:true}).count(),0);
  assert.ok(!calls.includes("/api/interview-recaps/missing-fixture"));
  assert.deepEqual(errors,[]);
  console.log("PASS: canonical source and independent target links, no table guessing, unresolved history, narrow layout");
} finally {await browser?.close();server.kill();}
