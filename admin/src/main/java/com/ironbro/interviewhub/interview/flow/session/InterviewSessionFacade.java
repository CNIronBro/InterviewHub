package com.ironbro.interviewhub.interview.flow.session;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ironbro.interviewhub.agent.api.io.resp.AgentMessageHistoryRespDTO;
import com.ironbro.interviewhub.common.convention.exception.ClientException;
import com.ironbro.interviewhub.common.enums.InterviewErrorCodeEnum;
import com.ironbro.interviewhub.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.ironbro.interviewhub.interview.api.io.req.InterviewAnswerReqDTO;
import com.ironbro.interviewhub.interview.api.io.req.InterviewConversationPageReqDTO;
import com.ironbro.interviewhub.interview.api.io.req.InterviewQuestionReqDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewAnswerRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewConversationRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewQuestionRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewRecordRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewSessionCreateRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewSessionRestoreRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.RadarChartDTO;
import com.ironbro.interviewhub.interview.application.InterviewWorkflowService;
import com.ironbro.interviewhub.interview.application.runtime.InterviewSessionRuntimeRehydrateService;
import com.ironbro.interviewhub.interview.application.runtime.InterviewRuntimeRehydrateScope;
import com.ironbro.interviewhub.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.ironbro.interviewhub.interview.dao.entity.InterviewQuestion;
import com.ironbro.interviewhub.interview.dao.entity.InterviewSession;
import com.ironbro.interviewhub.interview.flow.report.InterviewResumePreviewService;
import com.ironbro.interviewhub.interview.service.InterviewQuestionCacheService;
import com.ironbro.interviewhub.interview.service.InterviewQuestionService;
import com.ironbro.interviewhub.interview.service.InterviewRecordService;
import com.ironbro.interviewhub.interview.service.InterviewSessionService;
import com.ironbro.interviewhub.interview.service.model.InterviewRuntimeLoadMode;
import com.ironbro.interviewhub.interview.service.model.InterviewSessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 面试会话的门面层，职责：
 * 1. 权限校验（requireOwnedSession — 校验会话归属当前用户）
 * 2. 状态推进（DRAFT → READY → IN_PROGRESS → FINISHED）
 * 3. 组装请求参数，委托下层执行
 * Facade 不含业务逻辑，只做"守门 + 转发"
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionFacade {

    // 编排层接口（实际实现是 InterviewAgentOrchestrationService），负责转发到具体 flow
    private final InterviewWorkflowService interviewWorkflowService;
    // Redis 缓存：面试题、分数、建议、雷达图数据
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    // DB 操作：interview_question 表
    private final InterviewQuestionService interviewQuestionService;
    // DB 操作：interview_record 表（面试记录/报告）
    private final InterviewRecordService interviewRecordService;
    // 简历预览（PDF 渲染）
    private final InterviewResumePreviewService interviewResumePreviewService;
    // DB 操作：interview_session 表 + 会话状态管理
    private final InterviewSessionService interviewSessionService;
    // 运行时快照：答题进度、分数等运行态数据的快照刷新
    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService;
    // 运行时恢复：从 DB 回补缓存（页面刷新/恢复场景）
    private final InterviewSessionRuntimeRehydrateService runtimeRehydrateService;

    // ==================== 会话生命周期 ====================

    /** 创建面试会话，插入 DB 并返回 sessionId */
    public InterviewSessionCreateRespDTO createSession(Long userId) {
        return interviewSessionService.createSession(userId);
    }

    /** 分页查询用户的面试会话列表 */
    public IPage<InterviewConversationRespDTO> pageConversations(Long userId, InterviewConversationPageReqDTO requestParam) {
        return interviewSessionService.pageConversations(userId, requestParam);
    }

    /** 获取对话历史（当前未启用，直接拒绝） */
    public List<AgentMessageHistoryRespDTO> getConversationHistory(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId); // 校验会话归属
        throw new ClientException("interview history is not enabled", InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
    }

    /** 分页获取历史消息（当前未启用） */
    public IPage<AgentMessageHistoryRespDTO> pageHistoryMessages(
            String sessionId,
            Integer current,
            Integer size,
            Long userId) {
        if (StrUtil.isNotBlank(sessionId)) {
            interviewSessionService.requireOwnedSession(sessionId, userId); // 校验会话归属
        }
        throw new ClientException("interview history paging is not enabled", InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
    }

    /**
     * 结束面试：从 Redis 读取运行时数据 → 生成面试报告落库 → 更新会话状态为 FINISHED
     * 内部含分布式锁与重试，防止并发结束导致数据不一致
     */
    public void finishSession(String sessionId, Long userId) {
        interviewRecordService.saveInterviewRecordFromRedis(sessionId, userId);
    }

    /** 结束对话，与 finishSession 同逻辑 */
    public void endConversation(String sessionId, Long userId) {
        finishSession(sessionId, userId);
    }

    // ==================== 核心面试流程 ====================

    /**
     * 上传简历 → AI 出题
     * 状态推进：DRAFT → UPLOADING → (成功) READY / (失败) DRAFT
     */
    public InterviewQuestionRespDTO extractInterviewQuestions(
            String sessionId,
            MultipartFile resumePdf,
            Long userId,
            String username) {
        // 1) 先标记”上传中”，防止并发请求误判会话状态
        interviewSessionService.markResumeUploading(sessionId, userId);

        // 2) 组装请求，委托编排层（内部：上传OSS → AI解析简历 → 提取面试题 → 落库）
        InterviewQuestionReqDTO reqDTO = new InterviewQuestionReqDTO();
        reqDTO.setUserName(username);
        reqDTO.setSessionId(sessionId);
        reqDTO.setResumePdf(resumePdf);

        InterviewQuestionRespDTO response = interviewWorkflowService.extractInterviewQuestions(reqDTO);
        if (response != null && Integer.valueOf(1).equals(response.getIsSuccess())) {
            // 3) 成功：状态 → READY，同步简历URL和面试方向到会话表
            interviewSessionService.markReady(
                    sessionId,
                    userId,
                    response.getResumeFileUrl(),
                    response.getInterviewType()
            );
            runtimeSnapshotService.refreshAfterQuestionExtraction(sessionId); // 刷新运行时快照
            return response;
        }

        // 3) 失败：状态回落 DRAFT，用户可重新上传
        interviewSessionService.markDraft(sessionId, userId);
        return response;
    }

    /**
     * 【核心】提交答案
     * 状态推进：READY → IN_PROGRESS（首次答题时），委托编排层 → Pipeline（加锁 → 幂等 → AI评分 → 追问 → 推进题号）
     */
    public InterviewAnswerRespDTO answerInterviewQuestion(
            String sessionId,
            InterviewAnswerReqDTO requestParam,
            Long userId) {
        ensureInterviewCanProceed(sessionId, userId);       // 校验会话归属 + 状态是否可继续
        interviewSessionService.markInProgressIfReady(sessionId, userId); // 首次答题：READY → IN_PROGRESS
        requestParam.setSessionId(sessionId);
        return interviewWorkflowService.answerInterviewQuestion(sessionId, requestParam); // 委托 Pipeline
    }

    /** 获取下一题，状态同 answer 一样需要可继续 */
    public InterviewAnswerRespDTO getNextQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        interviewSessionService.markInProgressIfReady(sessionId, userId);
        return interviewWorkflowService.getNextQuestion(sessionId); // 委托编排层取下一题
    }

    /** 获取当前题（页面刷新时用），只有未结束时才推进状态 */
    public InterviewAnswerRespDTO getCurrentQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        InterviewAnswerRespDTO response = interviewWorkflowService.getCurrentQuestion(sessionId);
        if (response != null && Boolean.TRUE.equals(response.getIsSuccess()) && !Boolean.TRUE.equals(response.getFinished())) {
            interviewSessionService.markInProgressIfReady(sessionId, userId);
        }
        return response;
    }

    // ==================== 会话恢复 & 数据查询 ====================

    /** 加载简历预览（PDF 渲染） */
    public InterviewResumePreviewService.ResumePreviewResource loadResumePreview(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId); // 校验归属
        return interviewResumePreviewService.loadResumePreview(sessionId);
    }

    /**
     * 恢复面试会话（用户中途退出后重新进入）
     * 数据来源优先级：session 表 → question 表 → Redis 缓存 → DB 回补缓存，三层数据兜底保证恢复页一定能渲染
     */
    public InterviewSessionRestoreRespDTO restoreInterviewSession(String sessionId, Long userId) {
        // 1) 从 session 表读主信息（状态、简历URL、面试方向）
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.MATERIAL_ONLY); // 确保运行时数据就绪

        InterviewSessionRestoreRespDTO response = new InterviewSessionRestoreRespDTO();
        response.setSessionId(sessionId);
        response.setStatus(session.getStatus());
        response.setCanResume(isSessionResumable(session));
        response.setResumeFileUrl(session.getResumeFileUrl());
        response.setInterviewType(session.getInterviewType());

        // 2) 从 question 表补字段（session 表可能没存简历URL和面试方向）
        InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
        if (question != null) {
            if (StrUtil.isBlank(response.getResumeFileUrl())) {
                response.setResumeFileUrl(question.getResumeFileUrl());
            }
            if (StrUtil.isBlank(response.getInterviewType())) {
                response.setInterviewType(question.getInterviewType());
            }
            response.setResumeScore(question.getResumeScore());
        }

        // 3) 从 Redis 缓存读建议，缓存没有则从 DB 回补
        Map<String, String> suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        if (suggestions == null || suggestions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewSuggestionsFromDatabase(sessionId); // DB → Redis 回补
            suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        }
        response.setSuggestions(suggestions);

        // 4) 从 Redis 缓存读简历评分，同样支持 DB 回补
        Integer resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        if (resumeScore == null) {
            interviewQuestionCacheService.loadResumeScoreFromDatabase(sessionId); // DB → Redis 回补
            resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        }
        if (resumeScore != null) {
            response.setResumeScore(resumeScore);
        }

        if (StrUtil.isBlank(response.getInterviewType()) && question != null) {
            response.setInterviewType(question.getInterviewType());
        }
        return response;
    }

    /** 获取面试题目列表（题号 → 题目内容），缓存没有则从 DB 回补 */
    public Map<String, String> getSessionInterviewQuestions(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId); // 校验归属
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.MATERIAL_ONLY); // 确保运行时就绪

        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId); // 先查 Redis
        if (questions == null || questions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId); // 缓存没有 → 从 DB 回补到 Redis
            questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        }
        return questions;
    }

    /**
     * 获取面试总分
     * 优先级：Redis 缓存 > interview_record 表快照
     */
    public Integer getSessionTotalScore(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.SCORE_ONLY); // 确保分数数据就绪
        Integer score = interviewQuestionCacheService.getSessionTotalScore(sessionId); // 先查 Redis
        if (score != null && score > 0) {
            return score;
        }
        // 缓存没有 → 从 record 表兜底
        InterviewRecordRespDTO record = interviewRecordService.getBySessionId(sessionId, userId);
        if (record != null && record.getInterviewScore() != null) {
            return record.getInterviewScore();
        }
        return score;
    }

    /** 获取面试建议（AI 生成的改进建议），缓存没有则从 DB 回补 */
    public Map<String, String> getSessionInterviewSuggestions(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.MATERIAL_ONLY);

        Map<String, String> suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId); // 先查 Redis
        if (suggestions == null || suggestions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewSuggestionsFromDatabase(sessionId); // DB → Redis 回补
            suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        }
        return suggestions;
    }

    /** 获取简历评分，缓存没有则从 DB 回补 */
    public Integer getSessionResumeScore(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.MATERIAL_ONLY);

        Integer resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId); // 先查 Redis
        if (resumeScore == null) {
            interviewQuestionCacheService.loadResumeScoreFromDatabase(sessionId); // DB → Redis 回补
            resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        }
        return resumeScore;
    }

    /**
     * 获取雷达图数据（5 维：简历分 + 面试表现 + 仪态 + 专业技能 + 潜力指数）
     * 优先级：Redis 缓存 > interview_record 表快照
     */
    public RadarChartDTO getRadarChartData(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.FULL_RUNTIME); // 全量运行时数据就绪
        RadarChartDTO radar = interviewQuestionCacheService.getRadarChartData(sessionId); // 先查 Redis
        if (hasRadarSignal(radar)) {
            return radar;
        }
        // 缓存没有有效数据 → 从 record 表兜底
        InterviewRecordRespDTO record = interviewRecordService.getBySessionId(sessionId, userId);
        if (record != null && record.getRadarChart() != null) {
            return record.getRadarChart();
        }
        return radar;
    }

    // ==================== 仪态分析 ====================

    /**
     * 上传照片 → AI 评仪态（表情、着装、精神面貌）
     * 委托编排层 → InterviewDemeanorService（上传OSS → AI分析 → 评分落缓存）
     */
    public String evaluateDemeanor(
            String sessionId,
            MultipartFile userPhoto,
            String requestSessionId,
            Long userId,
            String username) {
        ensureInterviewCanProceed(sessionId, userId); // 校验会话可继续
        if (requestSessionId != null && !sessionId.equals(requestSessionId)) {
            throw new ClientException("sessionId mismatch between path and request parameter");
        }
        // 组装请求，委托编排层
        DemeanorEvaluationReqDTO reqDTO = new DemeanorEvaluationReqDTO();
        reqDTO.setUserName(username);
        reqDTO.setSessionId(sessionId);
        reqDTO.setUserPhoto(userPhoto);
        return interviewWorkflowService.evaluateDemeanor(reqDTO);
    }

    // ==================== 内部工具方法 ====================

    /** 校验会话归属当前用户 且 状态允许继续面试（READY / IN_PROGRESS） */
    private void ensureInterviewCanProceed(String sessionId, Long userId) {
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);
        if (session == null || !isSessionResumable(session)) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
        }
    }

    /** 判断会话状态是否可继续（READY 或 IN_PROGRESS 才行） */
    private boolean isSessionResumable(InterviewSession session) {
        if (session == null || StrUtil.isBlank(session.getStatus())) {
            return false;
        }
        try {
            return InterviewSessionStatus.valueOf(session.getStatus()).canResume();
        } catch (Exception ex) {
            return false;
        }
    }

    /** 判断雷达图是否包含有效数据（任一维度 > 0 即视为有效） */
    private boolean hasRadarSignal(RadarChartDTO radar) {
        if (radar == null) {
            return false;
        }
        return positive(radar.getResumeScore())
                || positive(radar.getInterviewPerformance())
                || positive(radar.getDemeanorEvaluation())
                || positive(radar.getProfessionalSkills())
                || positive(radar.getPotentialIndex());
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
