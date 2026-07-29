package com.ironbro.interviewhub.interview.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ironbro.interviewhub.agent.api.io.resp.AgentMessageHistoryRespDTO;
import com.ironbro.interviewhub.common.convention.annotation.CurrentUser;
import com.ironbro.interviewhub.common.convention.context.UserContext;
import com.ironbro.interviewhub.common.convention.result.Result;
import com.ironbro.interviewhub.common.convention.result.Results;
import com.ironbro.interviewhub.interview.api.io.req.InterviewAnswerReqDTO;
import com.ironbro.interviewhub.interview.api.io.req.InterviewConversationPageReqDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewAnswerRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewConversationRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewQuestionRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewSessionCreateRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewSessionRestoreRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.RadarChartDTO;
import com.ironbro.interviewhub.interview.flow.session.InterviewSessionFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 面试模块的总 Controller，覆盖面试全生命周期：
 * 创建会话 → 上传简历出题 → 答题+评分+追问 → 仪态分析 → 结束生成报告
 *
 * 【架构位置】Controller → Facade(权限/状态) → Orchestration(编排) → Pipeline(业务) → Invoker(AI调用)
 * 【面试要点】Controller 只做参数校验和取当前用户，所有业务逻辑下推到 Facade
 */
@Validated
@RestController
@RequestMapping("/api/xunzhi/v1/interview")
@RequiredArgsConstructor
public class InterviewSessionController {

    // Facade 层：统一处理权限校验、会话状态推进、请求组装
    private final InterviewSessionFacade interviewSessionFacade;

    // ==================== 会话生命周期 ====================

