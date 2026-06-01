package com.ironbro.interviewhub.user.api;

import com.ironbro.interviewhub.auth.application.LoginSessionService;
import com.ironbro.interviewhub.auth.application.PermissionService;
import com.ironbro.interviewhub.common.convention.result.Result;
import com.ironbro.interviewhub.common.convention.result.Results;
import com.ironbro.interviewhub.user.api.io.req.UserLoginReqDTO;
import com.ironbro.interviewhub.user.api.io.req.UserRegisterReqDTO;
import com.ironbro.interviewhub.user.api.io.resp.UserRespDTO;
import com.ironbro.interviewhub.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户管理控制器
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final LoginSessionService loginSessionService;
    private final PermissionService permissionService;

    @GetMapping("/{username}")
    public Result<UserRespDTO> getUserByUsername(@PathVariable("username") String username) {
        return Results.success(userService.getUserByUsername(username));
    }

    @GetMapping("/has-username")
    public Result<Boolean> hasUsername(@RequestParam("username") String username) {
        return Results.success(userService.hasUsername(username));
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody UserRegisterReqDTO requestParam) {
        userService.register(requestParam);
        return Results.success();
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody UserLoginReqDTO requestParam) {
        userService.login(requestParam);
        loginSessionService.login(requestParam.getUsername());

        Map<String, Object> result = new HashMap<>();
        result.put("token", loginSessionService.getCurrentToken());
        result.put("username", requestParam.getUsername());
        result.put("isAdmin", permissionService.isAdmin(requestParam.getUsername()));
        return Results.success(result);
    }

    @GetMapping("/check-login")
    public Result<Map<String, Object>> checkLogin() {
        Map<String, Object> result = new HashMap<>();
        boolean isLogin = loginSessionService.isCurrentLoggedIn();
        result.put("isLogin", isLogin);
        if (isLogin) {
            result.put("username", loginSessionService.getCurrentLoginId());
            result.put("token", loginSessionService.getCurrentToken());
        }
        return Results.success(result);
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        loginSessionService.logoutCurrent();
        return Results.success();
    }
}
