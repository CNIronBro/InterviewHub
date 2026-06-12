package com.ironbro.interviewhub.agent.dao.repository;

import com.ironbro.interviewhub.agent.dao.entity.AgentConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentConversationRepository extends MongoRepository<AgentConversation, String> {

    Optional<AgentConversation> findBySessionIdAndDelFlag(String sessionId, Integer delFlag);

    Page<AgentConversation> findByUserIdAndDelFlagOrderByUpdateTimeDesc(Long userId, Integer delFlag, Pageable pageable);

    Page<AgentConversation> findByUserIdAndAgentIdAndDelFlagOrderByUpdateTimeDesc(Long userId, Long agentId, Integer delFlag, Pageable pageable);
}