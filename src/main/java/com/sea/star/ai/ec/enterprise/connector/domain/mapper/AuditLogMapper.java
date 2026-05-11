package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import com.mybatisflex.core.BaseMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
