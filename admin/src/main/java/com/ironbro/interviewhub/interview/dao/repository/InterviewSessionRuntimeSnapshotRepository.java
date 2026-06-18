package com.ironbro.interviewhub.interview.dao.repository;

import com.ironbro.interviewhub.interview.dao.entity.InterviewSessionRuntimeSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InterviewSessionRuntimeSnapshotRepository extends MongoRepository<InterviewSessionRuntimeSnapshot, String> {

    Optional<InterviewSessionRuntimeSnapshot> findBySessionId(String sessionId);
}