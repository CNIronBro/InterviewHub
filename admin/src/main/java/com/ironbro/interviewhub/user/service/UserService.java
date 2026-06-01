package com.ironbro.interviewhub.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ironbro.interviewhub.user.api.io.req.UserLoginReqDTO;
import com.ironbro.interviewhub.user.api.io.req.UserRegisterReqDTO;
import com.ironbro.interviewhub.user.api.io.resp.UserLoginRespDTO;
import com.ironbro.interviewhub.user.api.io.resp.UserRespDTO;
import com.ironbro.interviewhub.user.dao.entity.UserDO;

/**
 * 用户接口层
 */
public interface UserService extends IService<UserDO> {

    /**
     * 根据用户名查询用户信息
     */
    UserRespDTO getUserByUsername(String username);

    /**
     * 查询用户名是否存在
     */
    Boolean hasUsername(String username);

    /**
     * 注册用户
     */
    void register(UserRegisterReqDTO requestParam);

    /**
     * 用户登录
     */
    UserLoginRespDTO login(UserLoginReqDTO requestParam);

    /**
     * 检查用户是否登录
     */
    Boolean checkLogin(String username, String token);

    /**
     * 退出登录
     */
    void logout(String username, String token);
}
