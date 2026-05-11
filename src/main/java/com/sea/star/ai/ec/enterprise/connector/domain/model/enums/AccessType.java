package com.sea.star.ai.ec.enterprise.connector.domain.model.enums;

/**
 * 接入方式 + 方言. 一个字段同时表达"打 DB 还是 HTTP"以及"DB 是哪种方言".
 *
 * <p>设计原因: "DB" 这个抽象其实没用——任何 DB 都必须知道方言才能跑 SQL,
 * 合并 access_type 与方言是合理的语义内聚, schema 不必再加 db_type 列.
 *
 * <p>枚举列表:
 * <ul>
 *   <li>POSTGRES / MYSQL / SQLSERVER: 已接入 (pom 有驱动 + smoke IT 覆盖)</li>
 *   <li>ORACLE: schema 接受但 pom 未加 ojdbc11 驱动. 真接入 Oracle 时只补 pom + IT 即可,
 *       不需要 DDL. 提前写入 ORACLE 模板会在 HikariCP 建池时 ClassNotFoundException 显式失败.</li>
 *   <li>API: HTTP API 接入</li>
 * </ul>
 */
public enum AccessType {
    POSTGRES,
    MYSQL,
    ORACLE,
    SQLSERVER,
    API;

    /** DB 家族判定: 后续加方言时只需扩枚举不改判等点. */
    public boolean isDb() {
        return this != API;
    }
}
