import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
if(process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB!=="true") throw new Error("Disposable database required");
const base=process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const modelBase=process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
class Client {
  cookies=new Map();
  async request(path,{method="GET",body,expected=200}={}) {
    const headers={};
    const cookie=()=>[...this.cookies].map(([k,v])=>`${k}=${v}`).join("; ");
    if(!["GET","HEAD"].includes(method)) {const csrf=await this.request("/api/auth/csrf");headers[csrf.headerName]=csrf.token;}
    headers.Cookie=cookie();
    if(body) headers["Content-Type"]="application/json";
    const response=await fetch(base+path,{method,headers,body:body?JSON.stringify(body):undefined});
    for(const line of response.headers.getSetCookie()){const [pair]=line.split(";");const split=pair.indexOf("=");this.cookies.set(pair.slice(0,split),pair.slice(split+1));}
    const text=await response.text(); assert.equal(response.status,expected,`${method} ${path}: ${text.slice(0,200)}`);
    return text ? JSON.parse(text):null;
  }
}
async function waitFor(fn,check) {for(let i=0;i<120;i++){const x=await fn();if(check(x))return x;await sleep(250);}throw new Error("Workflow timed out");}
const a=new Client(),b=new Client();
await waitFor(async()=>{try{return (await fetch(base+"/actuator/health")).ok;}catch{return false;}},Boolean);
for(const c of [a,b]) await c.request("/api/auth/register",{method:"POST",expected:201,body:{email:`minutes-${randomUUID()}@ardor.invalid`,password:`Minutes-test-${randomUUID()}!`}});
await a.request("/api/settings/llm",{method:"PUT",body:{provider:"OPENAI_COMPATIBLE",baseUrl:modelBase,model:"golden-path-model",apiKey:"fake-only"}});
const opp=await a.request("/api/opportunities",{method:"POST",expected:201,body:{company:"Synthetic ASR workflow",targetRole:"Backend"}});
const withRound=await a.request(`/api/opportunities/${opp.id}/rounds`,{method:"POST",expected:201,body:{roundNumber:1,stage:"TECHNICAL"}});
const round=withRound.rounds[0];
const otherProcess=await a.request("/api/opportunities",{method:"POST",expected:201,body:{company:"Another synthetic process"}});
const raw="  [00:01] rehearsal and noise. [09:41] Interviewer asks Redis persistence. RAW_ONLY_MARKER. Candidate answer unclear.  ";
await b.request("/api/interview-recaps",{method:"POST",expected:404,body:{content:raw,jobOpportunityId:opp.id,interviewRoundId:round.id}});
await a.request("/api/interview-recaps",{method:"POST",expected:404,body:{content:raw,jobOpportunityId:otherProcess.id,interviewRoundId:round.id}});
const submit=await a.request("/api/interview-recaps",{method:"POST",expected:202,body:{content:raw,jobOpportunityId:opp.id,interviewRoundId:round.id}});
assert.match(submit.nextAction,/确认/);
const url=`/api/interview-recaps/drafts/${submit.jobId}`;
let draft=await waitFor(()=>a.request(url),d=>d.minutes!=null);
assert.equal(draft.rawContent,raw,"Raw transcript must remain unchanged");
assert.equal(draft.reviews.length,0);
assert.equal((await a.request("/api/interview-recaps")).length,0,"No review before human confirmation");
await b.request(url,{expected:404});
await b.request(url,{method:"PUT",expected:404,body:{revision:1,minutes:"Other user must not change these minutes even with a known ID."}});
await b.request(url+"/confirm",{method:"POST",expected:404,body:{revision:1,requestId:randomUUID()}});
const corrected="## Confirmed interview\nInterviewer explained the workflow options. Candidate chose plan-executor. USER_CONFIRMED_ONLY. Unclear answers remain uncertain.";
draft=await a.request(url,{method:"PUT",body:{revision:draft.revision,minutes:corrected}});
await a.request(url,{method:"PUT",expected:409,body:{revision:1,minutes:corrected+" stale overwrite"}});
await a.request(url+"/confirm",{method:"POST",expected:409,body:{revision:1,requestId:randomUUID()}});
const confirmation={revision:draft.revision,requestId:randomUUID()};
draft=await a.request(url+"/confirm",{method:"POST",body:confirmation});
const reviewId=draft.reviews[0].id;
const [again,concurrent]=await Promise.all([a.request(url+"/confirm",{method:"POST",body:confirmation}),a.request(url+"/confirm",{method:"POST",body:confirmation})]);
assert.equal(again.reviews.length,1,"Repeated confirmation must reuse the same task");
assert.equal(again.reviews[0].id,reviewId);
assert.equal(concurrent.reviews[0].id,reviewId,"Concurrent confirmation must reuse the task");
await a.request(url,{method:"PUT",body:{revision:draft.revision,minutes:corrected+"\nLater edits are not part of this review."}});
draft=await waitFor(()=>a.request(url),d=>["COMPLETED","FAILED"].includes(d.reviews[0].status));
assert.equal(draft.reviews[0].status,"COMPLETED",JSON.stringify(draft.reviews[0]));
assert.equal(draft.reviews[0].minutes,corrected,"Confirmed snapshot is immutable after later edits");
assert.equal(draft.rawContent,raw);
const recapId=draft.reviews[0].recapId;
await b.request(`/api/interview-recaps/${recapId}`,{expected:404});
await b.request(`${url}/reviews/${reviewId}/retry`,{method:"POST",expected:404});
const report=await a.request(`/api/interview-recaps/${recapId}`);
assert.ok(report.questions.length>0);
assert.equal(report.jobOpportunityId,opp.id);
assert.equal(report.interviewRoundId,round.id,"Confirmed review inherits its source draft's exact round");
const source=await a.request(`/api/interview-recaps/drafts/from-report/${recapId}`,{method:"POST"});
assert.equal(source.draftId,submit.jobId,"A reviewed report must return to its original transcript");
const tasks=await a.request("/api/background-tasks?limit=100");
assert.ok(JSON.stringify(tasks).includes("RECAP_REVIEW"),"Detailed review is visible in background tasks");
await a.request(`/api/opportunities/${opp.id}/rounds/${round.id}`,{method:"DELETE"});
assert.equal((await a.request(`/api/interview-recaps/${recapId}`)).interviewRoundId,null,"Deleting a round detaches but preserves the report");
assert.equal((await a.request(url)).rawContent,raw,"Deleting a round preserves the transcript and minutes");
await a.request(`/api/opportunities/${opp.id}`,{method:"DELETE",expected:204});
assert.equal((await a.request(`/api/interview-recaps/${recapId}`)).jobOpportunityId,null,"Deleting a process preserves its detached report");
assert.equal((await a.request(url)).rawContent,raw,"Deleting a process preserves source material");
console.log("PASS: raw preservation, draft-first gate, owner isolation, optimistic editing, confirmation deduplication, immutable snapshots and background review");
