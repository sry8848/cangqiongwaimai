package com.sky.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
public class Redisconfiguraton { // 建议类名改为 RedisConfiguration (修正拼写)

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        log.info("开始创建RedisTemplate对象...");
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();

        // 1. 设置连接工厂
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        // 2. 创建 Key 的序列化器 (String)
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        // 3. 创建 Value 的序列化器 (Jackson) -- 🟥 核心修改区域 🟥
        ObjectMapper objectMapper = new ObjectMapper();

        // (A) 注册 JavaTimeModule，解决 LocalDateTime 序列化报错的问题
        objectMapper.registerModule(new JavaTimeModule());

        // (B) 设置可见性，允许 Jackson 访问私有属性
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // (C) 开启类型识别，解决反序列化后变成 LinkedHashMap 的问题
        // (这行代码代替了旧版本的 enableDefaultTyping)
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL
        );

        // (D) 将配置好的 ObjectMapper 塞给序列化器
        Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
        jackson2JsonRedisSerializer.setObjectMapper(objectMapper);

        // 4. 设置序列化规则
        redisTemplate.setKeySerializer(stringRedisSerializer);
        redisTemplate.setValueSerializer(jackson2JsonRedisSerializer);

        redisTemplate.setHashKeySerializer(stringRedisSerializer);
        redisTemplate.setHashValueSerializer(jackson2JsonRedisSerializer);

        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }
}