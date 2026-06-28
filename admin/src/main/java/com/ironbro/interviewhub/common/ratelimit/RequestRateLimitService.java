package com.ironbro.interviewhub.common.ratelimit;

public interface RequestRateLimitService {

    boolean tryAcquire(String key, RequestRateLimitPolicy policy);
}
