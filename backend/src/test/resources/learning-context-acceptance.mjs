import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { syntheticResumePdf } from "./acceptance-fixtures.mjs";
if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base=process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const model=process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
class Client {
  cookies=new Map();
  async request(path,{method="GET",body,expected=200}={}) {
    const headers={};
    if (!["GET","HEAD"].includes(method)) {const csrf=await this.request("/api/auth/csrf");headers[csrf.headerName]=csrf.token;}
    headers.Cookie=[...this.cookies].map(([key,value])=>`${key}=${value}`).join("; ");
    if(body && !(body instanceof FormData)) headers["Content-Type"]="application/json";
    const response=await fetch(base+path,{method,headers,body:body instanceof FormData?body:body?JSON.stringify(body):undefined});
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
const sourceRoundOne=await a.request(`/api/opportunities/${sourceInterview.id}/rounds`,{method:"POST",expected:201,body:{stage:"TECHNICAL"}});
await a.request(`/api/opportunities/${sourceInterview.id}/rounds`,{method:"POST",expected:201,body:{stage:"MANAGER"}});
const sourceRoundId=sourceRoundOne.rounds[0].id;
const submission=await a.request("/api/interview-recaps",{method:"POST",expected:202,body:{content:"Synthetic interview transcript. The interviewer asks about persistence and the candidate describes one option.",jobOpportunityId:sourceInterview.id,interviewRoundId:sourceRoundId}});
const draftPath=`/api/interview-recaps/drafts/${submission.jobId}`;
const draft=await until(()=>a.request(draftPath),value=>!!value.minutes);
await a.request(draftPath+"/confirm",{method:"POST",body:{revision:draft.revision,requestId:randomUUID()}});
const reviewed=await until(()=>a.request(draftPath),value=>value.reviews[0]?.status==="COMPLETED");
const report=await a.request(`/api/interview-recaps/${reviewed.reviews[0].recapId}`),question=report.questions[0];
const sourceReference=await a.request(`/api/learning-plans/origins/INTERVIEW_QUESTION/${question.id}`);
assert.equal(sourceReference.recordId,report.id);
await b.request(`/api/learning-plans/origins/INTERVIEW_QUESTION/${question.id}`,{expected:404});
await a.request(`/api/learning-plans/origins/MANUAL/${question.id}`,{expected:400});
const target=await a.request("/api/opportunities",{method:"POST",expected:201,body:{company:"Independent preparation target"}});
const targetWithRound=await a.request(`/api/opportunities/${target.id}/rounds`,{method:"POST",expected:201,body:{stage:"TECHNICAL"}});
const round=targetWithRound.rounds[0];
const input={concept:"Persistence trade-offs",requestId:randomUUID(),originKind:"INTERVIEW_QUESTION",originResourceId:question.id,targetInterviewId:target.id,targetRoundId:round.id};
for(const sourceType of [undefined,"MANUAL","AGENT"]) {
  await a.request("/api/learning-plans",{method:"POST",expected:400,body:{concept:"Invalid autonomous source",requestId:randomUUID(),sourceType,originResourceId:question.id}});
}
const plan=await a.request("/api/learning-plans",{method:"POST",expected:201,body:input});
assert.equal(plan.sourceType,"RECAP");assert.equal(plan.sourceId,question.id);
assert.equal(plan.originKind,"INTERVIEW_QUESTION");assert.equal(plan.originRecordId,report.id);
assert.equal(plan.targetInterviewId,target.id);assert.equal(plan.targetRoundId,round.id);
assert.equal((await a.request("/api/learning-plans",{method:"POST",expected:201,body:input})).id,plan.id);
await a.request("/api/learning-plans",{method:"POST",expected:400,body:{...input,sourceType:"RESUME",sourceId:question.id}});
await a.request("/api/learning-plans",{method:"POST",expected:400,body:{...input,sourceType:"RECAP",sourceId:randomUUID()}});
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

// Real domain-provider integration: owned uploads, reference resolution, creation,
// and rejection of both reference lookup and creation by another user.
const resumeUpload=new FormData();resumeUpload.set("file",new Blob([syntheticResumePdf()],{type:"application/pdf"}),"source-fixture.pdf");
const resume=await a.request("/api/resumes",{method:"POST",expected:201,body:resumeUpload});
const knowledgeUpload=new FormData();knowledgeUpload.set("file",new Blob(["# Recovery fixture\nCompare recovery time and acceptable data loss before choosing persistence."],{type:"text/plain"}),"source-fixture.txt");
const document=await a.request("/api/knowledge/documents",{method:"POST",expected:201,body:knowledgeUpload});
for(const [kind,material] of [["RESUME",resume],["KNOWLEDGE",document]]) {
  const referencePath=`/api/learning-plans/origins/${kind}/${material.id}`;
  const reference=await a.request(referencePath);assert.equal(reference.resourceId,material.id);assert.equal(reference.recordId,null);
  await b.request(referencePath,{expected:404});
  const sourceInput={concept:`Learning from ${kind}`,requestId:randomUUID(),originKind:kind,originResourceId:material.id};
  const sourced=await a.request("/api/learning-plans",{method:"POST",expected:201,body:sourceInput});
  assert.equal(sourced.originKind,kind);assert.equal(sourced.originResourceId,material.id);assert.equal(sourced.targetInterviewId,null);
  await b.request("/api/learning-plans",{method:"POST",expected:404,body:{...sourceInput,requestId:randomUUID()}});
}

// Attempt history: one graded round keeps its own exercise snapshot, answers, score, evaluation and model.
const attemptsPath=`/api/learning-plans/${manual.id}/attempts`;
assert.equal(manual.contentVersion,1,"A generated lesson starts at content version 1");
assert.equal(manual.exercisesVersion,1,"A generated exercise set starts at version 1");
assert.ok(manual.exercises.every(item=>typeof item.exerciseId==="string"&&item.exerciseId.length>0),
  "Every generated exercise carries an exerciseId");
const generatedExerciseIds=manual.exercises.map(item=>item.exerciseId);
assert.deepEqual((await a.request(`/api/learning-plans/${manual.id}`)).exercises.map(item=>item.exerciseId),
  generatedExerciseIds,"Re-reading a plan must keep the same exercise ids");
await b.request(attemptsPath,{expected:404});
await a.request(attemptsPath,{method:"POST",expected:409,body:{answers:manual.exercises.map(()=>"stale answer"),expectedAttemptCount:7}});
await a.request(attemptsPath,{method:"POST",expected:409,body:{answers:manual.exercises.map(()=>"stale answer"),exercisesVersion:7}});
assert.equal((await a.request(attemptsPath)).length,0,"Stale fences must reject before grading");
const attemptAnswers=manual.exercises.map(item=>item.type==="MULTIPLE_CHOICE"?"记录写命令":"先明确恢复目标，再比较 RDB 与 AOF。");
const graded=await a.request(attemptsPath,{method:"POST",body:{answers:attemptAnswers,expectedAttemptCount:0,exercisesVersion:1}});
assert.equal(graded.attemptCount,1);assert.equal(graded.status,"NEEDS_REVIEW");
assert.equal(graded.exercisesVersion,2,"A weak round replaces the exercise set with a new version");
const replacedExerciseIds=graded.exercises.map(item=>item.exerciseId);
assert.ok(replacedExerciseIds.every(id=>typeof id==="string"&&id.length>0),"Replaced exercises keep stable ids");
assert.ok(replacedExerciseIds.every(id=>!generatedExerciseIds.includes(id)),
  "A replaced exercise set must not reuse the previous ids");
const history=await a.request(attemptsPath);
assert.equal(history.length,1,"One graded round must become one attempt row");
const attempt=history[0];
assert.equal(attempt.attemptNo,1);assert.equal(attempt.legacy,false);
assert.equal(attempt.modelName,"golden-path-model");assert.equal(attempt.promptVersion,"learning-evaluation-v2");
assert.deepEqual(attempt.answers,attemptAnswers);
assert.deepEqual(attempt.exercises.map(item=>item.exerciseId),generatedExerciseIds,
  "The attempt snapshot must keep the ids of the exercise set that was actually answered");
assert.deepEqual(graded.lastEvaluation.attempts[0].items.map(item=>item.exerciseId),generatedExerciseIds,
  "The old-client attempt projection must also retain answered exercise IDs");
assert.deepEqual(attempt.evaluation.questionFeedback.map(item=>item.exerciseId),[...generatedExerciseIds].reverse(),
  "Paraphrased/reordered model feedback retains stable IDs through the real scoring pipeline");
assert.deepEqual(graded.lastEvaluation.attempts[0].questionFeedback,attempt.evaluation.questionFeedback);
assert.deepEqual(graded.lastEvaluation.attempts[0].gaps,attempt.evaluation.gaps);
assert.equal(attempt.score,65);
assert.ok(Array.isArray(attempt.exercises[0].rubric)&&attempt.exercises[0].rubric.length>0,"Attempt snapshot keeps the rubric");
assert.equal(attempt.evaluation.nextFocus,"恢复时间与数据丢失窗口");
assert.equal(attempt.evaluation.choices[0].correct,true,"Stored choice grading matches the selected option");
await a.request(attemptsPath,{method:"POST",expected:409,body:{answers:attemptAnswers,expectedAttemptCount:1,exercisesVersion:1}});
assert.equal((await a.request(`/api/learning-plans/${manual.id}`)).attemptCount,1,"A stale retry must not grade twice");

// Real multi-round source -> learning, re-answer, self-rated review and server-owned progress.
const card=await a.request("/api/memory-cards",{method:"POST",expected:201,body:{sourceType:"INTERVIEW",recapQuestionId:question.id,front:"Synthetic persistence question",back:"PRIVATE_SYNTHETIC_CARD_BACK",tags:["Fixture"]}});
await a.request(`/api/memory-cards/${card.id}/reviews`,{method:"POST",body:{rating:"GOOD"}});
await a.request(`/api/interview-replays/questions/${question.id}`,{method:"POST",expected:201,body:{requestId:randomUUID(),answer:"AOF records writes; compare recovery latency and loss windows before choosing persistence."}});
const progress=await a.request(`/api/learning-progress?sourceInterviewId=${sourceInterview.id}&size=1`);
assert.equal(progress.totalQuestions,1,"Only the weak fixture question belongs in the progress track");
assert.equal(progress.hasNext,false);assert.equal(progress.tracks.length,1);
const track=progress.tracks[0];assert.equal(track.question.id,question.id);assert.equal(track.question.roundId,sourceRoundId);
assert.equal(track.learningPlans.length,2,"Retain both sync and queued plans instead of taking the first");
assert.equal(track.interviewLearningPlans.length,1,"Interview-wide provenance is distinct from exact question learning");
assert.ok(track.learningPlans.every(item=>item.targetInterviewId===target.id),"Preparation target does not overwrite source interview");
assert.equal(track.cards[0].id,card.id);assert.equal(track.cards[0].repetitions,1);
assert.equal(track.replay.attemptCount,1);assert.ok(track.replay.latestAt);assert.ok(track.replay.latestVerdict);
assert.ok(!("answerText" in track.replay)&&!("back" in track.cards[0])&&!("lesson" in track.learningPlans[0]),"Progress must not transport full private practice payloads");
assert.ok(progress.accountSummary.learningPlans>=6);assert.equal(progress.accountSummary.recordedLearningAttempts,1);
const otherSource=await a.request(`/api/learning-progress?sourceInterviewId=${target.id}`);
assert.equal(otherSource.tracks.length,0,"Preparing for B does not fabricate a source question from B");
assert.equal(otherSource.accountSummary.learningPlans,progress.accountSummary.learningPlans,"Autonomous/account learning remains visible with no source interview questions");
const foreignProgress=await b.request("/api/learning-progress");
assert.equal(foreignProgress.tracks.length,0);
assert.ok(Object.values(foreignProgress.accountSummary).every(count=>count===0),"Account totals must not leak another user's activity");
await new Client().request("/api/learning-progress",{expected:401});
await b.request(`/api/learning-progress?sourceInterviewId=${sourceInterview.id}`,{expected:404});
await a.request("/api/learning-progress?size=51",{expected:400});await a.request("/api/learning-progress?page=-1",{expected:400});
await a.request(`/api/opportunities/${target.id}`,{method:"DELETE",expected:204});
assert.equal((await a.request(`/api/learning-plans/${plan.id}`)).targetInterviewId,null);
await a.request(`/api/interview-recaps/${report.id}`,{method:"DELETE",expected:204});
const retained=await a.request(`/api/learning-plans/${plan.id}`);
assert.equal(retained.originKind,"INTERVIEW_QUESTION");assert.equal(retained.originResourceId,question.id);
assert.equal((await a.request("/api/learning-plans",{method:"POST",expected:201,body:input})).id,plan.id,"Valid retry does not require a deleted source to exist");
// Learning owns the existing card/review tables. Moving Java ownership must not
// reset state, broaden deletion, or make independent cards depend on a record.
let ownedCards=await a.request("/api/memory-cards");
let detached=ownedCards.find(item=>item.id===card.id);
assert.ok(detached,"Deleting the source report preserves its standalone card");
assert.equal(detached.recapQuestionId,null);
assert.equal(detached.repetitions,1);
const updatedCard=await a.request("/api/memory-cards/"+card.id,{method:"PUT",body:{front:"Edited synthetic prompt",back:"Edited synthetic answer",tags:["edited"]}});
assert.equal(updatedCard.repetitions,1);assert.equal(updatedCard.intervalDays,detached.intervalDays);
assert.equal(updatedCard.nextReviewAt,detached.nextReviewAt);
await b.request("/api/memory-cards/"+card.id,{method:"PUT",expected:404,body:{front:"Foreign edit",back:"Foreign edit",tags:[]}});
const suspended=await a.request("/api/memory-cards/"+card.id+"/suspend",{method:"POST"});
assert.equal(suspended.status,"SUSPENDED");
await a.request("/api/memory-cards/"+card.id+"/reviews",{method:"POST",expected:409,body:{rating:"GOOD"}});
await a.request("/api/memory-cards/"+card.id+"/suspend?value=false",{method:"POST"});
const newer=await a.request("/api/memory-cards",{method:"POST",expected:201,body:{sourceType:"AGENT",front:"Independent later card",back:"Independent answer",tags:[]}});
const foreignCard=await b.request("/api/memory-cards",{method:"POST",expected:201,body:{sourceType:"AGENT",front:"Another owner card",back:"Another owner answer",tags:[]}});
await a.request("/api/memory-cards/snapshot",{method:"DELETE",expected:404,body:{ids:[card.id,foreignCard.id]}});
assert.ok((await a.request("/api/memory-cards")).some(item=>item.id===card.id),"Mixed-owner snapshot is rejected before deleting any owned card");
await a.request("/api/memory-cards/snapshot",{method:"DELETE",expected:204,body:{ids:[card.id,card.id]}});
ownedCards=await a.request("/api/memory-cards");
assert.ok(!ownedCards.some(item=>item.id===card.id));
assert.ok(ownedCards.some(item=>item.id===newer.id),"A snapshot cannot delete a card created outside that snapshot");
assert.ok((await b.request("/api/memory-cards")).some(item=>item.id===foreignCard.id));
console.log("PASS: explicit origins, independent targets, owned learning/review progress, card lifecycle and snapshot isolation, retained history, idempotency and deletion preservation");
