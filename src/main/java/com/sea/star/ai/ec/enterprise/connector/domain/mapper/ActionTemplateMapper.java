package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.ActionTemplateTableDef.ACTION_TEMPLATE;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import java.util.Objects;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ActionTemplateMapper extends BaseMapper<ActionTemplate> {

    default ActionTemplate findByActionAndAccessType(String action, AccessType accessType) {
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(accessType, "accessType 不能为空");
        return selectOneByQuery(QueryWrapper.create()
                .where(ACTION_TEMPLATE.ACTION.eq(action))
                .and(ACTION_TEMPLATE.ACCESS_TYPE.eq(accessType)));
    }

    /**
     * 找出同一 action 的所有方言版本 (POSTGRES / MYSQL / SQLSERVER / ORACLE / API).
     * 用于 Admin API 写入时校验 description / param_schema / datasource_name 在多方言间一致性.
     */
    default java.util.List<ActionTemplate> findAllByAction(String action) {
        Objects.requireNonNull(action, "action 不能为空");
        return selectListByQuery(QueryWrapper.create()
                .where(ACTION_TEMPLATE.ACTION.eq(action)));
    }
}
