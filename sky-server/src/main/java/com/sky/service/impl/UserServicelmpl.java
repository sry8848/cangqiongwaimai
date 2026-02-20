package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.LoginFailedException;
import com.sky.mapper.UserMapper;
import com.sky.properties.WeChatProperties;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class UserServicelmpl implements UserService {

    private static final String URL = "https://api.weixin.qq.com/sns/jscode2session";

    @Autowired
    private WeChatProperties weChatProperties;
    @Autowired
    private UserMapper userMapper;
    /**
     * 微信登录
     * @param userLoginDTO
     * @return
     */
    @Override
    public User wxlogin(UserLoginDTO userLoginDTO) {
        // 创建发送请求
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("appid",weChatProperties.getAppid() );
        paramMap.put("secret", weChatProperties.getSecret());
        paramMap.put("js_code", userLoginDTO.getCode());
        paramMap.put("grant_type", "authorization_code");
        String  json = HttpClientUtil.doGet(URL, paramMap);
        // 解析json
        JSONObject jsonObject = JSON.parseObject(json);
        System.out.println("微信接口返回结果: " + json);
        String openid = jsonObject.getString("openid");

        //判断openid是否为空
        if(openid == null){
            String errmsg = jsonObject.getString("errmsg");
            String errcode = jsonObject.getString("errcode");
            // 打印日志，方便后台看
            System.out.println("微信登录失败，错误码：" + errcode + "，错误信息：" + errmsg);

            // 抛出异常告诉前端（把 errmsg 放进去，前端能看到具体原因）
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED + ": " + errmsg);
        }

        //判断用户是否存在
        User user = userMapper.getByOpenid(openid);
        if(user == null){
            //用户不存在，创建新用户
            user = User.builder()
                    .openid(openid)
                    .createTime(LocalDateTime.now())
                    .build();
            userMapper.insert(user);
        }

        return user;

    }
}
