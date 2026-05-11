package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SysDictUpdateRequest {

    @NotBlank(message = "dict_value 不能为空")
    @Size(max = 500, message = "dict_value 最长 500 字符")
    private String dictValue;
}
