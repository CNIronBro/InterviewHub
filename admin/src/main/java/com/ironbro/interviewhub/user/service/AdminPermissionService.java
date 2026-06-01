package com.ironbro.interviewhub.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ironbro.interviewhub.user.dao.entity.AdminPermission;

public interface AdminPermissionService extends IService<AdminPermission> {

    /**
     * 检查用户是否为管理员
     */
    Boolean isAdmin(String username);

    /**
     * 根据用户名设置用户为管理员
     */
    void setAdminByUserId(String username);
}
