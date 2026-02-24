package com.sky.constant;

public class RedisConstant {
    // 每日新增用户 Key 前缀，后面接日期：user:new:2026-02-23
    public static final String USER_NEW_KEY = "user:new:";
    // 每日总用户快照 Key 前缀，后面接日期：user:total:2026-02-23
    public static final String USER_TOTAL_KEY = "user:total:";
    // 全局当前总用户量 Key
    public static final String USER_TOTAL_CURRENT = "user:total:current";
}
