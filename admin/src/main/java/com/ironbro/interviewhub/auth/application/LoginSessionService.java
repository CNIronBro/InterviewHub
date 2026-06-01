package com.ironbro.interviewhub.auth.application;

public interface LoginSessionService {

    void login(String username);

    void logoutCurrent();

    boolean isCurrentLoggedIn();

    String getCurrentToken();

    String getCurrentLoginId();
}
