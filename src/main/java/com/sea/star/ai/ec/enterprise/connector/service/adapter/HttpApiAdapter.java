package com.sea.star.ai.ec.enterprise.connector.service.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.exception.AdapterExecutionException;
import com.sea.star.ai.ec.enterprise.connector.service.SysDictService;
import com.sea.star.ai.ec.enterprise.connector.util.EncryptionUtils;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 调用商家 HTTP API 的适配器 (Phase 6 起从 TenantDatasource 而非 TenantConfig 取资源).
 *
 * 认证: BEARER / BASIC / NONE (按 TenantDatasource.apiAuthType)
 * 路径参数: {orderId} 风格占位符替换为 params 中对应值
 * 超时: 模板 timeout_seconds (截断到 sys_dict.limit.absolute_max_timeout)
 * 响应体上限: sys_dict.limit.max_api_response_size_mb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpApiAdapter implements BusinessAdapter {

    private static final String DICT_ABSOLUTE_MAX_TIMEOUT = "limit.absolute_max_timeout";
    private static final String DICT_DEFAULT_QUERY_TIMEOUT = "limit.default_query_timeout";
    private static final String DICT_MAX_RESPONSE_SIZE_MB = "limit.max_api_response_size_mb";

    private static final String AUTH_BEARER = "BEARER";
    private static final String AUTH_BASIC = "BASIC";

    private final WebClient.Builder webClientBuilder;
    private final EncryptionUtils encryptionUtils;
    private final SysDictService sysDictService;

    @Override
    public boolean supports(AccessType accessType) {
        return accessType == AccessType.API;
    }

    @Override
    public UnifiedResult execute(AdapterRequest request) {
        TenantDatasource datasource = request.getDatasource();
        ActionTemplate template = request.getTemplate();

        if (datasource.getApiBaseUrl() == null || datasource.getApiBaseUrl().isBlank()) {
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_API_ERROR,
                    "数据源未配置 api_base_url: " + datasource.getTenantId() + "/" + datasource.getDsName());
        }
        String path = request.getResolvedApiPath();
        if (path == null || path.isBlank()) {
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_API_ERROR, "API path 为空");
        }

        WebClient client = buildClient(datasource);
        String finalPath = substitutePathVars(path, request.getParams());
        HttpMethod method = resolveMethod(template.getApiMethod());
        int timeoutSeconds = resolveTimeout(template.getTimeoutSeconds());

        long start = System.currentTimeMillis();
        try {
            WebClient.RequestBodySpec spec = client.method(method).uri(finalPath);
            applyDatasourceHeaders(spec, datasource);

            WebClient.RequestHeadersSpec<?> finalSpec;
            if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
                String body = renderBody(template.getApiBodyTemplate(), request.getParams());
                finalSpec = body != null ? spec.bodyValue(body) : spec;
            } else {
                finalSpec = spec;
            }

            String response = finalSpec
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            long cost = System.currentTimeMillis() - start;
            log.info("HTTP API 调用完成 tenantId={}, ds={}, method={} path={}, cost={}ms",
                    datasource.getTenantId(), datasource.getDsName(), method, finalPath, cost);
            Object data = tryParseJson(response);
            return UnifiedResult.ok(data);
        } catch (WebClientResponseException e) {
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_API_ERROR,
                    "商家 API 返回错误 status=" + e.getStatusCode() + ": " + e.getStatusText(), e);
        } catch (Exception e) {
            // Mono.timeout 会把 TimeoutException 包成 RuntimeException (Reactor 的 block() 机制)
            if (isTimeout(e)) {
                throw new AdapterExecutionException(
                        ErrorCode.ADAPTER_TIMEOUT,
                        "商家 API 超时 (>" + timeoutSeconds + "s)", e);
            }
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_API_ERROR,
                    "调用商家 API 失败: " + e.getMessage(), e);
        }
    }

    private WebClient buildClient(TenantDatasource ds) {
        int maxSizeMb = sysDictService.getInt(DICT_MAX_RESPONSE_SIZE_MB, 5);
        int maxBytes = maxSizeMb * 1024 * 1024;
        return webClientBuilder.clone()
                .baseUrl(ds.getApiBaseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
    }

    private void applyDatasourceHeaders(WebClient.RequestBodySpec spec, TenantDatasource ds) {
        String authType = ds.getApiAuthType();
        if (AUTH_BEARER.equalsIgnoreCase(authType)) {
            String token = encryptionUtils.decrypt(ds.getApiTokenEnc());
            if (token != null) {
                spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
        } else if (AUTH_BASIC.equalsIgnoreCase(authType)) {
            String token = encryptionUtils.decrypt(ds.getApiTokenEnc());
            if (token != null) {
                String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
                spec.header(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
            }
        }
        // 额外 header (api_headers JSONB)
        if (ds.getApiHeaders() != null && !ds.getApiHeaders().isBlank()) {
            Map<String, Object> headers = JsonUtils.toMap(ds.getApiHeaders());
            if (headers != null) {
                headers.forEach((k, v) -> {
                    if (v != null) spec.header(k, String.valueOf(v));
                });
            }
        }
    }

    private HttpMethod resolveMethod(String apiMethod) {
        if (apiMethod == null || apiMethod.isBlank()) return HttpMethod.GET;
        return HttpMethod.valueOf(apiMethod.toUpperCase());
    }

    /**
     * 把路径模板中的 {key} 替换为 URL 编码后的 params[key]。
     * 例如 /orders/{orderId} + {orderId:"ORD 1/admin"} → /orders/ORD%201%2Fadmin
     *
     * 必须 URL 编码：防止参数值含 '/' '?' '#' 等字符导致路径注入。
     */
    static String substitutePathVars(String path, Map<String, Object> params) {
        if (params == null || params.isEmpty()) return path;
        String result = path;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String placeholder = "{" + e.getKey() + "}";
            if (result.contains(placeholder)) {
                // URLEncoder 按 form 规范把空格编码为 '+'，path 中需要 '%20'
                String encoded = URLEncoder.encode(
                                String.valueOf(e.getValue()), StandardCharsets.UTF_8)
                        .replace("+", "%20");
                result = result.replace(placeholder, encoded);
            }
        }
        return result;
    }

    /**
     * 渲染 POST body 模板：把 JSON 树中值为 "{key}" 的节点替换为 params[key] 对应的**类型化** JSON 值。
     *
     * 规则：
     *  - 必须是整串等于 "{key}"（首尾为花括号）的字符串节点才被替换；
     *  - 嵌入式占位符（如 "prefix-{key}-suffix"）不支持，避免字符串注入风险；
     *  - 替换后值按实际类型写入（String → 字符串, Number → 数字, Boolean → 布尔, 对象 → 对象）。
     *
     * 这种"结构化替换"天然防止 JSON 注入——参数值中的 '"' '\n' 等字符由 Jackson 负责转义。
     */
    static String renderBody(String bodyTemplate, Map<String, Object> params) {
        if (bodyTemplate == null || bodyTemplate.isBlank()) return null;
        if (params == null || params.isEmpty()) return bodyTemplate;
        try {
            JsonNode tree = JsonUtils.mapper().readTree(bodyTemplate);
            JsonNode substituted = substituteNode(tree, params);
            return JsonUtils.mapper().writeValueAsString(substituted);
        } catch (Exception e) {
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_API_ERROR,
                    "body 模板渲染失败: " + e.getMessage(), e);
        }
    }

    private static JsonNode substituteNode(JsonNode node, Map<String, Object> params) {
        if (node.isTextual()) {
            String text = node.asText();
            if (text.length() >= 2
                    && text.charAt(0) == '{'
                    && text.charAt(text.length() - 1) == '}'
                    && !text.substring(1, text.length() - 1).isEmpty()) {
                String key = text.substring(1, text.length() - 1);
                if (params.containsKey(key)) {
                    return JsonUtils.mapper().valueToTree(params.get(key));
                }
            }
            return node;
        }
        if (node.isObject()) {
            ObjectNode result = JsonUtils.mapper().createObjectNode();
            // Jackson 2.18: fields() 仍是主 API; 2.19+ 会推荐 properties(), 升级时再改
            node.fields().forEachRemaining(entry ->
                    result.set(entry.getKey(), substituteNode(entry.getValue(), params)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonUtils.mapper().createArrayNode();
            for (JsonNode child : node) {
                result.add(substituteNode(child, params));
            }
            return result;
        }
        return node;
    }

    private Object tryParseJson(String response) {
        if (response == null || response.isBlank()) return new HashMap<>();
        try {
            return JsonUtils.fromJson(response, new TypeReference<Object>() {});
        } catch (Exception e) {
            // 非 JSON 响应，原样返回字符串
            return response;
        }
    }

    /**
     * 判断异常链中是否有 java.util.concurrent.TimeoutException（Reactor 会包装）。
     */
    private boolean isTimeout(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof java.util.concurrent.TimeoutException) return true;
            cur = cur.getCause();
        }
        return false;
    }

    private int resolveTimeout(Integer templateTimeout) {
        int absoluteMax = sysDictService.getInt(DICT_ABSOLUTE_MAX_TIMEOUT, 600);
        int defaultTimeout = sysDictService.getInt(DICT_DEFAULT_QUERY_TIMEOUT, 5);
        int effective = templateTimeout != null ? templateTimeout : defaultTimeout;
        return Math.min(effective, absoluteMax);
    }
}
