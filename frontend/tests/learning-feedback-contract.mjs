import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

const source = await readFile(new URL("../src/lib/learning-feedback.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } });
const { associateLearningFeedback: match } = await import(`data:text/javascript;base64,${Buffer.from(compiled.outputText).toString("base64")}`);
const items = Array.from({ length: 6 }, (_, i) => ({ question: `Synthetic question ${i + 1}`, answer: "Synthetic answer" }));
const legacy = items.map((item, index) => ({ question: [0, 3, 4].includes(index) ? `Paraphrased ${index + 1}` : item.question, feedback: `Comment ${index + 1}` }));
const result = match(items, legacy);
assert.deepEqual(result.perQuestion.map(rows => rows.length), [0, 1, 1, 0, 0, 1]);
assert.deepEqual(result.unmatched.map(row => row.feedback), ["Comment 1", "Comment 4", "Comment 5"]);
assert.equal(result.perQuestion.flat().length + result.unmatched.length, 6, "No comment may disappear");
const identified = items.map((item, i) => ({ ...item, exerciseId: `q-${i}` }));
const shuffled = identified.map((item, i) => ({ exerciseId: item.exerciseId, question: "Model paraphrase", feedback: `Comment ${i + 1}` })).reverse();
assert.deepEqual(match(identified, shuffled).perQuestion.map(rows => rows[0].feedback), legacy.map(row => row.feedback));
assert.equal(match(identified, shuffled).unmatched.length, 0);
assert.equal(match(identified, [{ exerciseId: "q-0", feedback: "Comment", referenceAnswer: "Independent reference answer" }]).perQuestion[0][0].referenceAnswer,
  "Independent reference answer", "Reference answers follow question identity, not returned array order");
assert.equal(match(identified, [{ exerciseId: "unknown", question: items[0].question, feedback: "Wrong ID" }]).unmatched.length, 1,
  "An explicit wrong ID cannot fall back to text");
assert.equal(match([{ ...items[0], exerciseId: "same" }, { ...items[1], exerciseId: "same" }], [{ exerciseId: "same", feedback: "Ambiguous" }]).unmatched.length, 1);
assert.equal(match([items[0], items[0]], [{ question: items[0].question, feedback: "Ambiguous text" }]).unmatched.length, 1);
assert.equal(match([{ question: "C++", answer: "" }], [{ question: "C#", feedback: "Different language" }]).unmatched.length, 1,
  "Punctuation cannot be stripped to force a semantic match");
assert.equal(match(items, [{ question: "  Synthetic question 1  ", feedback: "Trim only" }]).perQuestion[0].length, 1);
assert.equal(match(items).perQuestion.flat().length, 0);
assert.deepEqual(match([], legacy).unmatched, legacy);
assert.equal(match(items, null).unmatched.length, 0);
assert.deepEqual(match(items, [null, { feedback: "Unkeyed comment" }, "Legacy text comment"]).unmatched.map(row => row.feedback), ["Unkeyed comment", "Legacy text comment"]);
assert.deepEqual(legacy.map(row => row.feedback), Array.from({ length: 6 }, (_, i) => `Comment ${i + 1}`), "Input must remain unchanged");
console.log("Learning feedback contract: stable IDs, reordered/paraphrased feedback, all six legacy comments retained, ambiguity and wrong-ID refusal passed.");
