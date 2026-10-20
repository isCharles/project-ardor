import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base=process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const model=process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
class Client {
  cookies=new Map();
  async request(path,{method="GET",body,expected=200}={}) {
    const headers={};
    if (!["GET","HEAD"].includes(method)) {const csrf=await this.request("/api/auth/csrf");headers[csrf.headerName]=csrf.token;}
    headers.Cookie=[...this.cookies].map(([key,value])=>`${key}=${value}`).join("; ");
    if(body) headers["Content-Type"]="application/json";
    const response=await fetch(base+path,{method,headers,body:body?JSON.stringify(body):undefined});
    for(const line of response.headers.getSetCookie()){const pair=line.split(";")[0],at=pair.indexOf("=");this.cookies.set(pair.slice(0,at),pair.slice(at+1));}
    const text=await response.text();assert.equal(response.status,expected,`${method} ${path}: ${text.slice(0,200)}`);
    return text?JSON.parse(text):null;
  }
}
async function until(read,accept) {for(let i=0;i<160;i++){const value=await read();if(accept(value))return value;await new Promise(r=>setTimeout(r,250));}throw new Error("Context workflow timed out");}
const a=new Client(),b=new Client();
for(const client of [a,b]) await client.request("/api/auth/register",{method:"POST",expected:201,body:{email:`context-${randomUUID()}@ardor.invalid`,password:`Context-${randomUUID()}!`}});
await a.request("/api/settings/llm",{method:"PUT",body:{provider:"OPENAI_COMPATIBLE",baseUrl:model,model:"golden-path-model",apiKey:"fake-only"}});
const sourceInterview=await a.request("/api/opportunities",{method:"POST",expected:201,body:{company:"Synthetic source interview"}});
const submission=await a.request("/api/interview-recaps",{method:"POST",expected:202,body:{content:"Synthetic interview transcript. The interviewer asks about persistence and the candidate describes one option.",jobOpportunityId:sourceInterview.id}});
const draftPath=`/api/interview-recaps/drafts/${submission.jobId}`;
const draft=await until(()=>a.request(draftPath),value=>!!value.minutes);
await a.request(draftPath+"/confirm",{method:"POST",body:{revision:draft.revision,requestId:randomUUID()}});
const reviewed=await until(()=>a.request(draftPath),value=>value.reviews[0]?.status==="COMPLETED");
const report=await a.request(`/api/interview-recaps/${reviewed.reviews[0].recapId}`),question=report.questions[0];
const target=await a.request("/api/opportunities",{method:"POST",expected:201,body:{company:"Independent preparation target"}});
const targetWithRound=await a.request(`/api/opportunities/${target.id}/rounds`,{method:"POST",expected:201,body:{stage:"TECHNICAL"}});
const round=targetWithRound.rounds[0];
const input={concept:"Persistence trade-offs",requestId:randomUUID(),originKind:"INTERVIEW_QUESTION",originResourceId:question.id,targetInterviewId:target.id,targetRoundId:round.id};
const plan=await a.request("/api/learning-plans",{method:"POST",expected:201,body:input});
assert.equal(plan.sourceType,"RECAP");assert.equal(plan.sourceId,question.id);
assert.equal(plan.originKind,"INTERVIEW_QUESTION");assert.equal(plan.originRecordId,report.id);
assert.equal(plan.targetInterviewId,target.id);assert.equal(plan.targetRoundId,round.id);
assert.equal((await a.request("/api/learning-plans",{method:"POST",expected:201,body:input})).id,plan.id);
await a.request("/api/learning-plans",{method:"POST",expected:400,body:{...input,targetRoundId:null}});
await b.request("/api/learning-plans",{method:"POST",expected:404,body:{...input,requestId:randomUUID()}});
await a.request("/api/learning-plans",{method:"POST",expected:404,body:{...input,requestId:randomUUID(),targetInterviewId:sourceInterview.id}});
const queued=await a.request("/api/learning-plans/jobs",{method:"POST",expected:202,body:{...input,requestId:randomUUID()}});
const job=await until(()=>a.request(`/api/ai-jobs/${queued.id}`),value=>["COMPLETED","FAILED"].includes(value.status));
assert.equal(job.status,"COMPLETED",JSON.stringify(job));
const createdId=new URL(job.resultPath,"http://fixture.invalid").searchParams.get("id");
const queuedPlan=await a.request(`/api/learning-plans/${createdId}`);
assert.equal(queuedPlan.originResourceId,question.id);assert.equal(queuedPlan.targetRoundId,round.id);
const legacy=await a.request("/api/learning-plans",{method:"POST",expected:201,body:{concept:"Legacy record source",requestId:randomUUID(),sourceType:"RECAP",sourceId:report.id}});
assert.equal(legacy.originKind,"INTERVIEW_RECORD");assert.equal(legacy.originRecordId,report.id);
assert.equal(legacy.targetInterviewId,null,"Source interview is not an inferred target");
const manual=await a.request("/api/learning-plans",{method:"POST",expected:201,body:{concept:"Independent learning",requestId:randomUUID(),sourceType:"MANUAL"}});
assert.equal(manual.originKind,"MANUAL");assert.equal(manual.targetInterviewId,null);
await a.request(`/api/opportunities/${target.id}`,{method:"DELETE",expected:204});
assert.equal((await a.request(`/api/learning-plans/${plan.id}`)).targetInterviewId,null);
await a.request(`/api/interview-recaps/${report.id}`,{method:"DELETE",expected:204});
const retained=await a.request(`/api/learning-plans/${plan.id}`);
assert.equal(retained.originKind,"INTERVIEW_QUESTION");assert.equal(retained.originResourceId,question.id);
console.log("PASS: explicit persisted origins, independent targets, owner/parent isolation, sync/async context, idempotency and deletion preservation");
