package com.ironbro.interviewhub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.ironbro.interviewhub.**.dao.mapper")
@EnableScheduling
public class InterviewHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewHubApplication.class, args);
    }
}
