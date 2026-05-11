package com.sea.star.ai.ec.enterprise.connector.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.sea.star.ai.ec.enterprise.connector.domain.dto.TemplateCreateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.dto.TemplateUpdateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.ActionTemplateMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.service.ActionTemplateService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 校验 AdminTemplateController 在写入第二个方言版本时, description / param_schema /
 * datasource_name 必须与已存在的兄弟行 byte-equal 一致, 否则抛 INCONSISTENT_TEMPLATE_FAMILY.
 *
 * <p>这是多方言适配的"族内一致性"防呆: AI 视角同 action 是同一个 tool, 元数据不一致会让 AI
 * 拿到的 schema 跟实际 SQL 期望对不上.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminTemplateControllerConsistencyTest {

    @Mock private ActionTemplateService actionTemplateService;
    @Mock private ActionTemplateMapper actionTemplateMapper;

    private AdminTemplateController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminTemplateController(actionTemplateService, actionTemplateMapper);
        // 让 service.create 给 entity 填一个 templateId, 模拟数据库自增
        doAnswer(inv -> {
            ActionTemplate t = inv.getArgument(0);
            t.setTemplateId(99);
            return null;
        }).when(actionTemplateService).create(any());
    }

    @Test
    @DisplayName("无兄弟行: 创建放行")
    void createWithNoSiblings() {
        when(actionTemplateMapper.findAllByAction("queryOrder")).thenReturn(List.of());

        TemplateCreateRequest req = baseCreateReq();
        assertThatCode(() -> controller.create(req)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("有兄弟行且元数据一致: 创建放行")
    void createWithConsistentSibling() {
        ActionTemplate sibling = baseSiblingTemplate(AccessType.POSTGRES, 10);
        when(actionTemplateMapper.findAllByAction("queryOrder")).thenReturn(List.of(sibling));

        TemplateCreateRequest req = baseCreateReq();  // 默认元数据一致
        UnifiedResult result = controller.create(req);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("description 不一致: 抛 INCONSISTENT_TEMPLATE_FAMILY")
    void createWithDifferentDescription() {
        ActionTemplate sibling = baseSiblingTemplate(AccessType.POSTGRES, 10);
        sibling.setDescription("不同的描述");  // 兄弟行 description 跟 candidate 不一致
        when(actionTemplateMapper.findAllByAction("queryOrder")).thenReturn(List.of(sibling));

        TemplateCreateRequest req = baseCreateReq();
        assertThatThrownBy(() -> controller.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("paramSchema 不一致: 抛 INCONSISTENT_TEMPLATE_FAMILY")
    void createWithDifferentParamSchema() {
        ActionTemplate sibling = baseSiblingTemplate(AccessType.POSTGRES, 10);
        sibling.setParamSchema("{\"orderId\":\"int\"}");  // 跟 candidate 字段大小写不同
        when(actionTemplateMapper.findAllByAction("queryOrder")).thenReturn(List.of(sibling));

        TemplateCreateRequest req = baseCreateReq();  // candidate 用 {"order_id":"string"}
        assertThatThrownBy(() -> controller.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("param_schema");
    }

    @Test
    @DisplayName("datasource_name 不一致: 抛 INCONSISTENT_TEMPLATE_FAMILY")
    void createWithDifferentDatasourceName() {
        ActionTemplate sibling = baseSiblingTemplate(AccessType.POSTGRES, 10);
        sibling.setDatasourceName("orders");  // 兄弟行打 orders, candidate 打 default
        when(actionTemplateMapper.findAllByAction("queryOrder")).thenReturn(List.of(sibling));

        TemplateCreateRequest req = baseCreateReq();
        assertThatThrownBy(() -> controller.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("datasource_name");
    }

    @Test
    @DisplayName("更新自身行不会触发自检")
    void updateSelfNotChecked() {
        ActionTemplate existing = baseSiblingTemplate(AccessType.MYSQL, 99);
        when(actionTemplateMapper.selectOneById(99)).thenReturn(existing);
        when(actionTemplateMapper.findAllByAction("queryOrder")).thenReturn(List.of(existing));

        TemplateUpdateRequest req = new TemplateUpdateRequest();
        req.setSqlTemplate("SELECT 2");  // 只改 sql_template, 元数据不变

        assertThatCode(() -> controller.update(99, req)).doesNotThrowAnyException();
    }

    // ---- helpers ----

    private TemplateCreateRequest baseCreateReq() {
        TemplateCreateRequest req = new TemplateCreateRequest();
        req.setAction("queryOrder");
        req.setAccessType(AccessType.MYSQL);
        req.setName("查询订单");
        req.setDescription("按订单号查询");
        req.setDatasourceName("default");
        req.setSqlTemplate("SELECT * FROM `t_order` WHERE id=:id LIMIT 1");
        req.setParamSchema("{\"order_id\":\"string\"}");
        return req;
    }

    private ActionTemplate baseSiblingTemplate(AccessType accessType, Integer templateId) {
        return ActionTemplate.builder()
                .templateId(templateId)
                .action("queryOrder")
                .accessType(accessType)
                .name("查询订单")
                .description("按订单号查询")
                .datasourceName("default")
                .sqlTemplate("SELECT * FROM orders WHERE id=:id LIMIT 1")
                .paramSchema("{\"order_id\":\"string\"}")
                .enabled(true)
                .build();
    }
}
