package com.sky.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfiguration {

    // ==========================================
    // 1. 声明普通的交换机和死信交换机
    // ==========================================

    // 业务正常发送消息用的交换机
    @Bean
    public DirectExchange orderDirectExchange() {
        return new DirectExchange("order.direct");
    }

    // 专门用来“收尸”的死信交换机 (Dead Letter Exchange)
    @Bean
    public DirectExchange orderDlxExchange() {
        return new DirectExchange("order.dlx");
    }

    // ==========================================
    // 2. 支付超时第一阶段（10秒延迟队列及其死信处理队列）
    // ==========================================

    // 存放10秒延迟消息的队列（不能有消费者监听它！）
    @Bean
    public Queue delay10sQueue() {
        return QueueBuilder.durable("order.delay.10s.queue")
                .ttl(10000) // 存活时间：10000毫秒 (10秒)
                .deadLetterExchange("order.dlx") // 死了之后交给谁？交给收尸交换机
                .deadLetterRoutingKey("order.dlx.10s") // 死信路由键：贴上10秒的标签
                .build();
    }

    // 将普通交换机与10秒延迟队列绑定
    @Bean
    public Binding delay10sBinding() {
        return BindingBuilder.bind(delay10sQueue()).to(orderDirectExchange()).with("order.delay.10s");
    }

    // 第一阶段真正被程序监听的“死信处理队列”
    @Bean
    public Queue process10sQueue() {
        return new Queue("order.process.10s.queue");
    }

    // 将收尸交换机与第一阶段处理队列绑定
    @Bean
    public Binding process10sBinding() {
        return BindingBuilder.bind(process10sQueue()).to(orderDlxExchange()).with("order.dlx.10s");
    }


    // ==========================================
    // 3. 支付超时第二阶段（14分50秒延迟队列及其死信处理队列）
    // ==========================================

    // 存放14分50秒延迟消息的队列（不能有消费者监听它！）
    @Bean
    public Queue delay14mQueue() {
        return QueueBuilder.durable("order.delay.14m.queue")
                .ttl(890000) // 存活时间：890000毫秒 (14分50秒)
                .deadLetterExchange("order.dlx")
                .deadLetterRoutingKey("order.dlx.14m") // 死信路由键：贴上14分的标签
                .build();
    }

    // 将普通交换机与14分50秒延迟队列绑定
    @Bean
    public Binding delay14mBinding() {
        return BindingBuilder.bind(delay14mQueue()).to(orderDirectExchange()).with("order.delay.14m");
    }

    // 第二阶段真正被程序监听的“最终死信处理队列” (到了这里就彻底超时了)
    @Bean
    public Queue process14mQueue() {
        return new Queue("order.process.14m.queue");
    }

    // 将收尸交换机与第二阶段处理队列绑定
    @Bean
    public Binding process14mBinding() {
        return BindingBuilder.bind(process14mQueue()).to(orderDlxExchange()).with("order.dlx.14m");
    }

    // ==========================================
    // 4. 派送超时第一阶段（60分钟延迟队列及死信处理队列）
    // ==========================================

    /**
     * 存放 60 分钟延迟消息的队列，不能有消费者监听它！
     */
    @Bean
    public Queue delay60mQueue() {
        return QueueBuilder.durable("order.delay.60m.queue")
                .ttl(3600000) // 存活时间：3600000毫秒 (60分钟)
                .deadLetterExchange("order.dlx") // 死了之后依然交给同一个收尸交换机
                .deadLetterRoutingKey("order.dlx.60m") // 贴上 60分钟 的死信标签
                .build();
    }

    /**
     * 将普通交换机与 60 分钟延迟队列绑定
     */
    @Bean
    public Binding delay60mBinding() {
        return BindingBuilder.bind(delay60mQueue()).to(orderDirectExchange()).with("order.delay.60m");
    }

    /**
     * 派送超时的“死信处理队列”（我们的程序要监听这个队列）
     */
    @Bean
    public Queue process60mQueue() {
        return new Queue("order.process.60m.queue");
    }

    /**
     * 将收尸交换机与派送超时的处理队列绑定
     */
    @Bean
    public Binding process60mBinding() {
        return BindingBuilder.bind(process60mQueue()).to(orderDlxExchange()).with("order.dlx.60m");
    }

    // ==========================================
    // 5. 派送超时第二阶段（23小时延迟队列及死信处理队列）
    // ==========================================

    /**
     * 存放 23 小时延迟消息的队列，不能有消费者监听它！
     */
    @Bean
    public Queue delay23hQueue() {
        return QueueBuilder.durable("order.delay.23h.queue")
                .ttl(82800000) // 存活时间：82800000毫秒 (23小时)
                .deadLetterExchange("order.dlx") // 死了之后依然交给同一个收尸交换机
                .deadLetterRoutingKey("order.dlx.23h") // 贴上 23 小时的死信标签
                .build();
    }

    /**
     * 将普通交换机与 23 小时延迟队列绑定
     */
    @Bean
    public Binding delay23hBinding() {
        return BindingBuilder.bind(delay23hQueue()).to(orderDirectExchange()).with("order.delay.23h");
    }

    /**
     * 派送超时的“死信处理队列”（我们的程序要监听这个队列）
     */
    @Bean
    public Queue process23hQueue() {
        return new Queue("order.process.23h.queue");
    }

    /**
     * 将收尸交换机与派送超时的处理队列绑定
     */
    @Bean
    public Binding process23hBinding() {
        return BindingBuilder.bind(process23hQueue()).to(orderDlxExchange()).with("order.dlx.23h");
    }

    // ==========================================
    // 5. WebSocket 集群广播模式 (Fanout)
    // ==========================================

    /**
     * 1. 声明一个广播交换机（大喇叭）
     */
    @Bean
    public FanoutExchange webSocketFanoutExchange() {
        return new FanoutExchange("websocket.fanout");
    }

    /**
     * 2. 声明一个匿名队列（神仙特性：每个启动的 Java 程序会自动生成一个随机名字的队列，断开即销毁）
     */
    @Bean
    public Queue webSocketAnonymousQueue() {
        return new AnonymousQueue();
    }

    /**
     * 3. 把这台服务器的专属匿名队列，绑定到大喇叭上
     * (注意这里参数直接注入了上面定义的 bean)
     */
    @Bean
    public Binding webSocketBinding(Queue webSocketAnonymousQueue, FanoutExchange webSocketFanoutExchange) {
        // Fanout 交换机不需要 RoutingKey，所以没有 .with("xxx")
        return BindingBuilder.bind(webSocketAnonymousQueue).to(webSocketFanoutExchange);
    }
}