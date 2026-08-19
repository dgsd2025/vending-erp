package top.aole.vend.modules.sso.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 平台门户（aole-portal）SSO 配置。全部从 application.yml ← .env 注入，禁止硬编码（ole-portal-sso 硬约束 1/2）。
 *
 * <pre>
 * SSO_ENABLED=false             → SsoController 不挂载，模块整体不生效
 * SSO_PORTAL_BASE_URL           → 门户 API 根（含 /api），容器内一般是 http://host.docker.internal:18151/api
 * SSO_APP_ID / SSO_CLIENT_SECRET→ 门户管理后台分配，只放 .env（mode 600），永不入仓
 * SSO_PORTAL_JWT_ISSUER         → 允许的 iss 多值，逗号分隔，默认 aole-portal,yunshan-portal
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "sso")
public class SsoProperties {

    private boolean enabled = false;
    /** 门户 API 根地址（含 context-path /api），例：http://host.docker.internal:18151/api */
    private String portalBaseUrl = "";
    /** 门户分配的子系统 app_id */
    private String appId = "";
    /** 门户分配的 client_secret（exchange 时随 body 提交） */
    private String clientSecret = "";
    /** JWT issuer 允许列表（逗号分隔，灰度切换期同时认两个名字） */
    private String jwtIssuer = "aole-portal,yunshan-portal";
    /**
     * 允许登录本系统的门户租户 ID 白名单（逗号多值）。**缺省空 = 拒绝所有租户**（跨系统统一口径，2026-08-19）。
     * 生态管理平台租户 = T000004。
     */
    private String allowedTenantIds = "";
    /** 前端 SSO 中转页路径：后端换到本系统 token 后 302 到这里（hash 传 token） */
    private String frontendCallbackPath = "/sso/callback";
    /** 门户公钥内存缓存 TTL（秒），默认 10 分钟 */
    private long publicKeyTtlSeconds = 600;
    /** JWT 时间校验允许的时钟偏差（秒） */
    private long clockSkewSeconds = 60;
    /** 调门户 HTTP 超时（毫秒） */
    private int httpTimeoutMs = 5000;

    public List<String> acceptedIssuers() {
        return Arrays.stream(jwtIssuer == null ? new String[0] : jwtIssuer.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** 允许的租户 ID 列表（空列表 = 拒绝所有） */
    public List<String> allowedTenants() {
        return Arrays.stream(allowedTenantIds == null ? new String[0] : allowedTenantIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** 去掉末尾斜杠的门户根地址 */
    public String portalBase() {
        return portalBaseUrl == null ? "" : portalBaseUrl.replaceAll("/+$", "");
    }

    public boolean isConfigured() {
        return !portalBase().isEmpty() && appId != null && !appId.trim().isEmpty();
    }
}
