package com.sea.star.ai.ec.enterprise.connector.domain.model;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("sys_dict")
public class SysDict {

    @Id(keyType = KeyType.None)
    private String dictKey;

    @Column("dict_value")
    private String dictValue;

    @Column("value_type")
    private String valueType;

    @Column("group_name")
    private String groupName;

    @Column("description")
    private String description;

    /** 软删标志, Flex 自动过滤 (false=未删除) */
    @Column(value = "deleted", isLogicDelete = true)
    private Boolean deleted;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
