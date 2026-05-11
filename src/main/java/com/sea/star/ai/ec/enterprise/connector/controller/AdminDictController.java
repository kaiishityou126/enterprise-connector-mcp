package com.sea.star.ai.ec.enterprise.connector.controller;

import com.sea.star.ai.ec.enterprise.connector.domain.dto.SysDictUpdateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.SysDictMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.SysDict;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.sea.star.ai.ec.enterprise.connector.service.SysDictService;

/**
 * 字典表管理 API. 运行时可调的业务参数 (限流/超时/重试等), 更新后走 Redis Pub/Sub dict:refresh 广播.
 *
 * 路径设计:
 *   GET  /admin/dicts                    列表 (支持 ?group= 过滤分组)
 *   GET  /admin/dicts/{dictKey}          查单条
 *   PUT  /admin/dicts/{dictKey}          热更新单项值
 *
 * 只有 GET / PUT, 不提供 POST / DELETE — 字典 key 由开发团队通过 seed SQL 预定义,
 * 运行时不允许新增/删除 (防止误加未被代码读的键, 造成配置孤岛).
 */
@Slf4j
@RestController
@RequestMapping("/admin/dicts")
@RequiredArgsConstructor
public class AdminDictController {

    private final SysDictMapper sysDictMapper;
    private final SysDictService sysDictService;

    @GetMapping
    public UnifiedResult list(@RequestParam(required = false) String group) {
        List<SysDict> items = (group == null || group.isBlank())
                ? sysDictMapper.selectAll()
                : sysDictMapper.findByGroupName(group);
        return UnifiedResult.ok(items);
    }

    @GetMapping("/{dictKey}")
    public UnifiedResult get(@PathVariable String dictKey) {
        SysDict dict = sysDictMapper.selectOneById(dictKey);
        if (dict == null) {
            return UnifiedResult.fail(
                    com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode.PARAM_INVALID,
                    "字典项不存在: " + dictKey);
        }
        return UnifiedResult.ok(dict);
    }

    @PutMapping("/{dictKey}")
    public UnifiedResult update(@PathVariable String dictKey,
                                @Valid @RequestBody SysDictUpdateRequest req) {
        sysDictService.update(dictKey, req.getDictValue());
        log.info("Admin 更新字典 dictKey={}, newValue={}", dictKey, req.getDictValue());
        return UnifiedResult.ok(dictKey);
    }
}
