package com.ironbro.interviewhub.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.agent.application.BusinessAgentResolver;
import com.ironbro.interviewhub.agent.application.BusinessAgentScene;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.interview.api.io.req.InterviewAnswerReqDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewAnswerRespDTO;
import com.ironbro.interviewhub.interview.shared.InterviewResponseParser;
import com.ironbro.interviewhub.interview.application.flow.InterviewFlowStateMachine;
import com.ironbro.interviewhub.interview.application.guard.core.InterviewAiGuardException;
import com.ironbro.interviewhub.interview.application.runtime.InterviewRuntimeRehydrateScope;
import com.ironbro.interviewhub.interview.application.runtime.InterviewSessionRuntimeRehydrateService;
import com.ironbro.interviewhub.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.ironbro.interviewhub.interview.application.runtime.InterviewSessionRuntimeView;
import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleDecision;
import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleService;
import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewAction;
import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewContext;
import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewDecision;
import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewMergeResult;
import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewMergeService;
import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewRuleService;
import com.ironbro.interviewhub.interview.service.InterviewQuestionCacheService;
import com.ironbro.interviewhub.interview.service.model.InterviewFlowState;
import com.ironbro.interviewhub.interview.service.model.InterviewRuntimeLoadMode;
import com.ironbro.interviewhub.interview.service.model.InterviewTurnLog;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 【核心】答题流水线：用户提交答案后的完整处理链路。
 * 8 步流水线：①参数校验 → ②requestId归一化 → ③幂等门禁 → ④加载当前题+flow → ⑤题号级加锁 → ⑥二次校验题号 → ⑦AI评分 → ⑧推进flow+提交分数
 * 幂等两阶段设计(processing防并发+replay回放)、题号级锁(非会话级)、追问不计入总分、分数提交失败回滚flow、追问规则引擎二次决策
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewAnswerPipeline {

    private static final String PROCESSING_MESSAGE = "current request is processing, please retry later";
    private static final String QUESTION_LOCK_MESSAGE = "current question is processing, please retry later";
    private static final String STALE_QUESTION_MESSAGE = "stale question number, please refresh current question";

    private final BusinessAgentResolver businessAgentResolver;              // 解析评分 Agent 配置
    private final InterviewQuestionCacheService interviewQuestionCacheService; // Redis 缓存操作
    private final InterviewEvaluationService interviewEvaluationService;    // AI 评分（调大模型）
    private final InterviewFollowUpService interviewFollowUpService;        // AI 生成追问
    private final InterviewResponseParser interviewResponseParser;          // AI 响应解析
    private final InterviewFlowStateMachine interviewFlowStateMachine;      // 流程状态机（Redis Hash + Lua CAS）
    private final InterviewAnswerIdempotencyService interviewAnswerIdempotencyService; // 两阶段幂等控制
    private final InterviewQuestionLockService interviewQuestionLockService; // 题号级分布式锁
    private final InterviewFollowUpRuleService interviewFollowUpRuleService; // 追问规则引擎（二次决策）
    private final InterviewSecondReviewRuleService interviewSecondReviewRuleService;
    private final InterviewSecondReviewMergeService interviewSecondReviewMergeService;
    private final InterviewTurnRepairService interviewTurnRepairService;    // 轮次写入失败的修复队列
    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService; // MongoDB 热快照刷新
    private final InterviewSessionRuntimeRehydrateService runtimeRehydrateService; // 缓存恢复（Redis 丢失时从 MongoDB 回补）

    public InterviewAnswerRespDTO execute(String sessionId, InterviewAnswerReqDTO requestParam) {
        InterviewAnswerPipelineContext ctx = new InterviewAnswerPipelineContext();
        ctx.sessionId = sessionId;
        ctx.requestParam = requestParam;
        ctx.response = InterviewAnswerRespDTO.init();

        try {
            // 1) 基础参数校验。
            if (!validateRequest(ctx)) {
                return ctx.response;
            }
            // 2) 归一化 requestId，保证幂等键稳定。
            normalizeRequestId(ctx);
            // 3) 幂等门禁：命中已成功请求直接回放，处理中请求快速失败。
            if (!stepIdempotency(ctx)) {
                return ctx.response;
            }
            // 4) 读取当前题与 flow，拒绝过期题号。
            if (!stepLoadCurrentQuestion(ctx)) {
                return finishAndReturn(ctx, false);
            }
            // 5) 以“当前题号”为粒度加锁，串行化同题并发提交。
            if (!stepAcquireQuestionLock(ctx)) {
                return ctx.response;
            }
            // 6) 加锁后再次校验题号，避免锁前后游标漂移导致串题。
            if (!stepValidateQuestionAfterLock(ctx)) {
                return ctx.response;
            }
            // 7) 调评分链路并提取结构化评分结果（此时仅计算，不入账）。
            if (!stepEvaluateAndScore(ctx)) {
                return ctx.response;
            }
            if (!stepSecondReview(ctx)) {
                return ctx.response;
            }
            // 8) 推进 flow、提交分数并组装下一题/结束态响应。
            if (!stepAdvanceFlowAndAssemble(ctx)) {
                return ctx.response;
            }
            return finishAndReturn(ctx, true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while executing interview answer pipeline, sessionId: {}", sessionId);
            recordAnswerPipelineFailure("interrupted");
            ctx.response.fail("interview answer request interrupted");
            return ctx.response;
        } catch (Exception ex) {
            log.error("Failed to execute interview answer pipeline, sessionId: {}", sessionId, ex);
            recordAnswerPipelineFailure("unexpected_exception");
            return ctx.response.fail("failed to process answer: " + ex.getMessage());
        } finally {
            interviewQuestionLockService.release(ctx.questionLock);
            if (ctx.idempotencyStarted && !ctx.idempotencyMarkedSucceeded) {
                interviewAnswerIdempotencyService.clearProcessing(ctx.sessionId, ctx.requestId);
            }
        }
    }

    /**
     * 收尾：成功时写轮次日志 → 标记幂等成功 → 刷新 MongoDB 热快照。
     * 轮次写入失败不阻塞主流程，放入修复队列异步重试。
     */
    private InterviewAnswerRespDTO finishAndReturn(InterviewAnswerPipelineContext ctx, boolean appendTurn) {
        if (Boolean.TRUE.equals(ctx.response.getIsSuccess())) {
            if (appendTurn && !stepAppendTurnLog(ctx)) {
                interviewTurnRepairService.enqueue(ctx.sessionId, ctx.turnLog, "append_failed_before_mark_succeeded");
            }
            interviewAnswerIdempotencyService.markSucceeded(ctx.sessionId, ctx.requestId, ctx.response);
            ctx.idempotencyMarkedSucceeded = true;
            if (ctx.turnLog != null) {
                runtimeSnapshotService.refreshAfterAnswerCommitted(ctx.sessionId, ctx.requestId, ctx.turnLog);
            }
        }
        return ctx.response;
    }

    private boolean validateRequest(InterviewAnswerPipelineContext ctx) {
        if (StrUtil.isBlank(ctx.sessionId)) {
            ctx.response.fail("sessionId cannot be empty");
            return false;
        }
        if (ctx.requestParam == null) {
            ctx.response.fail("request body cannot be empty");
            return false;
        }
        if (StrUtil.isBlank(ctx.requestParam.getQuestionNumber())) {
            ctx.response.fail("question number cannot be empty");
            return false;
        }
        if (StrUtil.isBlank(ctx.requestParam.getAnswerContent())) {
            ctx.response.fail("answer content cannot be empty");
            return false;
        }
        return true;
    }

    /** 归一化 requestId：前端没传则用 sessionId+题号+答案内容的 SHA256 自动生成，保证幂等键稳定 */
    private void normalizeRequestId(InterviewAnswerPipelineContext ctx) {
        String requestId = ctx.requestParam.getRequestId();
        if (StrUtil.isBlank(requestId)) {
            String seed = ctx.sessionId + "|" + ctx.requestParam.getQuestionNumber().trim() + "|" + ctx.requestParam.getAnswerContent();
            requestId = "auto-" + DigestUtil.sha256Hex(seed).substring(0, 32);
            ctx.requestParam.setRequestId(requestId);
        } else {
            requestId = requestId.trim();
            ctx.requestParam.setRequestId(requestId);
        }
        ctx.requestId = requestId;
    }

    /**
     * 两阶段幂等控制：
     * - SUCCEEDED：已有结果 → 直接回放缓存的响应，不重复调 AI
     * - PROCESSING：其他请求正在处理 → 快速失败，前端可重试
     * - NEW：首次请求 → 标记 processing，再查 MongoDB 热快照是否有软回放记录（防 Redis 丢失后重复调 AI）
     */
    private boolean stepIdempotency(InterviewAnswerPipelineContext ctx) {
        InterviewAnswerIdempotencyService.TryStartResult tryStartResult =
                interviewAnswerIdempotencyService.tryStart(ctx.sessionId, ctx.requestId);
        switch (tryStartResult.getStatus()) {
            case SUCCEEDED -> {
                InterviewAnswerRespDTO replayResponse = tryStartResult.getReplayResponse();
                if (replayResponse != null) {
                    Metrics.counter("idempotency_replay_hit_total").increment();
                    ctx.response = replayResponse;
                    return false;
                }
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
            case PROCESSING -> {
                // 其他请求正在处理，快速失败
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
            case NEW -> {
                // 首次请求，标记 processing
                ctx.idempotencyStarted = true;
                // 再查 MongoDB 热快照：如果 Redis 丢了但 MongoDB 还有记录，直接回放，不重复调 AI
                InterviewAnswerRespDTO replayResponse = runtimeSnapshotService.findReplayResponse(
                        ctx.sessionId,
                        ctx.requestId,
                        ctx.requestParam.getQuestionNumber(),
                        ctx.requestParam.getAnswerContent()
                );
                if (replayResponse != null) {
                    interviewAnswerIdempotencyService.markSucceeded(ctx.sessionId, ctx.requestId, replayResponse);
                    ctx.idempotencyMarkedSucceeded = true;
                    ctx.response = replayResponse;
                    return false;
                }
                return true;
            }
            default -> {
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
        }
    }

    private boolean stepAcquireQuestionLock(InterviewAnswerPipelineContext ctx) throws InterruptedException {
        RLock questionLock = interviewQuestionLockService.acquire(ctx.sessionId, ctx.currentQuestionNumber);
        if (questionLock == null) {
            Metrics.counter("question_lock_contention_total").increment();
            recordAnswerPipelineFailure("question_lock_contention");
            ctx.response.fail(QUESTION_LOCK_MESSAGE);
            return false;
        }
        ctx.questionLock = questionLock;
        return true;
    }

    /** 加载当前题 + flow 状态机。确保运行时数据就绪（Redis 没有则从 MongoDB 恢复），拒绝过期题号。 */
    private boolean stepLoadCurrentQuestion(InterviewAnswerPipelineContext ctx) {
        // 确保运行时数据就绪：Redis → MongoDB 热快照回补
        InterviewSessionRuntimeView runtimeView = runtimeRehydrateService.ensureRuntime(
                ctx.sessionId,
                InterviewRuntimeLoadMode.READ_WRITE_REQUIRED,
                InterviewRuntimeRehydrateScope.HOT_RUNTIME
        );
        if (runtimeView != null && !runtimeView.canWrite() && !runtimeView.isTerminal()) {
            ctx.response.fail("interview runtime restored as read-only");
            return false;
        }
        ctx.flowState = ensureInterviewFlow(ctx.sessionId);
        if (ctx.flowState == null) {
            ctx.response.fail("interview flow not initialized");
            return false;
        }
        if (interviewFlowStateMachine.isCompleted(ctx.flowState)) {
            ctx.response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId));
            ctx.response.finish().success();
            return false;
        }
        if (interviewFlowStateMachine.isOutOfRange(ctx.flowState)) {
            interviewFlowStateMachine.markCompleted(ctx.sessionId);
            ctx.response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId));
            ctx.response.finish().success();
            return false;
        }

        ctx.currentQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(ctx.flowState);
        ctx.currentQuestion = getQuestionWithReload(ctx.sessionId, ctx.currentQuestionNumber);
        if (StrUtil.isBlank(ctx.currentQuestion)) {
            ctx.response.fail("question does not exist or expired");
            return false;
        }

        ctx.currentIsFollowUp = isFollowUpQuestion(ctx.currentQuestionNumber);
        ctx.currentFollowUpCount = resolveFollowUpCount(ctx.flowState, ctx.currentQuestionNumber);
        ctx.maxFollowUp = resolveMaxFollowUp(ctx.flowState);
        ctx.response.withCurrentQuestion(ctx.currentQuestionNumber, ctx.currentQuestion);
        if (!isRequestedQuestionCurrent(ctx.requestParam.getQuestionNumber(), ctx.currentQuestionNumber)) {
            Metrics.counter("stale_question_reject_total").increment();
            recordAnswerPipelineFailure("stale_question_reject");
            ctx.response.fail(STALE_QUESTION_MESSAGE);
            return false;
        }
        return true;
    }

    /**
     * 加锁后二次校验题号。
     * 为什么要二次校验？因为 stepLoadCurrentQuestion 和 stepAcquireQuestionLock 之间有时间窗口，
     * 另一个请求可能已经推进了 flow（题号变了），导致当前请求拿到的是旧题号。
     * 加锁后重新读 flow，确认题号没变，才能继续处理。
     */
    private boolean stepValidateQuestionAfterLock(InterviewAnswerPipelineContext ctx) {
        InterviewFlowState lockedFlow = interviewFlowStateMachine.current(ctx.sessionId);
        if (lockedFlow == null) {
            ctx.response.fail("interview flow not initialized");
            return false;
        }
        String lockedQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(lockedFlow);
        if (!isRequestedQuestionCurrent(ctx.currentQuestionNumber, lockedQuestionNumber)) {
            Metrics.counter("stale_question_reject_total").increment();
            recordAnswerPipelineFailure("stale_question_reject_after_lock");
            ctx.response.fail(STALE_QUESTION_MESSAGE);
            return false;
        }
        String lockedQuestionContent = getQuestionWithReload(ctx.sessionId, lockedQuestionNumber);
        if (StrUtil.isBlank(lockedQuestionContent)) {
            ctx.response.fail("question does not exist or expired");
            return false;
        }
        ctx.flowState = lockedFlow;
        ctx.currentQuestionNumber = lockedQuestionNumber;
        ctx.currentQuestion = lockedQuestionContent;
        ctx.currentIsFollowUp = isFollowUpQuestion(lockedQuestionNumber);
        ctx.currentFollowUpCount = resolveFollowUpCount(lockedFlow, lockedQuestionNumber);
        ctx.maxFollowUp = resolveMaxFollowUp(lockedFlow);
        return true;
    }

    /**
     * AI 评分：调大模型对用户答案评分，提取结构化结果。
     * 返回字段：score（兼容分）、feedback、missing_points、anchors。
     * 是否追问完全由后端规则链决定，评分 Agent 不拥有追问决策权。
     * 注意：此时仅计算，不入账（分数提交在 stepAdvanceFlowAndAssemble 中）
     */
    private boolean stepEvaluateAndScore(InterviewAnswerPipelineContext ctx) {
        interviewFlowStateMachine.moveToEvaluating(ctx.sessionId); // 标记状态为"评分中"

        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_ANSWER_EVALUATION);
        if (agentProperties == null) {
            recordAnswerPipelineFailure("agent_config_missing");
            ctx.response.fail("agent configuration does not exist");
            return false;
        }

        Map<String, Object> evaluationResult;
        try {
            evaluationResult = interviewEvaluationService.evaluateAnswer(
                    ctx.sessionId,
                    ctx.requestId,
                    ctx.currentQuestionNumber,
                    ctx.currentQuestion,
                    ctx.requestParam.getAnswerContent(),
                    agentProperties
            );
        } catch (InterviewAiGuardException guardException) {
            recordAnswerPipelineFailure("ai_guard_" + guardException.getErrorCode().name().toLowerCase());
            ctx.response.fail(guardException.getMessage());
            return false;
        }
        if (evaluationResult == null) {
            recordAnswerPipelineFailure("evaluation_parse_failed");
            ctx.response.fail("failed to parse evaluation result");
            return false;
        }

        Integer score = interviewResponseParser.parseScoreFromResponse(evaluationResult, "score");
        if (score == null) {
            recordAnswerPipelineFailure("evaluation_score_missing");
            ctx.response.fail("score missing in evaluation result");
            return false;
        }

        ctx.missingPoints = interviewResponseParser.asStringList(evaluationResult.get("missing_points"));
        ctx.ruleScore = interviewResponseParser.parseScoreFromResponse(evaluationResult, "ruleScore");
        ctx.anchorJudgments = interviewResponseParser.parseAnchorResult(
                JSON.toJSONString(evaluationResult));
        ctx.ruleVersion = interviewResponseParser.asString(evaluationResult.get("ruleVersion"));

        ctx.score = score;
        ctx.totalScore = interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId);
        ctx.response.withEvaluation(score, interviewResponseParser.asString(evaluationResult.get("feedback")), ctx.totalScore);
        return true;
    }

    /**
     * 推进 flow + 组装响应。核心分支逻辑：
     * ① 拍 flow 快照（用于失败回滚）
     * ② 追问规则引擎根据锚点事实独立决策，评分 Agent 不参与是否追问
     * ③ 追问分支：生成追问题 → 缓存 → 推进 flow → 提交分数 → 返回追问题
     * ④ 主问题推进分支：advanceMainQuestion → 判断是否结束 → 提交分数 → 返回下一题或 finish
     * ⑤ 分数提交失败：回滚 flow 到快照状态，客户端重试仍能命中当前题
     */
    private boolean stepSecondReview(InterviewAnswerPipelineContext ctx) {
        ctx.firstScore = ctx.score;
        InterviewSecondReviewContext reviewContext = new InterviewSecondReviewContext();
        reviewContext.setFirstScore(ctx.ruleScore);
        reviewContext.setAnchorJudgments(ctx.anchorJudgments);
        reviewContext.setConfidence(resolveReviewConfidence(ctx.anchorJudgments));
        reviewContext.setRubricVersion(0);
        reviewContext.setUserRequestedReview(false);

        InterviewSecondReviewDecision decision = interviewSecondReviewRuleService.decide(reviewContext);
        ctx.reviewReasonCode = decision.getReasonCode();
        ctx.reviewFinalStrategy = decision.getReviewAction().name();
        if (decision.getReviewAction() == InterviewSecondReviewAction.DIRECT_ACCEPT) {
            applyRuleScoreAsFinal(ctx);
            ctx.secondReviewed = false;
            return true;
        }
        if (decision.getReviewAction() == InterviewSecondReviewAction.CONSERVATIVE_RESULT) {
            applyReviewMerge(ctx, interviewSecondReviewMergeService.conservative(
                    ctx.firstScore, ctx.ruleScore, ctx.anchorJudgments, ctx.ruleVersion,
                    "CONSERVATIVE_" + decision.getReasonCode()));
            ctx.secondReviewed = false;
            return true;
        }

        ctx.secondReviewed = true;
        try {
            AgentPropertiesDO scorerAgent =
                    businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_ANSWER_EVALUATION);
            Map<String, Object> reviewed = interviewEvaluationService.evaluateAnswerForReview(
                    ctx.sessionId,
                    ctx.requestId,
                    ctx.currentQuestionNumber,
                    ctx.currentQuestion,
                    ctx.requestParam.getAnswerContent(),
                    scorerAgent
            );
            if (reviewed == null) {
                applyRuleScoreAsFinal(ctx);
                ctx.reviewFinalStrategy = "REVIEW_FAILED_USE_RULE_RESULT";
                return true;
            }
            ctx.reviewedScore = interviewResponseParser.parseScoreFromResponse(reviewed, "score");
            applyReviewMerge(ctx, interviewSecondReviewMergeService.merge(
                    ctx.firstScore, ctx.ruleScore, ctx.anchorJudgments, ctx.ruleVersion, reviewed));
            return true;
        } catch (Exception ex) {
            log.warn(
                    "Second review failed; deterministic rule result remains authoritative, sessionId={}, requestId={}, questionNumber={}",
                    ctx.sessionId, ctx.requestId, ctx.currentQuestionNumber, ex);
            applyRuleScoreAsFinal(ctx);
            ctx.reviewFinalStrategy = "REVIEW_FAILED_USE_RULE_RESULT";
            return true;
        }
    }

    private void applyRuleScoreAsFinal(InterviewAnswerPipelineContext ctx) {
        if (ctx.ruleScore == null) return;
        ctx.score = ctx.ruleScore;
        ctx.response.setScore(ctx.score);
    }

    private void applyReviewMerge(
            InterviewAnswerPipelineContext ctx, InterviewSecondReviewMergeResult merged) {
        if (merged == null) return;
        if (merged.getFinalScore() != null) {
            ctx.score = merged.getFinalScore();
        }
        ctx.ruleScore = merged.getRuleScore();
        ctx.anchorJudgments = merged.getAnchorJudgments();
        ctx.ruleVersion = merged.getRuleVersion();
        ctx.reviewFinalStrategy = merged.getFinalStrategy();
        ctx.response.setScore(merged.isHidePreciseScore() ? null : ctx.score);
        if (merged.isNeedsReview()) {
            String feedback = StrUtil.blankToDefault(ctx.response.getFeedback(), "");
            String notice = merged.isHidePreciseScore()
                    ? "评分证据不足，请补充作答后再评估。"
                    : "复核结果存在关键差异，建议复习并人工确认。";
            ctx.response.setFeedback(StrUtil.isBlank(feedback) ? notice : feedback + " " + notice);
        }
    }

    private Double resolveReviewConfidence(List<Map<String, Object>> anchors) {
        if (anchors == null || anchors.isEmpty()) return null;
        Double minimum = null;
        for (Map<String, Object> anchor : anchors) {
            Object value = anchor == null ? null : anchor.get("confidence");
            if (!(value instanceof Number number)) return null;
            double confidence = number.doubleValue();
            minimum = minimum == null ? confidence : Math.min(minimum, confidence);
        }
        return minimum;
    }

    private boolean stepAdvanceFlowAndAssemble(InterviewAnswerPipelineContext ctx) {
        // ① 先拍快照：后续若计分提交失败，用于补偿回滚 flow，避免”题号推进成功但分数未入账”
        InterviewFlowState flowSnapshotBeforeAdvance = snapshotFlowState(interviewFlowStateMachine.current(ctx.sessionId));
        InterviewFollowUpRuleDecision ruleDecision = decideFollowUp(ctx);
        int resolvedMaxFollowUp = ruleDecision != null && ruleDecision.getResolvedMaxFollowUp() > 0
                ? ruleDecision.getResolvedMaxFollowUp()
                : ctx.maxFollowUp;
        boolean needFollowUp = ruleDecision != null
                && ruleDecision.isNeedFollowUp();
        ctx.followUpNeeded = needFollowUp;

        log.info(
                "Follow-up rule decision, sessionId={}, requestId={}, questionNumber={}, chainId={}, reasonCode={}, reasonText={}, ruleVersion={}, needFollowUp={}, targetAnchorIds={}, targetMissingPoints={}, resolvedMaxFollowUp={}, fallback={}",
                ctx.sessionId,
                ctx.requestId,
                ctx.currentQuestionNumber,
                ruleDecision == null ? null : ruleDecision.getChainId(),
                ruleDecision == null ? null : ruleDecision.getReasonCode(),
                ruleDecision == null ? null : ruleDecision.getReasonText(),
                ruleDecision == null ? null : ruleDecision.getRuleVersion(),
                needFollowUp,
                ruleDecision == null ? null : ruleDecision.getTargetAnchorIds(),
                ruleDecision == null ? null : ruleDecision.getTargetMissingPoints(),
                resolvedMaxFollowUp,
                ruleDecision != null && ruleDecision.isFallback()
        );

        // 2) 按规则优先走追问分支；追问生成失败则自动回落到主问题推进分支。
        if (needFollowUp && ctx.currentFollowUpCount < resolvedMaxFollowUp) {
            String mainQuestionNumber = resolveMainQuestionNumber(ctx.currentQuestionNumber);
            String mainQuestion = getQuestionWithReload(ctx.sessionId, mainQuestionNumber);
            String mainAnswer = resolveMainQuestionAnswer(ctx, mainQuestionNumber);
            String parentQuestionSpec = resolveQuestionSpec(ctx.sessionId, mainQuestionNumber);
            InterviewFollowUpService.FollowUpQuestionResult followUpQuestionResult = interviewFollowUpService.generateFollowUpQuestion(
                    ctx.sessionId,
                    ctx.requestId,
                    ctx.currentQuestionNumber,
                    mainQuestion,
                    mainAnswer,
                    ctx.currentQuestion,
                    ctx.requestParam.getAnswerContent(),
                    resolveFollowUpStrategy(ctx.sessionId, ctx.currentQuestionNumber),
                    parentQuestionSpec,
                    ruleDecision.getTargetAnchorIds(),
                    ruleDecision.getTargetMissingPoints(),
                    ctx.currentFollowUpCount,
                    resolvedMaxFollowUp
            );
            if (followUpQuestionResult.hasQuestion()) {
                interviewQuestionCacheService.cacheFollowUpQuestion(
                        ctx.sessionId,
                        followUpQuestionResult.getQuestionNumber(),
                        followUpQuestionResult.getQuestionContent()
                );
                interviewQuestionCacheService.cacheFollowUpQuestionSpec(
                        ctx.sessionId,
                        followUpQuestionResult.getQuestionNumber(),
                        followUpQuestionResult.getQuestionSpecJson()
                );
                InterviewFlowState followUpFlow = interviewFlowStateMachine.startFollowUpQuestion(
                        ctx.sessionId,
                        followUpQuestionResult.getQuestionNumber()
                );
                Integer nextFollowUpCount = followUpFlow != null && followUpFlow.getFollowUpCount() != null
                        ? followUpFlow.getFollowUpCount()
                        : followUpQuestionResult.getFollowUpCount();
                if (!commitScoreAtSuccess(ctx)) {
                    rollbackFlowAfterCommitFailure(ctx, flowSnapshotBeforeAdvance, "followup");
                    return false;
                }
                ctx.response.withNextQuestion(
                        followUpQuestionResult.getQuestionNumber(),
                        followUpQuestionResult.getQuestionContent(),
                        true,
                        nextFollowUpCount
                ).success();
                return true;
            }
        }

        // 3) 无追问时推进主问题；到末题则标记完成并返回 finish。
        InterviewFlowState nextFlow = interviewFlowStateMachine.advanceMainQuestion(ctx.sessionId);
        if (nextFlow == null || interviewFlowStateMachine.isCompleted(nextFlow)) {
            interviewFlowStateMachine.markCompleted(ctx.sessionId);
            if (!commitScoreAtSuccess(ctx)) {
                rollbackFlowAfterCommitFailure(ctx, flowSnapshotBeforeAdvance, "finish");
                return false;
            }
            ctx.response.finish().success();
            return true;
        }

        String nextQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(nextFlow);
        String nextQuestion = getQuestionWithReload(ctx.sessionId, nextQuestionNumber);
        if (StrUtil.isBlank(nextQuestion)) {
            ctx.response.fail("next question does not exist or expired");
            return false;
        }

        if (!commitScoreAtSuccess(ctx)) {
            rollbackFlowAfterCommitFailure(ctx, flowSnapshotBeforeAdvance, "next_main");
            return false;
        }
        ctx.response.withNextQuestion(nextQuestionNumber, nextQuestion, false, 0).success();
        return true;
    }

    private String resolveFollowUpStrategy(String sessionId, String questionNumber) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNumber)) {
            return "";
        }
        Map<String, String> specs = interviewQuestionCacheService.getSessionQuestionSpecs(sessionId);
        if (specs == null || specs.isEmpty()) {
            return "";
        }
        String mainQuestionNumber = questionNumber.trim().replaceFirst("-F\\d+$", "");
        String specJson = specs.get(mainQuestionNumber);
        if (StrUtil.isBlank(specJson)) {
            return "";
        }
        try {
            Map<String, Object> spec = JSON.parseObject(specJson, Map.class);
            return interviewResponseParser.asString(spec.get("followUpStrategy"));
        } catch (Exception ex) {
            log.debug("Follow-up strategy is unavailable, sessionId={}, questionNumber={}",
                    sessionId, questionNumber, ex);
            return "";
        }
    }

    /**
     * 提交分数入账。
     * - 追问不计入总分（追问只是深挖，不是新的独立问题）
     * - 只有主问题才调 addSessionScore（Lua 原子更新 sum/count/avg）
     * - 在”返回成功前”才提交，避免失败重试重复计分
     */
    private boolean commitScoreAtSuccess(InterviewAnswerPipelineContext ctx) {
        try {
            Integer committedTotalScore = Boolean.TRUE.equals(ctx.currentIsFollowUp)
                    ? interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId)  // 追问：只读总分，不入账
                    : interviewQuestionCacheService.addSessionScore(ctx.sessionId, ctx.score); // 主问题：分数入账
            ctx.totalScore = committedTotalScore;
            ctx.response.setTotalScore(committedTotalScore);
            return true;
        } catch (Exception ex) {
            log.error("Failed to commit interview score, sessionId={}, requestId={}", ctx.sessionId, ctx.requestId, ex);
            recordAnswerPipelineFailure("score_commit_failed");
            ctx.response.fail("failed to commit interview score");
            return false;
        }
    }

    /** 分数提交失败时，回滚 flow 到推进前的快照状态，保证客户端重试仍能命中当前题（不会跳题） */
    private void rollbackFlowAfterCommitFailure(
            InterviewAnswerPipelineContext ctx,
            InterviewFlowState flowSnapshotBeforeAdvance,
            String branch) {
        if (flowSnapshotBeforeAdvance == null) {
            return;
        }
        try {
            interviewQuestionCacheService.restoreInterviewFlow(ctx.sessionId, flowSnapshotBeforeAdvance);
            Metrics.counter("answer_flow_rollback_total", "branch", StrUtil.blankToDefault(branch, "unknown")).increment();
            log.warn("Rolled back interview flow after score commit failure, sessionId={}, requestId={}, branch={}",
                    ctx.sessionId, ctx.requestId, branch);
        } catch (Exception ex) {
            log.error("Failed to rollback interview flow after score commit failure, sessionId={}, requestId={}, branch={}",
                    ctx.sessionId, ctx.requestId, branch, ex);
        }
    }

    private InterviewFlowState snapshotFlowState(InterviewFlowState state) {
        if (state == null) {
            return null;
        }
        // 手动复制，避免后续对象被原地修改导致“快照”失效。
        InterviewFlowState snapshot = new InterviewFlowState();
        snapshot.setStatus(state.getStatus());
        snapshot.setCurrentIndex(state.getCurrentIndex());
        snapshot.setCurrentQuestionNumber(state.getCurrentQuestionNumber());
        snapshot.setTotalQuestions(state.getTotalQuestions());
        snapshot.setFollowUpCount(state.getFollowUpCount());
        snapshot.setMaxFollowUp(state.getMaxFollowUp());
        snapshot.setVersion(state.getVersion());
        return snapshot;
    }

    /**
     * 追问规则引擎是唯一决策者。
     * 它只消费评分事实（分数、锚点状态、缺失点）和流程状态，不读取模型追问建议。
     */
    private InterviewFollowUpRuleDecision decideFollowUp(InterviewAnswerPipelineContext ctx) {
        InterviewFollowUpRuleContext ruleContext = new InterviewFollowUpRuleContext();
        ruleContext.setSessionId(ctx.sessionId);
        ruleContext.setRequestId(ctx.requestId);
        ruleContext.setQuestionNumber(ctx.currentQuestionNumber);
        ruleContext.setInterviewType(interviewQuestionCacheService.getSessionInterviewDirection(ctx.sessionId));
        ruleContext.setFollowUpQuestion(Boolean.TRUE.equals(ctx.currentIsFollowUp));
        ruleContext.setFollowUpCount(ctx.currentFollowUpCount == null ? 0 : Math.max(ctx.currentFollowUpCount, 0));
        ruleContext.setMaxFollowUp(ctx.maxFollowUp == null ? 1 : Math.max(ctx.maxFollowUp, 1));
        ruleContext.setScore(ctx.score);
        ruleContext.setMissingPoints(ctx.missingPoints);
        ruleContext.setAnchorJudgments(ctx.anchorJudgments);
        ruleContext.setQuestionSpecJson(resolveQuestionSpec(ctx.sessionId, ctx.currentQuestionNumber));
        ruleContext.setInterviewCompleted(Boolean.TRUE.equals(ctx.response.getFinished()));
        ctx.followUpRuleDecision = interviewFollowUpRuleService.decide(ruleContext);
        return ctx.followUpRuleDecision;
    }

    /** 写答题轮次日志到 Redis List（成功时），失败不阻塞主流程（由 finishAndReturn 放入修复队列） */
    private boolean stepAppendTurnLog(InterviewAnswerPipelineContext ctx) {
        try {
            InterviewTurnLog turn = InterviewTurnLog.builder()
                    .timestamp(System.currentTimeMillis())
                    .requestId(ctx.requestId)
                    .questionNumber(ctx.currentQuestionNumber)
                    .questionContent(ctx.currentQuestion)
                    .answerContent(truncateForLog(ctx.requestParam.getAnswerContent(), 1000))
                    .score(ctx.score)
                    .ruleScore(ctx.ruleScore)
                    .anchorJudgments(ctx.anchorJudgments)
                    .ruleVersion(ctx.ruleVersion)
                    .reviewReasonCode(ctx.reviewReasonCode)
                    .secondReviewed(ctx.secondReviewed)
                    .firstScore(ctx.firstScore)
                    .reviewedScore(ctx.reviewedScore)
                    .reviewFinalStrategy(ctx.reviewFinalStrategy)
                    .totalScore(ctx.totalScore)
                    .feedback(ctx.response.getFeedback())
                    .followUpNeeded(ctx.followUpNeeded)
                    .isFollowUp(ctx.currentIsFollowUp)
                    .followUpCount(ctx.currentFollowUpCount)
                    .nextQuestionNumber(ctx.response.getNextQuestionNumber())
                    .nextQuestion(ctx.response.getNextQuestion())
                    .finished(ctx.response.getFinished())
                    .build();
            ctx.turnLog = turn;
            return interviewQuestionCacheService.appendInterviewTurnIfAbsent(ctx.sessionId, turn);
        } catch (Exception ex) {
            log.warn("Failed to append interview turn, sessionId: {}", ctx.sessionId, ex);
            return false;
        }
    }

    private InterviewFlowState ensureInterviewFlow(String sessionId) {
        InterviewFlowState state = interviewFlowStateMachine.current(sessionId);
        if (state != null) {
            return state;
        }

        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (questions == null || questions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        }
        if (questions == null || questions.isEmpty()) {
            return null;
        }
        return interviewFlowStateMachine.ensureInitialized(sessionId, questions.size());
    }

    private String getQuestionWithReload(String sessionId, String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
        if (StrUtil.isBlank(questionContent) && !isFollowUpQuestion(questionNumber)) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
        }
        return questionContent;
    }

    private String resolveMainQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        return questionNumber.trim().replaceFirst("-F\\d+$", "");
    }

    /**
     * The follow-up generator is always grounded in the root main question and its first answer.
     */
    private String resolveMainQuestionAnswer(InterviewAnswerPipelineContext ctx, String mainQuestionNumber) {
        if (!Boolean.TRUE.equals(ctx.currentIsFollowUp)) {
            return ctx.requestParam.getAnswerContent();
        }
        List<InterviewTurnLog> turns = interviewQuestionCacheService.getInterviewTurns(ctx.sessionId);
        if (turns != null) {
            for (int index = turns.size() - 1; index >= 0; index--) {
                InterviewTurnLog turn = turns.get(index);
                if (turn != null && Objects.equals(mainQuestionNumber, turn.getQuestionNumber())) {
                    return turn.getAnswerContent();
                }
            }
        }
        return "";
    }

    private String resolveQuestionSpec(String sessionId, String questionNumber) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNumber)) {
            return "";
        }
        Map<String, String> specs = interviewQuestionCacheService.getSessionQuestionSpecs(sessionId);
        if (specs == null || specs.isEmpty()) {
            return "";
        }
        String exactSpec = specs.get(questionNumber.trim());
        if (StrUtil.isNotBlank(exactSpec)) {
            return exactSpec;
        }
        return StrUtil.blankToDefault(specs.get(resolveMainQuestionNumber(questionNumber)), "");
    }

    /** 判断是否为追问题：追问题号格式为 "1-F1"、"1-F2"（主问题号-F追问序号） */
    private boolean isFollowUpQuestion(String questionNumber) {
        return StrUtil.isNotBlank(questionNumber) && questionNumber.trim().matches("\\d+-F\\d+");
    }

    private Integer resolveFollowUpCount(InterviewFlowState flowState, String questionNumber) {
        if (!isFollowUpQuestion(questionNumber)) {
            return 0;
        }
        int parsedFollowUpCount = extractFollowUpCount(questionNumber);
        if (parsedFollowUpCount > 0) {
            return parsedFollowUpCount;
        }
        if (flowState != null && flowState.getFollowUpCount() != null) {
            return Math.max(flowState.getFollowUpCount(), 0);
        }
        return 0;
    }

    private int resolveMaxFollowUp(InterviewFlowState flowState) {
        if (flowState == null || flowState.getMaxFollowUp() == null || flowState.getMaxFollowUp() <= 0) {
            return 1;
        }
        return flowState.getMaxFollowUp();
    }

    private int extractFollowUpCount(String questionNumber) {
        if (!isFollowUpQuestion(questionNumber)) {
            return 0;
        }
        int separatorIndex = questionNumber.indexOf("-F");
        if (separatorIndex < 0 || separatorIndex + 2 >= questionNumber.length()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(questionNumber.substring(separatorIndex + 2).trim()), 0);
        } catch (Exception ex) {
            return 0;
        }
    }

    private String truncateForLog(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void recordAnswerPipelineFailure(String reason) {
        String safeReason = StrUtil.isBlank(reason) ? "unknown" : reason.trim();
        Metrics.counter("answer_pipeline_fail_total", "reason", safeReason).increment();
    }

    private boolean isRequestedQuestionCurrent(String requestedQuestion, String currentQuestion) {
        String normalizedRequested = normalizeQuestionNumber(requestedQuestion);
        String normalizedCurrent = normalizeQuestionNumber(currentQuestion);
        return Objects.equals(normalizedRequested, normalizedCurrent);
    }

    private String normalizeQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim().toUpperCase();
        if (normalized.matches("\\d+")) {
            try {
                return String.valueOf(Integer.parseInt(normalized));
            } catch (Exception ex) {
                return normalized;
            }
        }
        if (normalized.matches("\\d+-F\\d+")) {
            String[] parts = normalized.split("-F");
            try {
                return Integer.parseInt(parts[0]) + "-F" + Integer.parseInt(parts[1]);
            } catch (Exception ex) {
                return normalized;
            }
        }
        return normalized;
    }

    private static final class InterviewAnswerPipelineContext {
        private String sessionId;
        private InterviewAnswerReqDTO requestParam;
        private InterviewAnswerRespDTO response;
        private String requestId;
        private InterviewFlowState flowState;
        private String currentQuestionNumber;
        private String currentQuestion;
        private Boolean currentIsFollowUp;
        private Integer currentFollowUpCount;
        private Integer maxFollowUp;
        private Integer score;
        private Integer ruleScore;
        private List<Map<String, Object>> anchorJudgments;
        private String ruleVersion;
        private Integer totalScore;
        private Boolean followUpNeeded;
        private List<String> missingPoints;
        private InterviewFollowUpRuleDecision followUpRuleDecision;
        private String reviewReasonCode;
        private Boolean secondReviewed;
        private Integer firstScore;
        private Integer reviewedScore;
        private String reviewFinalStrategy;
        private InterviewTurnLog turnLog;
        private boolean idempotencyStarted;
        private boolean idempotencyMarkedSucceeded;
        private RLock questionLock;
    }
}
