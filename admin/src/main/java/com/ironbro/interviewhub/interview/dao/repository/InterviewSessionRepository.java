package com.ironbro.interviewhub.interview.dao.repository;

import com.ironbro.interviewhub.interview.dao.entity.InterviewSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InterviewSessionRepository extends MongoRepository<InterviewSession, String> {

    Optional<InterviewSession> findBySessionIdAndDelFlag(String sessionId, Integer delFlag);
}