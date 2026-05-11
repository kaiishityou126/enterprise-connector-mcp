package com.sea.star.ai.ec.enterprise.connector.infrastructure.cache;

import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.service.SysDictService;
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
 * Redis Pub/Sub 监听：收到字典刷新消息后通知本机 SysDictService 重新加载对应 key。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DictRefreshListener {

    private final SysDictService sysDictService;

    @Bean
    public RedisMessageListenerContainer dictRefreshListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                messageListener(),
                new PatternTopic(BusinessConstants.CHANNEL_DICT_REFRESH));
        return container;
    }

    private MessageListener messageListener() {
        return (Message message, byte[] pattern) -> {
            String key = new String(message.getBody(), StandardCharsets.UTF_8);
            log.info("收到字典刷新消息 key={}", key);
            sysDictService.refresh(key);
        };
    }
}
