package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.TenantActionConfigTableDef.TENANT_ACTION_CONFIG;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 复合主键 (tenant_id, action):
 *   Flex 的 BaseMapper.selectOneById(Serializable) 只接受单 key,
 *   所以按复合主键查询/删除统一走 QueryWrapper。
 */
@Mapper
public interface TenantActionConfigMapper extends BaseMapper<TenantActionConfig> {

    default List<TenantActionConfig> findByTenantId(String tenantId) {
        return selectListByQuery(QueryWrapper.create()
                .where(TENANT_ACTION_CONFIG.TENANT_ID.eq(tenantId)));
    }

    default TenantActionConfig findByTenantAndAction(String tenantId, String action) {
        return selectOneByQuery(QueryWrapper.create()
                .where(TENANT_ACTION_CONFIG.TENANT_ID.eq(tenantId))
                .and(TENANT_ACTION_CONFIG.ACTION.eq(action)));
    }

    default int deleteByTenantAndAction(String tenantId, String action) {
        return deleteByQuery(QueryWrapper.create()
                .where(TENANT_ACTION_CONFIG.TENANT_ID.eq(tenantId))
                .and(TENANT_ACTION_CONFIG.ACTION.eq(action)));
    }
}
