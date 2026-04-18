package com.acp.cw3.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

@Service
public class RedisCacheService {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public <T> T get(String key, Class<T> type) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException ignored) {
        }
    }

    public void evictByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
