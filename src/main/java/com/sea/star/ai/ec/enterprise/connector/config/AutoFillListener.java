package com.sea.star.ai.ec.enterprise.connector.config;

import com.mybatisflex.annotation.InsertListener;
import com.mybatisflex.annotation.UpdateListener;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.AsyncTask;
import com.sea.star.ai.ec.enterprise.connector.domain.model.AuditLog;
import com.sea.star.ai.ec.enterprise.connector.domain.model.SysDict;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.spring.boot.MyBatisFlexCustomizer;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Configuration;

/**
 * 对齐原 MP MetaObjectHandler 行为:
 *   - INSERT: createdAt / updatedAt 为空时填 now()
 *   - UPDATE: updatedAt 填 now()
 *
 * Flex 的监听器是 per-entity 注册, 这里统一实现后在 @PostConstruct 一次性注册到所有有这两个字段的实体。
 */
@Configuration
public class AutoFillListener implements MyBatisFlexCustomizer {

    private static final InsertListener INSERT = entity -> {
        LocalDateTime now = LocalDateTime.now();
        setIfNull(entity, "createdAt", now);
        setIfNull(entity, "updatedAt", now);
    };

    private static final UpdateListener UPDATE = entity -> set(entity, "updatedAt", LocalDateTime.now());

    /** TenantActionConfig 没有 createdAt, 只填 grantedAt (授权时间) */
    private static final InsertListener INSERT_GRANTED = entity -> setIfNull(entity, "grantedAt", LocalDateTime.now());

    @Override
    public void customize(FlexGlobalConfig globalConfig) {
        // 软删字段是 BOOLEAN 类型 (deleted). Flex 默认用 0/1 (INT) 绑定 logic-delete 值,
        // 在 PG 下 BOOLEAN 列对 INT 没有隐式转换, 会炸 "operator does not exist: boolean = integer".
        // 这里改成用 Boolean true/false 绑定, 匹配我们的列类型.
        globalConfig.setNormalValueOfLogicDelete(Boolean.FALSE);
        globalConfig.setDeletedValueOfLogicDelete(Boolean.TRUE);

        // 需要自动填充 createdAt/updatedAt 的实体
        Class<?>[] entitiesWithBothTimestamps = {
                TenantConfig.class,
                TenantDatasource.class,
                ActionTemplate.class,
                SysDict.class
        };
        for (Class<?> cls : entitiesWithBothTimestamps) {
            globalConfig.registerInsertListener(INSERT, cls);
            globalConfig.registerUpdateListener(UPDATE, cls);
        }
        // 只有 createdAt (AuditLog, AsyncTask)
        Class<?>[] entitiesWithCreatedOnly = {
                AuditLog.class,
                AsyncTask.class
        };
        for (Class<?> cls : entitiesWithCreatedOnly) {
            globalConfig.registerInsertListener(INSERT, cls);
        }
        // TenantActionConfig: 只填 grantedAt, 不填 createdAt/updatedAt (表里没有这两列)
        globalConfig.registerInsertListener(INSERT_GRANTED, TenantActionConfig.class);
    }

    @PostConstruct
    void ensureRegistered() {
        // @Configuration + MyBatisFlexCustomizer: Flex auto-config 会自动扫描并调用 customize();
        // 此方法留作钩子, 便于日志打印或未来扩展
    }

    // ---- 反射工具 (避免每个实体写 setter 分支) ----
    private static void setIfNull(Object entity, String fieldName, Object value) {
        try {
            Field f = findField(entity.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            if (f.get(entity) == null) f.set(entity, value);
        } catch (Exception ignored) {
            // 字段不存在 / 访问失败, 静默忽略
        }
    }

    private static void set(Object entity, String fieldName, Object value) {
        try {
            Field f = findField(entity.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.set(entity, value);
        } catch (Exception ignored) {
        }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}