    /** 创建面试会话，返回 sessionId，前端拿到后用于后续所有请求 */
    @PostMapping("/sessions")
    public Result<InterviewSessionCreateRespDTO> createSession(@CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.createSession(currentUser.getUserId()));
    }

    // ==================== 会话列表 & 历史消息 ====================

    /** 分页查询用户的面试会话列表（历史记录页） */
    @GetMapping("/conversations")
    public Result<IPage<InterviewConversationRespDTO>> pageConversations(
            InterviewConversationPageReqDTO requestParam,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.pageConversations(currentUser.getUserId(), requestParam));
    }

    /** 获取某个会话的对话消息（当前未启用，直接抛异常） */
    @GetMapping("/conversations/{sessionId}/messages")
    public Result<List<AgentMessageHistoryRespDTO>> getConversationHistory(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getConversationHistory(sessionId, currentUser.getUserId()));
    }

    /** 分页查询历史消息（当前未启用） */
    @GetMapping("/messages/history")
    public Result<IPage<AgentMessageHistoryRespDTO>> pageHistoryMessages(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @CurrentUser UserContext currentUser) {
        return Results.success(
                interviewSessionFacade.pageHistoryMessages(sessionId, current, size, currentUser.getUserId()));
    }

    /** 结束面试：生成面试报告、落库记录、更新会话状态为 FINISHED */
    @PutMapping("/sessions/{sessionId}/finish")
    public Result<Void> finishSession(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        interviewSessionFacade.finishSession(sessionId, currentUser.getUserId());
        return Results.success();
    }

    /** 结束对话（与 finishSession 同逻辑，前端两种结束方式都走这里） */
    @PutMapping("/conversations/{sessionId}/end")
    public Result<Void> endConversation(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        interviewSessionFacade.endConversation(sessionId, currentUser.getUserId());
        return Results.success();
    }

    // ==================== 核心面试流程 ====================

    /**
     * 上传简历 → AI 出题
     * 流程：标记"上传中" → 上传OSS → AI解析简历提取面试题 → 状态推进到 READY
     * 失败会回落到 DRAFT，允许重试
     */
    @PostMapping("/sessions/{sessionId}/interview-questions")
    public Result<InterviewQuestionRespDTO> extractInterviewQuestions(
            @PathVariable String sessionId,
            @RequestParam("resumePdf") MultipartFile resumePdf,
            @RequestParam(value = "confirmedTarget", required = false) String confirmedTarget,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.extractInterviewQuestions(
                sessionId, resumePdf, currentUser.getUserId(), currentUser.getUsername(), confirmedTarget));
    }

    /**
     * 【核心】提交答案（form 表单格式）
     *
     * 完整流程：
     *   Controller: 参数校验 → 组装 DTO
     *   Facade: 校验会话归属+状态 → READY 提升到 IN_PROGRESS
     *   Pipeline: 加锁 → 幂等检查 → AI评分 → 判断追问 → 生成追问 → 推进题号
     *   Invoker: SingleFlight去重 → Guard限流 → 调大模型
     *
     * requestId 用于幂等：同一 requestId 重复提交不会重复执行
     */
    @PostMapping("/sessions/{sessionId}/interview/answer")
    public Result<InterviewAnswerRespDTO> answerInterviewQuestion(
            @PathVariable String sessionId,
            @NotBlank(message = "questionNumber cannot be blank")
            @Size(max = 32, message = "questionNumber length must be less than or equal to 32")
            @RequestParam("questionNumber") String questionNumber,
            @NotBlank(message = "answerContent cannot be blank")
            @Size(max = 5000, message = "answerContent length must be less than or equal to 5000")
            @RequestParam("answerContent") String answerContent,
            @RequestParam(value = "requestId", required = false) String requestId,
            @CurrentUser UserContext currentUser) {
        InterviewAnswerReqDTO requestParam = new InterviewAnswerReqDTO();
        requestParam.setQuestionNumber(questionNumber);
        requestParam.setAnswerContent(answerContent);
        requestParam.setRequestId(requestId);
        return Results.success(
                interviewSessionFacade.answerInterviewQuestion(sessionId, requestParam, currentUser.getUserId()));
    }

    /** 提交答案（JSON body 格式），逻辑与上面完全一致，只是入参方式不同 */
    @PostMapping(value = "/sessions/{sessionId}/interview/answer-json", consumes = "application/json")
    public Result<InterviewAnswerRespDTO> answerInterviewQuestionJson(
            @PathVariable String sessionId,
            @Valid @RequestBody InterviewAnswerReqDTO requestParam,
            @CurrentUser UserContext currentUser) {
        return Results.success(
                interviewSessionFacade.answerInterviewQuestion(sessionId, requestParam, currentUser.getUserId()));
    }

    /** 获取下一题（答题完成后前端调用，推进到下一道面试题） */
    @GetMapping("/sessions/{sessionId}/next-question")
    public Result<InterviewAnswerRespDTO> getNextQuestion(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getNextQuestion(sessionId, currentUser.getUserId()));
    }

    /** 获取当前题（页面刷新/恢复时用，不推进题号） */
    @GetMapping("/sessions/{sessionId}/current-question")
    public Result<InterviewAnswerRespDTO> getCurrentQuestion(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getCurrentQuestion(sessionId, currentUser.getUserId()));
    }

    // ==================== 会话恢复 & 数据查询 ====================

    /**
     * 恢复面试会话（用户中途退出后重新进入）
     * 返回：会话状态、是否可继续、简历URL、面试方向、建议、简历评分
     * 内部会从数据库回补缓存，保证恢复页可直接渲染
     */
    @GetMapping("/sessions/{sessionId}/restore")
    public Result<InterviewSessionRestoreRespDTO> restoreInterviewSession(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.restoreInterviewSession(sessionId, currentUser.getUserId()));
    }

    /** 获取该会话的面试题目列表（题号 → 题目内容） */
    @GetMapping("/sessions/{sessionId}/interview/questions")
    public Result<Map<String, String>> getSessionInterviewQuestions(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getSessionInterviewQuestions(sessionId, currentUser.getUserId()));
    }

    /** 获取面试总分（优先取缓存，缓存没有则取记录快照） */
    @GetMapping("/sessions/{sessionId}/interview/score")
    public Result<Integer> getSessionTotalScore(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getSessionTotalScore(sessionId, currentUser.getUserId()));
    }

    /** 获取面试建议（AI 根据整体表现生成的改进建议） */
    @GetMapping("/sessions/{sessionId}/interview/suggestions")
    public Result<Map<String, String>> getSessionInterviewSuggestions(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(
                interviewSessionFacade.getSessionInterviewSuggestions(sessionId, currentUser.getUserId()));
    }

    /** 获取简历评分（AI 对简历质量的打分） */
    @GetMapping("/sessions/{sessionId}/resume/score")
    public Result<Integer> getSessionResumeScore(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getSessionResumeScore(sessionId, currentUser.getUserId()));
    }

    /** 获取雷达图数据（简历分 + 面试表现 + 仪态 + 专业技能 + 潜力指数） */
    @GetMapping("/sessions/{sessionId}/radar-chart")
    public Result<RadarChartDTO> getRadarChartData(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getRadarChartData(sessionId, currentUser.getUserId()));
    }

    // ==================== 仪态分析 ====================

    /**
     * 上传照片 → AI 评仪态（表情、着装、精神面貌等）
     * 前端在面试过程中定时拍照上传，AI 返回仪态评分
     */
    @PostMapping("/sessions/{sessionId}/demeanor-evaluation")
    public Result<String> evaluateDemeanor(
            @PathVariable String sessionId,
            @RequestPart("userPhoto") MultipartFile userPhoto,
            @RequestParam(value = "sessionId", required = false) String requestSessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.evaluateDemeanor(
                sessionId,
                userPhoto,
                requestSessionId,
                currentUser.getUserId(),
                currentUser.getUsername()));
    }
}
