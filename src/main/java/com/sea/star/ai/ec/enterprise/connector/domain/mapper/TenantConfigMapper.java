package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.TenantConfigTableDef.TENANT_CONFIG;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantConfigMapper extends BaseMapper<TenantConfig> {

    default List<TenantConfig> findEnabled() {
        return selectListByQuery(QueryWrapper.create()
                .where(TENANT_CONFIG.ENABLED.eq(Boolean.TRUE)));
    }
}
