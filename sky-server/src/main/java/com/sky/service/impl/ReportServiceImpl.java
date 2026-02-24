package com.sky.service.impl;

import com.sky.constant.RedisConstant;
import com.sky.dto.TurnoverStatDTO;
import com.sky.dto.UserStatDTO;
import com.sky.service.ReportService;
import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private RedisTemplate<String, String> stringRedisTemplate;

    /**
     * 统计指定时间区间内的营业额数据
     *
     * @param begin
     * @param end
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        // 1. 准备日期列表
        List<LocalDate> dateList = new ArrayList<>();

        // 【修复1】使用临时变量 tempDate 跑循环，绝对不要修改入参 begin！
        LocalDate tempDate = begin;
        while (!tempDate.isAfter(end)) {
            dateList.add(tempDate);
            tempDate = tempDate.plusDays(1);
        }

        Map<String, Object> map = new HashMap<>();
        map.put("begin", LocalDateTime.of( begin, LocalTime.MIN));
        map.put("end", LocalDateTime.of( end, LocalTime.MAX));
        map.put("status", Orders.COMPLETED);

        // 2. 查数据库，直接拿 List<DTO>
        List<TurnoverStatDTO> statList = orderMapper.getTurnoverStatistics(map);

        // 3. 将 List<DTO> 转为 Map<日期, 金额>，方便后续查找
        // 这一步比处理 Map<String, Object> 爽多了，有代码提示，不用担心 key 拼错
        Map<LocalDate, Double> dataMap = statList.stream()
                .collect(Collectors.toMap(TurnoverStatDTO::getDate, TurnoverStatDTO::getAmount));

        // 4. 填充最终数据
        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate date : dateList) {
            // 类型安全，不用强转，不用判空
            Double amount = dataMap.getOrDefault(date, 0.0);
            turnoverList.add(amount);
        }

        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .turnoverList(StringUtils.join(turnoverList, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的用户数据
     * 进行了redis缓存优化，缓存时间30天
     *
     * @param begin
     * @param end
     * @return
     */
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        // 1. 存放从 begin 到 end 之间的每天对应的日期
        List<LocalDate> dateList = new ArrayList<>();

        // 【修复 Bug 2】：使用临时变量 tempDate 循环，保护原始入参 begin 不被污染
        LocalDate tempDate = begin;
        dateList.add(tempDate);
        while (!tempDate.equals(end)) {
            tempDate = tempDate.plusDays(1);
            dateList.add(tempDate);
        }

        // 2. 建立 redis 键集合
        List<String> incrseKeyList = dateList.stream()
                .map(date -> RedisConstant.USER_NEW_KEY + date)
                .collect(Collectors.toList());
        List<String> totalKeyList = dateList.stream()
                .map(date -> RedisConstant.USER_TOTAL_KEY + date)
                .collect(Collectors.toList());

        // 3. 遍历获取 redis 中的数据
        List<String> increaseList = stringRedisTemplate.opsForValue().multiGet(incrseKeyList);
        List<String> totalList = stringRedisTemplate.opsForValue().multiGet(totalKeyList);

        // 4. 创建两个数组，用于存放最终结果
        Long[] finalNewUsers = new Long[dateList.size()];
        Long[] finalTotalUsers = new Long[dateList.size()];

        // 创建未命中键范围参数
        LocalDate minMissDate = null;
        LocalDate maxMissDate = null;

        // 5. 循环检查是否命中
        for (int i = 0; i < dateList.size(); i++) {
            LocalDate date = dateList.get(i);

            // 检查新增数据是否命中
            if (increaseList.get(i) == null) {
                minMissDate = minMissDate == null ? date : minMissDate;
                maxMissDate = date;
            } else {
                finalNewUsers[i] = Long.valueOf(increaseList.get(i));
            }

            // 检查总数数据是否命中
            //如果是 LocalDate.now()，优先去读全局 Key 会更快一点
            if (date.equals(LocalDate.now())) {
                String totalUserstr = stringRedisTemplate.opsForValue().get(RedisConstant.USER_TOTAL_CURRENT);
                if(totalUserstr != null){
                    finalTotalUsers[i] = Long.valueOf(totalUserstr);
                }
            }
            if (totalList.get(i) == null) {
                minMissDate = minMissDate == null ? date : minMissDate;
                maxMissDate = date;
            } else {
                finalTotalUsers[i] = Long.valueOf(totalList.get(i));
            }
        }

        // 6. 兜底逻辑：处理 Cache Miss
        if (minMissDate != null && maxMissDate != null) {
            // 【修复 Bug 1】：把时间对象的创建移到判空里面，防止全命中时抛出 NPE
            LocalDateTime beginTime = LocalDateTime.of(minMissDate, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(maxMissDate, LocalTime.MAX);

            List<UserStatDTO> missIncreaseList = userMapper.getDateCountByDateRange(beginTime, endTime);
            Map<LocalDate, Long> missMap = missIncreaseList.stream()
                    .collect(Collectors.toMap(UserStatDTO::getDate, UserStatDTO::getCount));

            Long baseTotal = userMapper.countBeforeDate(beginTime);
            // 防空处理
            baseTotal = baseTotal == null ? 0L : baseTotal;

            // 【修复 Bug 3】：正确的累加推算逻辑
            Long currentTotal = baseTotal;
            LocalDate checkDate = minMissDate;

            while (!checkDate.isAfter(maxMissDate)) {
                Long dailyNew = missMap.getOrDefault(checkDate, 0L); // 当天新增用户数

                // 核心修复：把昨天总数加上今天新增，变成今天总数，继续往下滚
                currentTotal += dailyNew;
                Long runningTotal = currentTotal;

                // 注意：这里的 begin 是最开始传进来的原始参数，现在它没被污染，能算出正确的 index
                int index = (int) ChronoUnit.DAYS.between(begin, checkDate);

                if (index >= 0 && index < dateList.size()) {
                    // 填补新增并回写
                    if (finalNewUsers[index] == null) {
                        finalNewUsers[index] = dailyNew;
                        stringRedisTemplate.opsForValue().set(RedisConstant.USER_NEW_KEY + checkDate, String.valueOf(dailyNew), 30, TimeUnit.DAYS);
                    }
                    // 填补总数并回写
                    if (finalTotalUsers[index] == null) {
                        finalTotalUsers[index] = runningTotal;
                        if (checkDate.equals(LocalDate.now())) {
                            stringRedisTemplate.opsForValue().set(RedisConstant.USER_TOTAL_CURRENT, String.valueOf(runningTotal), 1, TimeUnit.HOURS);
                        } else {
                            stringRedisTemplate.opsForValue().set(RedisConstant.USER_TOTAL_KEY + checkDate, String.valueOf(runningTotal), 30, TimeUnit.DAYS);
                        }
                    }
                }
                checkDate = checkDate.plusDays(1);
            }
        } // 【修复语法】：补上缺失的右大括号

        // 7. 封装结果数据
        return UserReportVO
                .builder()
                .dateList(StringUtils.join(dateList, ","))
                // 注意：为了跟前端对应，如果用 Arrays.asList 包装一下数组，join 拼接会更稳妥
                .totalUserList(StringUtils.join(Arrays.asList(finalTotalUsers), ","))
                .newUserList(StringUtils.join(Arrays.asList(finalNewUsers), ","))
                .build();
    }


    /**
     * 统计指定时间区间内的订单数据
     * @param begin
     * @param end
     * @return
     */
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = new ArrayList<>();

        LocalDate tempDate = begin;
        dateList.add(tempDate);

        while (!tempDate.equals(end)) {
            tempDate = tempDate.plusDays(1);
            dateList.add(tempDate);
        }

        //存放每天的订单总数
        List<Integer> orderCountList = new ArrayList<>();
        //存放每天的有效订单数
        List<Integer> validOrderCountList = new ArrayList<>();

        //遍历dateList集合，查询每天的有效订单数和订单总数
        for (LocalDate date : dateList) {
            //查询每天的订单总数 select count(id) from orders where order_time > ? and order_time < ?
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);
            Integer orderCount = getOrderCount(beginTime, endTime, null);

            //查询每天的有效订单数 select count(id) from orders where order_time > ? and order_time < ? and status = 5
            Integer validOrderCount = getOrderCount(beginTime, endTime, Orders.COMPLETED);

            orderCountList.add(orderCount);
            validOrderCountList.add(validOrderCount);
        }

        //计算时间区间内的订单总数量
        Integer totalOrderCount = orderCountList.stream().reduce(Integer::sum).get();

        //计算时间区间内的有效订单数量
        Integer validOrderCount = validOrderCountList.stream().reduce(Integer::sum).get();

        Double orderCompletionRate = 0.0;
        if(totalOrderCount != 0){
            //计算订单完成率
            orderCompletionRate = validOrderCount.doubleValue() / totalOrderCount;
        }

        return  OrderReportVO.builder()
                .dateList(StringUtils.join(dateList,","))
                .orderCountList(StringUtils.join(orderCountList,","))
                .validOrderCountList(StringUtils.join(validOrderCountList,","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }

    /**
     * 根据条件统计订单数量
     * @param begin
     * @param end
     * @param status
     * @return
     */
    private Integer getOrderCount(LocalDateTime begin, LocalDateTime end, Integer status){
        Map map = new HashMap();
        map.put("begin",begin);
        map.put("end",end);
        map.put("status",status);

        return orderMapper.countByMap(map);
    }

    /**
     * 获取销量前10的统计报表
     * 逻辑：优先从 Redis 的 ZSet 中通过多日并集运算获取，若 Redis 异常则降级查询数据库。
     *
     * @param begin 开始日期
     * @param end   结束日期
     * @return 包含商品名称列表和销量列表的 VO 对象
     */
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        // 1. 生成日期列表，用于拼接 Redis 的 Key
        List<LocalDate> dateList = new ArrayList<>();
        LocalDate tempDate = begin;
        while (!tempDate.isAfter(end)) {
            dateList.add(tempDate);
            tempDate = tempDate.plusDays(1); // 逐日递增
        }

        // 2. 准备 Redis 临时 Key，用于存储多日聚合后的并集结果
        // 使用时间戳防止并发请求下的 Key 冲突
        String tempKey = "temp:top10:" + System.currentTimeMillis();
        
        // 3. 构建需要合并的所有每日销量 Key 列表 (例如: sales:2023-10-01, sales:2023-10-02...)
        List<String> zsetKeys = dateList.stream()
                .map(date -> RedisConstant.SALES_KEY + date)
                .collect(Collectors.toList());

        try {
            // 4. 执行 ZSet 并集运算 (ZUNIONSTORE)
            // 将 zsetKeys 中所有集合的 score（销量）累加，结果存入 tempKey
            stringRedisTemplate.opsForZSet().unionAndStore(tempKey, zsetKeys, tempKey);

            // 5. 从聚合后的有序集合中获取前 10 名
            // reverseRangeWithScores 表示按 score 从大到小排序，取索引 0-9
            Set<ZSetOperations.TypedTuple<String>> top10 =
                    stringRedisTemplate.opsForZSet().reverseRangeWithScores(tempKey, 0, 9);

            List<String> names = new ArrayList<>();
            List<Integer> numbers = new ArrayList<>();

            // 6. 解析并格式化结果
            if (top10 != null && !top10.isEmpty()) {
                for (ZSetOperations.TypedTuple<String> tuple : top10) {
                    names.add(tuple.getValue());                  // 商品名称
                    numbers.add(tuple.getScore().intValue());     // 累计销量
                }
            }

            // 7. 返回封装后的数据，将 List 转换为逗号分隔的字符串（适配前端图表）
            return SalesTop10ReportVO.builder()
                    .nameList(StringUtils.join(names, ","))
                    .numberList(StringUtils.join(numbers, ","))
                    .build();

        } catch (Exception e) {
            // 8. 异常处理：如果 Redis 操作失败（如连接失败或超时），记录警告并执行数据库降级方案
            log.warn("Redis ZSET 查询失败，降级到数据库查询: {}", e.getMessage());
            return getSalesTop10FromDB(begin, end);
        } finally {
            // 9. 资源清理：无论成功或失败，必须删除 Redis 临时 Key 释放内存
            stringRedisTemplate.delete(tempKey);
        }
    }

    /**
     * 数据库兜底查询逻辑：从订单表统计指定时间段内的销量前10
     */
    private SalesTop10ReportVO getSalesTop10FromDB(LocalDate begin, LocalDate end) {
        // 将日期转换为当天的开始和结束时刻 (00:00:00.000 ~ 23:59:59.999)
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);

        // 调用 Mapper 执行 SQL 聚合查询 (GROUP BY 商品名 ORDER BY SUM(销量) DESC LIMIT 10)
        List<GoodsSalesDTO> salesTop10 = orderMapper.getSalesTop10(beginTime, endTime);
        
        // 提取名称列表
        List<String> names = salesTop10.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList());
        String nameList = StringUtils.join(names, ",");

        // 提取数量列表
        List<Integer> numbers = salesTop10.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList());
        String numberList = StringUtils.join(numbers, ",");

        // 封装并返回结果
        return SalesTop10ReportVO.builder()
                .nameList(nameList)
                .numberList(numberList)
                .build();
    }
}
