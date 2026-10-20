import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {spawn} from "node:child_process";
import {chromium} from "playwright";

// Explicit opt-in: creates synthetic users and resources; never run on production.
assert.equal(process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB,"true","Disposable backend required");
const backend=process.env.ARDOR_ACCEPTANCE_API_BASE;
const model=process.env.ARDOR_ACCEPTANCE_LLM_BASE;
assert.ok(backend&&model,"Explicit disposable backend and fake model URLs required");
const origin="http://127.0.0.1:3114";
const server=spawn(process.execPath,["node_modules/next/dist/bin/next","start","-p","3114"],{stdio:"pipe"});
let logs="",browser;server.stdout.on("data",x=>logs+=x);server.stderr.on("data",x=>logs+=x);
try {
  let ready=false;
  for(let i=0;i<100;i++){if(server.exitCode!==null)throw new Error(logs);try{if((await fetch(origin)).ok){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}
  assert.ok(ready,logs);
  browser=await chromium.launch({headless:true,...(process.env.ARDOR_UI_BROWSER_CHANNEL?{channel:process.env.ARDOR_UI_BROWSER_CHANNEL}:{})});
  const context=await browser.newContext({viewport:{width:1440,height:1000}});
  async function req(path,method="GET",data,expected=200){
    const headers={};
    if(method!=="GET"){const c=await context.request.get(backend+"/api/auth/csrf");const v=await c.json();headers[v.headerName]=v.token;}
    const r=await context.request.fetch(backend+path,{method,headers,data});
    assert.equal(r.status(),expected,path+": "+(r.status()===expected?"":(await r.text()).slice(0,250)));
    return r.status()===204?null:r.json();
  }
  async function until(fn,predicate){
    for(let i=0;i<120;i++){const result=await fn();if(predicate(result))return result;await new Promise(r=>setTimeout(r,250));}
    throw new Error("Synthetic fixture workflow timed out");
  }
  await req("/api/auth/register","POST",{email:"progress-live-"+randomUUID()+"@ardor.invalid",password:"Live-"+randomUUID()+"!"},201);
  await req("/api/settings/llm","PUT",{provider:"OPENAI_COMPATIBLE",baseUrl:model,model:"golden-path-model",apiKey:"fake-only"});
  const interview=await req("/api/opportunities","POST",{company:"Live UI source fixture"},201);
  const rounds=await req("/api/opportunities/"+interview.id+"/rounds","POST",{stage:"TECHNICAL"},201);
  await req("/api/opportunities/"+interview.id+"/rounds","POST",{stage:"MANAGER"},201);
  const submit=await req("/api/interview-recaps","POST",{
    content:"Synthetic interview. Interviewer asks about persistence, candidate provides an incomplete comparison.",
    jobOpportunityId:interview.id,interviewRoundId:rounds.rounds[0].id},202);
  const path="/api/interview-recaps/drafts/"+submit.jobId;
  const draft=await until(()=>req(path),d=>Boolean(d.minutes));
  await req(path+"/confirm","POST",{revision:draft.revision,requestId:randomUUID()});
  const done=await until(()=>req(path),d=>d.reviews[0]?.status==="COMPLETED");
  const report=await req("/api/interview-recaps/"+done.reviews[0].recapId),question=report.questions[0];
  // Real transport, including the unsent Ardor handoff; no API response fixtures.
  await context.route("**/api/**",async route=>{
    const u=new URL(route.request().url());const r=await route.fetch({url:backend+u.pathname+u.search});
    await route.fulfill({response:r});
  });
  const page=await context.newPage(),errors=[];page.on("pageerror",e=>errors.push(e.message));
  await page.goto(origin+"/app/learning/progress?sourceInterviewId="+interview.id);
  await page.getByRole("link",{name:"Ask Ardor for a learning plan",exact:true}).click();
  const composer=page.getByRole("textbox",{name:/Message Ardor|给 Ardor 发消息/});
  await composer.waitFor();
  assert.ok((await composer.inputValue()).includes(question.id),"Handoff retains the exact source question");
  assert.equal(await page.getByRole("button",{name:/Remove attachment|移除资料/}).count(),1);
  assert.equal((await req("/api/learning-plans")).length,0,"Opening a suggested draft must not create a plan");
  const plan=await req("/api/learning-plans","POST",{concept:"Live connected progress plan",requestId:randomUUID(),originKind:"INTERVIEW_QUESTION",originResourceId:question.id},201);
  const card=await req("/api/memory-cards","POST",{front:"Synthetic recovery test",back:"Compare recovery loss and latency.",sourceType:"INTERVIEW",recapQuestionId:question.id},201);
  await req("/api/memory-cards/"+card.id+"/reviews","POST",{rating:"GOOD"});
  await req("/api/interview-replays/questions/"+question.id,"POST",{requestId:randomUUID(),answer:"Use recovery requirements to compare snapshot and log trade-offs before deciding."},201);
  await page.goto(origin+"/app/learning/progress?sourceInterviewId="+interview.id);
  await page.getByRole("heading",{name:question.questionText,exact:true}).waitFor();
  assert.equal(await page.locator('a[href="/app/learning?id='+plan.id+'"]').count(),1);
  assert.equal(await page.locator('a[href="/app/cards?card='+card.id+'"]').count(),1);
  assert.match(await page.locator("[data-progress-question]").innerText(),/Attempts · 1/);
  assert.deepEqual(errors,[]);
  await page.screenshot({path:".next/learning-progress-live.png",fullPage:true});
  console.log("PASS: real browser, authenticated disposable API and PostgreSQL; two source rounds, confirmed review, learning, replay and card review render in one owned progress view. No API mocks.");
} catch(error){console.error(logs.slice(-1000));throw error;}
finally{await browser?.close();server.kill();}
