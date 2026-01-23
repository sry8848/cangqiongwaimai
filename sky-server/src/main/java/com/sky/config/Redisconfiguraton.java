package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
public class Redisconfiguraton {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        log.info("开始创建RedisTemplate对象...");
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();

        // 1. 设置连接工厂
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        // 2. 创建序列化器
        // String 序列化器 (用于 Key)
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        // JSON 序列化器 (用于 Value，支持存对象)
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        // 3. --- 配置普通 String 操作 ---
        redisTemplate.setKeySerializer(stringSerializer);     // key采用String
        redisTemplate.setValueSerializer(jsonSerializer);     // value采用JSON

        // 4. --- 🟥 重点：配置 Hash 操作 🟥 ---
        redisTemplate.setHashKeySerializer(stringSerializer); // Hash里的字段(Field)采用String
        redisTemplate.setHashValueSerializer(jsonSerializer); // Hash里的值(Value)采用JSON

        return redisTemplate;
    }
}