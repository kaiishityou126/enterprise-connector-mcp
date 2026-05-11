package com.sea.star.ai.ec.enterprise.connector.service.adapter;

import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;

/**
 * 业务数据源适配器接口。
 * 每种接入类型（DB / HTTP API）实现一个。
 */
public interface BusinessAdapter {

    /**
     * 执行调用，返回标准化结果。
     * 执行异常应抛出 AdapterExecutionException，由上层统一处理。
     */
    UnifiedResult execute(AdapterRequest request);

    /**
     * 判断本适配器是否支持给定接入类型。
     */
    boolean supports(AccessType accessType);
}
