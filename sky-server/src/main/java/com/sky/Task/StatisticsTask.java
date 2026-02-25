package com.sky.Task;

import com.sky.constant.RedisConstant; // 假设你把 user:new: 等常量放在这里
import com.sky.dto.UserStatDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class StatisticsTask {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 每天凌晨4点执行
     * 作用：统计“昨天”的营业额和用户数据，校准并写入 Redis
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void processYesterdayStatistics() {
        log.info("开始执行昨日数据统计校准任务...");

        // 1. 获取昨天的日期范围 (例如：今天24号，算的是23号的全天数据)
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String dateStr = yesterday.toString();

        LocalDateTime beginTime = LocalDateTime.of(yesterday, LocalTime.MIN); // 昨天 00:00:00
        LocalDateTime endTime = LocalDateTime.of(yesterday, LocalTime.MAX);   // 昨天 23:59:59


        // ------------------ 2. 校准新增用户 (New Users) ------------------
        // SQL: select count(id) from user where create_time between ? and ?

        List<UserStatDTO> newUsers = userMapper.getDateCountByDateRange(beginTime, endTime);
        Long yesterdayNewUsers = newUsers.get(0).getCount();
        // 写入 Redis
        stringRedisTemplate.opsForValue().set(
                RedisConstant.USER_NEW_KEY + dateStr,
                String.valueOf(newUsers),
                30, TimeUnit.DAYS
        );


        // ------------------ 3. 校准总用户数 (Total Users) ------------------
        // 注意：总用户数是截止到“昨天结束”的累计值
        // SQL: select count(id) from user where create_time <= ?
        LocalDate today = LocalDate.now();

        Long totalUsers = userMapper.countBeforeDate(LocalDateTime.of(today, LocalTime.MIN));

        // 写入 Redis：昨天的快照
        stringRedisTemplate.opsForValue().set(
                RedisConstant.USER_TOTAL_KEY + dateStr,
                String.valueOf(totalUsers),
                30, TimeUnit.DAYS
        );

        // 【关键一步】：顺手更新一下“全局当前总数”，消除白天可能产生的累积误差
        List<UserStatDTO> todayNewUsers = userMapper.getDateCountByDateRange(LocalDateTime.of(today, LocalTime.MIN), LocalDateTime.now());
        stringRedisTemplate.opsForValue().set(
                RedisConstant.USER_TOTAL_CURRENT,
                String.valueOf(totalUsers + todayNewUsers.get(0).getCount()),
                30, TimeUnit.DAYS
        );

        log.info("校准用户统计完成: Date={}, New={}, Total={}", dateStr, newUsers, totalUsers);
    }
}