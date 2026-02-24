package com.sky.Listener;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
@Slf4j
public class OrderTimeoutListener {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 订单支付超时第一道关卡：监听 10 秒死信队列
     */
    @RabbitListener(queues = "order.process.10s.queue")
    public void process10sTimeout(Long orderId) {
        log.info("【10秒检测】收到订单 {} 的检测通知", orderId);

        // 1. 去数据库查这个订单此时此刻的最新状态
        Orders orders = orderMapper.getById(orderId);

        // 2. 幂等性防御：订单必须存在，且依然是“待支付”状态 (status = 1)
        if (orders != null && orders.getStatus().equals(Orders.PENDING_PAYMENT)) {
            log.info("【10秒检测】订单 {} 仍未支付，将其打入 14分50秒 大延迟队列", orderId);

            // 3. 没付钱？扔进第二条流水线！让他再睡 890 秒
            rabbitTemplate.convertAndSend("order.direct", "order.delay.14m", orderId);
        } else {
            // 如果用户在这10秒内光速付完款了，状态已经变成了“已支付”，这里直接跳过，啥也不干
            log.info("【10秒检测】订单 {} 已支付或状态改变，终止追踪", orderId);
        }
    }

    /**
     * 订单支付超时第二道关卡：监听 14分50秒 最终死信队列 (加上前面的10秒，刚好15分钟)
     */
    @RabbitListener(queues = "order.process.14m.queue")
    public void process14mTimeout(Long orderId) {
        log.info("【最终检测】收到订单 {}，准备执行最终判决", orderId);

        // 1. 再次去查数据库最新状态
        Orders orders = orderMapper.getById(orderId);

        // 2. 最后一次判断：过了15分钟了，还是待支付吗？
        if (orders != null && orders.getStatus().equals(Orders.PENDING_PAYMENT)) {
            log.info("【最终检测】订单 {} 满15分钟未支付，执行自动取消！", orderId);

            // 3. 执行苍穹外卖标准的取消逻辑
            orders.setStatus(Orders.CANCELLED);
            orders.setCancelReason("支付超时，系统自动取消");
            orders.setCancelTime(LocalDateTime.now());
            orderMapper.update(orders);

            // 注：如果是真实企业项目，这里可能还要去调用微信支付的关闭订单API，以及恢复商品库存

        } else {
            log.info("【最终检测】订单 {} 已支付或已处理，不执行取消", orderId);
        }
    }

    /**
     * 订单派送超时第一道关卡：监听60分钟死信队列
     */
    @RabbitListener(queues = "order.process.60m.queue")
    public void process60mTimeout(Long orderId) {
        log.info("【60分钟检测】收到订单 {} 的检测通知", orderId);

        // 1. 去数据库查这个订单此时此刻的最新状态
        Orders orders = orderMapper.getById(orderId);

        // 2. 幂等性防御：订单必须存在，且依然是“待派送”状态 (status = 4)
        if (orders != null && orders.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            log.info("【60分钟检测】订单 {} 仍未派送，将其打入 23小时 大延迟队列", orderId);
            rabbitTemplate.convertAndSend("order.direct", "order.delay.23h", orderId);

        } else {
            // 如果用户在这60分钟内光速派送了，状态已经变成了“已派送”，这里直接跳过，啥也不干
            log.info("【60分钟检测】订单 {} 已派送或状态改变，终止追踪", orderId);
        }
    }

    /**
     * 订单派送超时第二道关卡：监听23小时 终死信队列 (加上前面的60分钟，刚好24小时)
     */
    @RabbitListener(queues = "order.process.23h.queue")
    public void process23hTimeout(Long orderId) {
        log.info("【最终检测】收到订单 {}，准备执行最终判决", orderId);

        // 1. 获取订单最新状态
        Orders orders = orderMapper.getById(orderId);

        // 2. 最后一次判断：过了24小时了，还是待派送吗？
        if (orders != null && orders.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            log.info("【最终检测】订单 {} 满24小时未派送，执行自动取消！", orderId);

            // 3. 执行苍穹外卖标准的取消逻辑
            orders.setStatus(Orders.CANCELLED);
            orders.setCancelReason("派送超时，系统自动取消");
            orders.setCancelTime(LocalDateTime.now());
            orderMapper.update(orders);

        }
        else {
            log.info("【最终检测】订单 {} 已派送或已处理，不执行取消", orderId);
        }
    }
}