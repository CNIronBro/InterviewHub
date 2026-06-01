package com.ironbro.interviewhub.auth.infrastructure.satoken;

import com.ironbro.interviewhub.auth.application.PermissionService;
import com.ironbro.interviewhub.user.service.AdminPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SaTokenPermissionService implements PermissionService {

    private final AdminPermissionService adminPermissionService;

    @Override
    public boolean isAdmin(String username) {
        return adminPermissionService.isAdmin(username);
    }
}
