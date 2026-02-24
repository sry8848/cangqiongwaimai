package com.sky.Listener;

import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WebSocketListener {

    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 监听当前服务器自己生成的那个匿名队列
     * 注意这里的 SPEL 表达式 `#{webSocketAnonymousQueue.name}`，它会自动动态获取刚才那个随机队列的名字
     */
    @RabbitListener(queues = "#{webSocketAnonymousQueue.name}")
    public void receiveWebSocketMessage(String jsonMessage) {
        log.info("【集群广播接收】收到 WebSocket 推送指令，准备向连接在本机的客户端推送：{}", jsonMessage);

        // 真正调用 WebSocket 的地方，移到了这里！
        webSocketServer.sendToAllClient(jsonMessage);
    }
}