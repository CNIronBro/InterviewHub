package com.ironbro.interviewhub.auth.infrastructure.satoken;

import cn.dev33.satoken.stp.StpUtil;
import com.ironbro.interviewhub.auth.application.LoginSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SaTokenLoginSessionService implements LoginSessionService {

    @Override
    public void login(String username) {
        StpUtil.login(username);
    }

    @Override
    public void logoutCurrent() {
        StpUtil.logout();
    }

    @Override
    public boolean isCurrentLoggedIn() {
        try {
            return StpUtil.isLogin();
        } catch (Exception ex) {
            log.error("Failed to check current login status: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public String getCurrentToken() {
        try {
            return StpUtil.getTokenValue();
        } catch (Exception ex) {
            log.error("Failed to get current token: {}", ex.getMessage());
            return null;
        }
    }

    @Override
    public String getCurrentLoginId() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId != null ? loginId.toString() : null;
        } catch (Exception ex) {
            log.error("Failed to get current login id: {}", ex.getMessage());
            return null;
        }
    }
}
