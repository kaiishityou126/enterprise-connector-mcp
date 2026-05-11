package com.sea.star.ai.ec.enterprise.connector.service;

import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.SysDictMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.SysDict;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统字典服务：启动时全量加载到内存，运行时热更新（Admin API 触发 + Redis Pub/Sub 广播）。
 *
 * 读取永远走内存 ConcurrentHashMap；写入先 DB 后内存，再发布 Pub/Sub 消息通知其他实例刷新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysDictService {

    private final SysDictMapper sysDictMapper;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void loadAll() {
        try {
            List<SysDict> list = sysDictMapper.selectAll();
            for (SysDict d : list) {
                cache.put(d.getDictKey(), d.getDictValue());
            }
            log.info("SysDict 加载完成, 共 {} 项", list.size());
        } catch (Exception e) {
            // 启动降级：DB 不可用时以空字典启动，所有 get 方法走兜底默认值。
            // 待 DB 恢复后可通过 Admin API 或重启加载。
            log.warn("SysDict 启动加载失败, 将以空字典启动, 所有读取走兜底默认值", e);
        }
    }

    public int getInt(String key, int defaultValue) {
        String val = cache.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            log.warn("SysDict key={} 值非整数: {}", key, val);
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String val = cache.get(key);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            log.warn("SysDict key={} 值非长整数: {}", key, val);
            return defaultValue;
        }
    }

    public String getString(String key, String defaultValue) {
        String val = cache.get(key);
        return val != null ? val : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = cache.get(key);
        return val != null ? Boolean.parseBoolean(val) : defaultValue;
    }

    /**
     * 更新字典项：DB → 本机内存 → Pub/Sub 广播给其他实例
     */
    @Transactional
    public void update(String key, String value) {
        SysDict entity = sysDictMapper.selectOneById(key);
        if (entity == null) {
            // 用业务异常让 GlobalExceptionHandler 返回 400 而不是 500
            throw new BusinessException(ErrorCode.PARAM_INVALID, "字典项不存在: " + key);
        }
        entity.setDictValue(value);
        sysDictMapper.update(entity);
        cache.put(key, value);
        publish(key);
    }

    /**
     * 从 DB 重新加载单条 key（用于 Pub/Sub 监听时）
     */
    public void refresh(String key) {
        SysDict dict = sysDictMapper.selectOneById(key);
        if (dict != null) {
            cache.put(key, dict.getDictValue());
            log.info("SysDict 已刷新 key={}", key);
        } else {
            cache.remove(key);
            log.info("SysDict 已移除 key={}", key);
        }
    }

    public int size() {
        return cache.size();
    }

    private void publish(String key) {
        if (stringRedisTemplate == null) return;
        try {
            stringRedisTemplate.convertAndSend(BusinessConstants.CHANNEL_DICT_REFRESH, key);
        } catch (Exception e) {
            log.warn("发布字典刷新消息失败 key={}", key, e);
        }
    }
}
