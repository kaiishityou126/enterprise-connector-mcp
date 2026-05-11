package com.sea.star.ai.ec.enterprise.connector.infrastructure.cache;

import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 订阅 Redis channel "cache:invalidate"，收到消息后清除本地 L1 缓存。
 *
 * 消息格式: "{cacheName}|{key}"，例如 "tenantConfig|T001"。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CacheInvalidationListener {

    private final TwoLevelCacheManager twoLevelCacheManager;

    @Bean
    public RedisMessageListenerContainer cacheInvalidationContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                messageListener(),
                new PatternTopic(BusinessConstants.CHANNEL_CACHE_INVALIDATE));
        return container;
    }

    private MessageListener messageListener() {
        return (Message message, byte[] pattern) -> {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            int sep = payload.indexOf('|');
            if (sep <= 0 || sep == payload.length() - 1) {
                // 格式错误意味着一次失效广播失败，本机 L1 不会同步清除，数据可能不一致
                log.error("缓存失效消息格式错误，本机缓存未清除 payload={}", payload);
                return;
            }
            String cacheName = payload.substring(0, sep);
            String key = payload.substring(sep + 1);
            twoLevelCacheManager.evictLocal(cacheName, key);
            log.info("已清除本地缓存 cache={}, key={}", cacheName, key);
        };
    }
}
