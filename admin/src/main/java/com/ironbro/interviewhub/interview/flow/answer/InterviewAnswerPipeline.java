package com.ironbro.interviewhub.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.api.io.req.InterviewAnswerReqDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewAnswerRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 答题流水线：用户提交答案后的处理链路。
 */
@Component
@Slf4j
public class InterviewAnswerPipeline {

    private static final long ANSWER_TIMEOUT_SECONDS = 15;

    public InterviewAnswerRespDTO execute(String sessionId, InterviewAnswerReqDTO requestParam) {
        InterviewAnswerRespDTO response = InterviewAnswerRespDTO.init();

        // 1) 参数校验
        if (StrUtil.isBlank(sessionId)) {
            return response.fail("sessionId不能为空");
        }
        if (requestParam == null) {
            return response.fail("请求体不能为空");
        }
        if (StrUtil.isBlank(requestParam.getQuestionNumber())) {
            return response.fail("题号不能为空");
        }
        if (StrUtil.isBlank(requestParam.getAnswerContent())) {
            return response.fail("答案内容不能为空");
        }

        long startTime = System.currentTimeMillis();
        try {
            // 2) TODO: 归一化 requestId（幂等键）
            // 3) TODO: 幂等门禁
            // 4) TODO: 加载当前题 + flow
            // 5) TODO: 题号级加锁
            // 6) TODO: 二次校验题号
            // 7) TODO: AI 评分
            // 8) TODO: 推进 flow + 提交分数

            log.info("答题流水线处理完成, sessionId={}, questionNumber={}", sessionId, requestParam.getQuestionNumber());
            return response.success();
        } catch (Exception e) {
            log.error("答题流水线异常, sessionId={}", sessionId, e);
            return cleanupAfterTimeout(response, sessionId, startTime);
        }
    }

    /**
     * 超时清理：把卡住的会话状态回滚
     */
    private InterviewAnswerRespDTO cleanupAfterTimeout(InterviewAnswerRespDTO response, String sessionId, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > ANSWER_TIMEOUT_SECONDS * 1000) {
            log.warn("答题超时, sessionId={}, elapsed={}ms", sessionId, elapsed);
            // TODO: 回滚会话状态到 READY，避免一直卡在 ANSWERING
        }
        return response.fail("答题处理超时或异常");
    }
}