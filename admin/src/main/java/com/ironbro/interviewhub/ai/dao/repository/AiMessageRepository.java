package com.ironbro.interviewhub.ai.dao.repository;

import com.ironbro.interviewhub.ai.dao.entity.AiMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AI消息Repository接口
 */
@Repository
public interface AiMessageRepository extends MongoRepository<AiMessage, String> {

    /**
     * 根据会话ID查询消息列表，按序号升序
     */
    List<AiMessage> findBySessionIdAndDelFlagOrderByMessageSeqAsc(String sessionId, Integer delFlag);

    /**
     * 获取会话中最大消息序号
     */
    AiMessage findTopBySessionIdAndDelFlagOrderByMessageSeqDesc(String sessionId, Integer delFlag);

    /**
     * 统计会话消息数量
     */
    long countBySessionIdAndDelFlag(String sessionId, Integer delFlag);
}