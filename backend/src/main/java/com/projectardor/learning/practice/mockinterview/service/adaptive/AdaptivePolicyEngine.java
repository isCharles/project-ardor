package com.projectardor.learning.practice.mockinterview.service.adaptive;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.projectardor.learning.practice.mockinterview.domain.AnswerKind;
import com.projectardor.learning.practice.mockinterview.domain.DifficultyFeedback;
import com.projectardor.learning.practice.mockinterview.domain.PolicyAction;
import com.projectardor.learning.practice.mockinterview.domain.QuestionOrigin;
import com.projectardor.learning.practice.mockinterview.domain.QuestionTarget;

/**
 * Decides what to ask next, and why, without calling a model. Every rule here is a row of the
 * adaptive policy: pass raises one level, partial holds, failure lowers one level, an uncertain
 * reading is re-asked instead of counted against the candidate, and repeated inability moves to
 * another competency rather than drilling the same wound.
 *
 * <p>The engine reads the whole turn history so it can be replayed from persisted decisions after
 * an interruption and produce the same next question.
 */
@Component
public class AdaptivePolicyEngine {

    /** Below this the reading is too shaky to change the candidate's estimated level. */
    public static final BigDecimal LOW_CONFIDENCE = new BigDecimal("0.60");
    public static final int MAX_CONSECUTIVE_FOLLOW_UPS = 2;
    public static final int MAX_TURNS_PER_POINT = 3;
    public static final int MAX_CLARIFICATIONS_PER_POINT = 2;
    public static final int MAX_ALTERNATES_PER_POINT = 1;
    public static final int REPEATED_INABILITY = 2;
    public static final int MAX_TURNS_PER_COMPETENCY = 4;
    public static final int MAX_QUESTIONS = 30;

