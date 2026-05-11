package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.SysDictTableDef.SYS_DICT;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.SysDict;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysDictMapper extends BaseMapper<SysDict> {

    default List<SysDict> findByGroupName(String groupName) {
        return selectListByQuery(QueryWrapper.create()
                .where(SYS_DICT.GROUP_NAME.eq(groupName)));
    }
}
