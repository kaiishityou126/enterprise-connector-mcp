package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.TenantDatasourceTableDef.TENANT_DATASOURCE;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 复合主键 (tenant_id, ds_name), 按 TenantActionConfigMapper 的方式用 QueryWrapper.
 */
@Mapper
public interface TenantDatasourceMapper extends BaseMapper<TenantDatasource> {

    /** 列出某租户下所有未软删的数据源 */
    default List<TenantDatasource> findByTenantId(String tenantId) {
        return selectListByQuery(QueryWrapper.create()
                .where(TENANT_DATASOURCE.TENANT_ID.eq(tenantId)));
    }

    default TenantDatasource findByTenantAndDs(String tenantId, String dsName) {
        return selectOneByQuery(QueryWrapper.create()
                .where(TENANT_DATASOURCE.TENANT_ID.eq(tenantId))
                .and(TENANT_DATASOURCE.DS_NAME.eq(dsName)));
    }

    default int deleteByTenantAndDs(String tenantId, String dsName) {
        return deleteByQuery(QueryWrapper.create()
                .where(TENANT_DATASOURCE.TENANT_ID.eq(tenantId))
                .and(TENANT_DATASOURCE.DS_NAME.eq(dsName)));
    }
}
