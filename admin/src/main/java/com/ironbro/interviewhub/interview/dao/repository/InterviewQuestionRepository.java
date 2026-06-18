package com.ironbro.interviewhub.interview.dao.repository;

import com.ironbro.interviewhub.interview.dao.entity.InterviewQuestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewQuestionRepository extends MongoRepository<InterviewQuestion, String> {

    Optional<InterviewQuestion> findBySessionIdAndDelFlag(String sessionId, Integer delFlag);

    List<InterviewQuestion> findByUserNameAndDelFlagOrderByCreateTimeDesc(String userName, Integer delFlag);

    Page<InterviewQuestion> findByUserNameAndDelFlagOrderByCreateTimeDesc(String userName, Integer delFlag, Pageable pageable);

    List<InterviewQuestion> findByInterviewTypeAndDelFlagOrderByCreateTimeDesc(String interviewType, Integer delFlag);
}