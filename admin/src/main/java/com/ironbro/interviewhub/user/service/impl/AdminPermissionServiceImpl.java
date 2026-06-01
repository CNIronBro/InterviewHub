package com.ironbro.interviewhub.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ironbro.interviewhub.common.convention.exception.ServiceException;
import com.ironbro.interviewhub.user.dao.entity.AdminPermission;
import com.ironbro.interviewhub.user.dao.entity.UserDO;
import com.ironbro.interviewhub.user.dao.mapper.AdminPermissionMapper;
import com.ironbro.interviewhub.user.dao.mapper.UserMapper;
import com.ironbro.interviewhub.user.service.AdminPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminPermissionServiceImpl extends ServiceImpl<AdminPermissionMapper, AdminPermission>
        implements AdminPermissionService {

    private final UserMapper userMapper;

    @Override
    public Boolean isAdmin(String username) {
        LambdaQueryWrapper<AdminPermission> queryWrapper = Wrappers.lambdaQuery(AdminPermission.class)
                .eq(AdminPermission::getUsername, username)
                .eq(AdminPermission::getDelFlag, 0);
        AdminPermission adminPermission = getOne(queryWrapper);
        return adminPermission != null && adminPermission.getIsAdmin() != null && adminPermission.getIsAdmin() == 1;
    }

    @Override
    public void setAdminByUserId(String username) {
        LambdaQueryWrapper<UserDO> userQueryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username)
                .eq(UserDO::getDelFlag, 0);
        UserDO user = userMapper.selectOne(userQueryWrapper);
        if (user == null) {
            throw new ServiceException("用户不存在");
        }

        AdminPermission existingPermission = getOne(Wrappers.lambdaQuery(AdminPermission.class)
                .eq(AdminPermission::getUserId, user.getId()));
        if (existingPermission == null) {
            AdminPermission adminPermission = new AdminPermission();
            adminPermission.setUserId(user.getId());
            adminPermission.setUsername(user.getUsername());
            adminPermission.setIsAdmin(1);
            save(adminPermission);
        } else {
            existingPermission.setIsAdmin(1);
            updateById(existingPermission);
        }
    }
}
