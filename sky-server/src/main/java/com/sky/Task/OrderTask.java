//原订单超时处理方案，运用spring task
//package com.sky.Task;
//
//import com.sky.entity.Orders;
//import com.sky.mapper.OrderMapper;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//
//import java.time.LocalDateTime;
//import java.util.Collections;
//import java.util.List;
//
//@Component
//@Slf4j
//public class OrderTask {
//
//    @Autowired
//    private OrderMapper orderMapper;
//
//    /**
//     * 定时任务
//     * 每一分钟 检查一次订单状态，判断是否完成支付
//     */
//    @Scheduled(cron = "0 0/1 * * * ?")
//    public void processOrder(){
//        log.info("开始处理超时订单");
//        // 查询状态为“支付中”的订单，且订单支付时间在当前时间之前减15分钟
//        // 获取当前时间
//        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);
//        List<Orders> ordersList = orderMapper.getByStatusAndOrderTime(Orders.PENDING_PAYMENT, time);
//        if(ordersList != null && ordersList.size() > 0) {
//            for (Orders orders : ordersList) {
//                log.info("处理支付超时订单：{}", orders.getId());
//                orders.setStatus(Orders.CANCELLED);
//                orders.setCancelReason("支付超时，取消订单");
//                orders.setCancelTime(LocalDateTime.now());
//                orderMapper.update(orders);
//            }
//        }
//        log.debug("处理超时订单完成");// debug级别日志
//    }
//
//    /**
//     * 定时任务
//     * 每隔十分钟检查派送中的订单，超时5小时自动确认送达
//     */
//    @Scheduled(cron = "0 0/10 * * * ?")
//    public void processDeliveryOrder(){
//        log.info("开始处理派送中的订单");
//        LocalDateTime time = LocalDateTime.now().plusHours(-5);
//        List<Orders> ordersList = orderMapper.getByStatusAndOrderTime(Orders.DELIVERY_IN_PROGRESS, time);
//        if(ordersList != null && ordersList.size() > 0) {
//            for (Orders orders : ordersList) {
//                log.info("处理订单：{}", orders.getId());
//                orders.setStatus(Orders.COMPLETED);
//                orders.setDeliveryTime(LocalDateTime.now());
//                orderMapper.update(orders);
//            }
//        }
//        log.debug("处理派送中的订单完成");
//    }
//
//}
