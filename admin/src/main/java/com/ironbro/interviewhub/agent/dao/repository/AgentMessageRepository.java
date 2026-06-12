package com.ironbro.interviewhub.agent.dao.repository;

import com.ironbro.interviewhub.agent.dao.entity.AgentMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentMessageRepository extends MongoRepository<AgentMessage, String> {

    List<AgentMessage> findBySessionIdAndDelFlagOrderByMessageSeqAsc(String sessionId, Integer delFlag);

    AgentMessage findTopBySessionIdAndDelFlagOrderByMessageSeqDesc(String sessionId, Integer delFlag);

    Integer countBySessionIdAndDelFlag(String sessionId, Integer delFlag);
}