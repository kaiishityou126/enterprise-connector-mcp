package com.sea.star.ai.ec.enterprise.connector.domain.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccessTypeTest {

    @Test
    @DisplayName("枚举值: 一次扩到 5 值 (POSTGRES/MYSQL/ORACLE/SQLSERVER/API), 旧 'DB' 值应已被迁移移除")
    void enumValues() {
        assertThat(AccessType.values())
                .containsExactly(AccessType.POSTGRES, AccessType.MYSQL, AccessType.ORACLE,
                        AccessType.SQLSERVER, AccessType.API);
    }

    @Test
    @DisplayName("isDb(): 4 个 DB 方言都返回 true, 只有 API 返回 false")
    void isDb() {
        assertThat(AccessType.POSTGRES.isDb()).isTrue();
        assertThat(AccessType.MYSQL.isDb()).isTrue();
        assertThat(AccessType.ORACLE.isDb()).isTrue();
        assertThat(AccessType.SQLSERVER.isDb()).isTrue();
        assertThat(AccessType.API.isDb()).isFalse();
    }
}
