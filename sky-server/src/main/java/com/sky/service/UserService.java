package com.sky.service;


import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import org.apache.ibatis.annotations.Param;

public interface UserService {
    /**
     * 移动端用户登录
     * @param userLoginDTO
     * @return
     */
    User wxlogin( UserLoginDTO userLoginDTO);
}
