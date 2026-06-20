package com.ironbro.interviewhub.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.config.InterviewTurnRepairConfiguration;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 轮次补偿服务：漏掉的答题轮次定时修回来
 * TODO: 与幂等锁有冲突风险，后面可能回退
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewTurnRepairService {

    private static final String TURN_REPAIR_QUEUE_KEY = "interview:turn:repair:queue";

    private final StringRedisTemplate stringRedisTemplate;
    private final InterviewTurnRepairConfiguration configuration;

    public void enqueue(String sessionId, String requestId, String questionNumber, String reason) {
        if (StrUtil.isBlank(sessionId)) return;
        try {
            String task = String.format("{\"sessionId\":\"%s\",\"requestId\":\"%s\",\"questionNumber\":\"%s\",\"reason\":\"%s\",\"retryCount\":0}",
                    sessionId, requestId, questionNumber, reason);
            stringRedisTemplate.opsForList().rightPush(TURN_REPAIR_QUEUE_KEY, task);
        } catch (Exception ex) {
            log.warn("轮次补偿入队失败, sessionId={}", sessionId, ex);
        }
    }

    @Scheduled(fixedDelayString = "${xunzhi-agent.interview.turn-repair.fixed-delay-millis:3000}")
    public void repairPendingTurns() {
        if (!Boolean.TRUE.equals(configuration.getEnable())) return;
        int batchSize = configuration.getBatchSize() != null ? configuration.getBatchSize() : 50;
        for (int i = 0; i < batchSize; i++) {
            String payload = stringRedisTemplate.opsForList().leftPop(TURN_REPAIR_QUEUE_KEY);
            if (StrUtil.isBlank(payload)) return;
            log.info("轮次补偿处理中: {}", payload);
        }
    }

    @Data
    public static class TurnRepairTask {
        private String sessionId;
        private String requestId;
        private String questionNumber;
        private Integer retryCount;
        private String reason;
    }
}