    public record Context(List<InterviewPlanner.CompetencyTarget> competencies, List<EvaluatedTurn> history,
            boolean deepDive, Integer durationMinutes, Instant startedAt, Instant now) {
        public Context {
            if (competencies == null || competencies.isEmpty()) {
                throw new IllegalArgumentException("能力规划不能为空");
            }
            competencies = List.copyOf(competencies);
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    /** A null target means the interview is over; the reason is always recorded either way. */
    public record Decision(PolicyAction action, QuestionTarget target, String reason) {}

    public Decision decide(Context context) {
        if (context.history().isEmpty()) return open(context);
        EvaluatedTurn last = context.history().get(context.history().size() - 1);

        if (outOfTime(context)) {
            return terminate("已达面试时长 " + context.durationMinutes() + " 分钟，剩余维度留待下次");
        }
        if (context.history().size() >= MAX_QUESTIONS) {
            return terminate("已达单场题目上限 " + MAX_QUESTIONS + " 题");
        }
        // Rotating knowledge points can otherwise keep one competency busy past its budget, most
        // easily in deep-dive mode where the consecutive-follow-up limit does not apply.
        if (turnCount(context.history(), last.competency()) >= MAX_TURNS_PER_COMPETENCY) {
            return switchCompetency(context, last, "「" + last.competency() + "」已问 "
                    + MAX_TURNS_PER_COMPETENCY + " 题，达到单维度上限，转向其他维度");
        }
        if (last.kind() == AnswerKind.CLARIFICATION) return clarify(context, last);
        if (last.kind() == AnswerKind.OFF_TOPIC) {
            return alternate(context, last, "回答未针对题目，换个角度重问，不计入能力判断");
        }
        if (isUncertain(last)) {
            return alternate(context, last, "评分置信度 " + last.confidence() + " 低于 " + LOW_CONFIDENCE
                    + "，换角度重问，不据此下调能力");
        }
        if (turnsOnPoint(context.history(), last.knowledgePoint()) >= MAX_TURNS_PER_POINT) {
            return switchCompetency(context, last, "同一知识点已问 " + MAX_TURNS_PER_POINT + " 次，不再追问，转向其他维度");
        }
        if (!context.deepDive() && consecutiveFollowUps(context.history()) >= MAX_CONSECUTIVE_FOLLOW_UPS) {
            return switchCompetency(context, last, "同一维度已连续追问 " + MAX_CONSECUTIVE_FOLLOW_UPS + " 次，转向其他维度");
        }
        if (last.isInability() && inabilityCount(context.history(), last.competency()) >= REPEATED_INABILITY) {
            return switchCompetency(context, last, "同一维度连续 " + REPEATED_INABILITY + " 次未答出，记录边界后转向其他维度");
        }
        return followUp(context, last);
    }

    private Decision open(Context context) {
        InterviewPlanner.CompetencyTarget first = context.competencies().get(0);
        return new Decision(PolicyAction.OPEN,
                target(first, first.knowledgePoints().get(0), first.initialDifficulty(), QuestionOrigin.PLAN),
                "从 JD 优先级最高的维度「" + first.skill() + "」开始，初始难度 " + first.initialDifficulty());
    }

    private Decision followUp(Context context, EvaluatedTurn last) {
        InterviewPlanner.CompetencyTarget competency = competency(context, last.competency());
        int requested;
        String reason;
        if (last.isPass()) {
            requested = last.difficulty() + 1;
            reason = "本题得 " + last.score() + " 分，上调难度并转到更深的知识点";
        } else if (last.score() == 2) {
            requested = last.difficulty();
            reason = "本题得 2 分，方向正确但不完整，保持难度追问缺失部分："
                    + String.join("、", last.missingPoints());
        } else {
            requested = last.difficulty() - 1;
            reason = "本题得 " + last.score() + " 分，下调难度核实基础；一次答错不代表没有能力";
        }
        if (last.feedback() == DifficultyFeedback.TOO_HARD) {
            requested = last.difficulty() - 1;
            reason += "；候选人反馈偏难，改降一级";
        } else if (last.feedback() == DifficultyFeedback.TOO_EASY) {
            requested = last.difficulty() + 1;
            reason += "；候选人反馈偏易，改升一级";
        }
        int difficulty = step(last.difficulty(), requested);
        if (difficulty != requested) {
            reason += difficulty == 5 ? "；已达难度上限 5，保持本级换更深的知识点"
                    : "；已达难度下限 1，保持本级继续核实基础";
        }
        // The logged action describes the move that actually happened, including a feedback override.
        PolicyAction action = difficulty > last.difficulty() ? PolicyAction.RAISE
                : difficulty < last.difficulty() ? PolicyAction.LOWER : PolicyAction.HOLD;
        boolean deeper = last.isPass() && difficulty >= last.difficulty();
        // The current point still has budget here, so it is the fallback when every other point
        // in this competency has been asked out.
        String point = deeper
                ? eligiblePoint(context.history(), competency, last.knowledgePoint()).orElse(last.knowledgePoint())
                : last.knowledgePoint();
        if (deeper && !point.equals(last.knowledgePoint())) reason += "「" + point + "」";
        return new Decision(action,
                new QuestionTarget(competency.skill(), point, difficulty, competency.questionType(),
                        QuestionOrigin.FOLLOW_UP, last.questionId()),
                reason);
    }

    private Decision clarify(Context context, EvaluatedTurn last) {
        long asked = context.history().stream()
                .filter(turn -> turn.knowledgePoint().equals(last.knowledgePoint()))
                .filter(turn -> turn.kind() == AnswerKind.CLARIFICATION)
                .count();
        if (asked >= MAX_CLARIFICATIONS_PER_POINT) {
            return switchCompetency(context, last, "同一知识点已澄清 " + asked + " 次仍无法作答，转向其他维度");
        }
        return new Decision(PolicyAction.CLARIFY,
                new QuestionTarget(last.competency(), last.knowledgePoint(), last.difficulty(),
                        competency(context, last.competency()).questionType(),
                        QuestionOrigin.CLARIFICATION, last.questionId()),
                "候选人反问题目本身，先澄清再重问，本次不计分也不计入能力");
    }

    private Decision alternate(Context context, EvaluatedTurn last, String reason) {
        long alternates = context.history().stream()
                .filter(turn -> turn.knowledgePoint().equals(last.knowledgePoint()))
                .filter(turn -> turn.origin() == QuestionOrigin.ALTERNATE)
                .count();
        if (alternates >= MAX_ALTERNATES_PER_POINT) {
            return switchCompetency(context, last, reason + "；已换角度重问过，该维度保持未验证");
        }
        return new Decision(PolicyAction.CLARIFY,
                new QuestionTarget(last.competency(), last.knowledgePoint(), last.difficulty(),
                        competency(context, last.competency()).questionType(),
                        QuestionOrigin.ALTERNATE, last.questionId()),
                reason);
    }

    private Decision switchCompetency(Context context, EvaluatedTurn last, String reason) {
        Optional<InterviewPlanner.CompetencyTarget> next = context.competencies().stream()
                .filter(competency -> !competency.skill().equals(last.competency()))
                .filter(competency -> turnCount(context.history(), competency.skill()) < MAX_TURNS_PER_COMPETENCY)
                .filter(competency -> eligiblePoint(context.history(), competency, null).isPresent())
                .min(Comparator.comparingLong((InterviewPlanner.CompetencyTarget competency) ->
                        turnCount(context.history(), competency.skill())));
        if (next.isEmpty()) return terminate(reason + "；所有维度都已问到上限，结束本场");
        InterviewPlanner.CompetencyTarget competency = next.get();
        // Returning to a competency must not reopen a point that already used up its turn budget.
        String point = eligiblePoint(context.history(), competency, null).orElseThrow();
        // A new competency restarts its own scale; this is not a jump inside the previous one.
        return new Decision(PolicyAction.SWITCH,
                target(competency, point, competency.initialDifficulty(), QuestionOrigin.PLAN),
                reason + "；转到「" + competency.skill() + "」，按其初始难度 " + competency.initialDifficulty() + " 重新开始");
    }

    private static Decision terminate(String reason) {
        return new Decision(PolicyAction.TERMINATE, null, reason);
    }

    private static boolean outOfTime(Context context) {
        if (context.durationMinutes() == null || context.startedAt() == null || context.now() == null) return false;
        return Duration.between(context.startedAt(), context.now()).toMinutes() >= context.durationMinutes();
    }

    public static boolean isUncertain(EvaluatedTurn turn) {
        return turn.confidence() != null && turn.confidence().compareTo(LOW_CONFIDENCE) < 0;
    }

    /** Trailing questions that were not the opening of their competency. */
    private static int consecutiveFollowUps(List<EvaluatedTurn> history) {
        int count = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            if (history.get(index).origin() == QuestionOrigin.PLAN) break;
            count++;
        }
        return count;
    }

    private static long inabilityCount(List<EvaluatedTurn> history, String competency) {
        return history.stream()
                .filter(turn -> turn.competency().equals(competency))
                .filter(turn -> !isUncertain(turn))
                .filter(EvaluatedTurn::isInability)
                .count();
    }

    private static long turnCount(List<EvaluatedTurn> history, String competency) {
        return history.stream().filter(turn -> turn.competency().equals(competency)).count();
    }

    private static long turnsOnPoint(List<EvaluatedTurn> history, String knowledgePoint) {
        return history.stream().filter(turn -> turn.knowledgePoint().equals(knowledgePoint)).count();
    }

    private static InterviewPlanner.CompetencyTarget competency(Context context, String skill) {
        return context.competencies().stream()
                .filter(competency -> competency.skill().equals(skill))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("回合不属于本场能力规划：" + skill));
    }

    /** Moves one level at a time and never leaves the scale, so a jump always has a stated cause. */
    private static int step(int from, int requested) {
        int bounded = Math.max(from - 1, Math.min(from + 1, requested));
        return Math.max(1, Math.min(5, bounded));
    }

    /** Rotates from {@code after}, skipping points that already used up their turn budget. */
    private static Optional<String> eligiblePoint(List<EvaluatedTurn> history,
            InterviewPlanner.CompetencyTarget competency, String after) {
        List<String> points = competency.knowledgePoints();
        int start = after == null ? 0 : (points.indexOf(after) + 1) % points.size();
        for (int offset = 0; offset < points.size(); offset++) {
            String point = points.get((start + offset) % points.size());
            if (turnsOnPoint(history, point) < MAX_TURNS_PER_POINT) return Optional.of(point);
        }
        return Optional.empty();
    }

    private static QuestionTarget target(InterviewPlanner.CompetencyTarget competency, String point,
            int difficulty, QuestionOrigin origin) {
        return new QuestionTarget(competency.skill(), point, difficulty, competency.questionType(), origin, null);
    }
}