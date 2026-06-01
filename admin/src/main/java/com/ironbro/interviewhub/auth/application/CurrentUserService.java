package com.ironbro.interviewhub.auth.application;

import com.ironbro.interviewhub.auth.domain.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;

public interface CurrentUserService {

    boolean isLoggedIn();

    String getCurrentUsername();

    Long getCurrentUserId();

    CurrentPrincipal getCurrentPrincipal();

    CurrentPrincipal requireCurrentPrincipal();

    Long getUserIdByUsername(String username);

    String getUsernameByToken(String token);

    String extractToken(HttpServletRequest request);

    boolean isValidToken(String token);
}
