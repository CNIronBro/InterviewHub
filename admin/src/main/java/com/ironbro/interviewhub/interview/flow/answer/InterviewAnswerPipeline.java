package com.ironbro.interviewhub.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.api.io.req.InterviewAnswerReqDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewAnswerRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 答题流水线：用户提交答案后的处理链路（骨架版）。
 * TODO: 后续加 timeout、幂等、题号锁、AI评分、追问规则引擎
 */
@Component
@Slf4j
public class InterviewAnswerPipeline {

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

        // 2) TODO: 归一化 requestId（幂等键）
        // 3) TODO: 幂等门禁
        // 4) TODO: 加载当前题 + flow
        // 5) TODO: 题号级加锁
        // 6) TODO: 二次校验题号
        // 7) TODO: AI 评分
        // 8) TODO: 推进 flow + 提交分数

        log.info("答题流水线处理完成, sessionId={}, questionNumber={}", sessionId, requestParam.getQuestionNumber());
        return response.success();
    }
}