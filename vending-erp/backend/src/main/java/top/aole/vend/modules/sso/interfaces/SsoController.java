package top.aole.vend.modules.sso.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.sso.application.SsoLoginService;
import top.aole.vend.modules.sso.config.SsoProperties;

import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 平台门户 SSO 回调端点（仅 sso.enabled=true 时挂载；AuthGateFilter 已放行 /v1/sso/）。
 * <p>
 * 门户注册的 callback_url = https://vend.vvaix.com/api/v1/sso/callback，门户带 auth_code&amp;app_id&amp;tenantId 302 到这里；
 * 本端换成本系统 Bearer token 后再 302 到前端 {@code /sso/callback#token=..&displayName=..&role=..}
 * （与现有 /auth/login 交 token 的方式一致：前端 setSession 三件套后强刷整页）。失败则 302 到 {@code /sso/callback#error=登录失败（代码 N）&code=N}，细节只在日志。
 * token 放 URL fragment：不进服务端日志、不随 Referer 外泄。
 */
@Slf4j
@Api(tags = "SSO 单点登录")
@RestController
@RequestMapping("/v1/sso")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sso", name = "enabled", havingValue = "true")
public class SsoController {

    private final SsoLoginService loginService;
    private final SsoProperties props;

    @ApiOperation("门户回调：auth_code 兑换 → 验签 → 本地账号 → 302 到前端中转页")
    @GetMapping("/callback")
    public void callback(@RequestParam(value = "auth_code", required = false) String authCodeSnake,
                         @RequestParam(value = "authCode", required = false) String authCodeCamel,
                         @RequestParam(value = "app_id", required = false) String appIdSnake,
                         @RequestParam(value = "appId", required = false) String appIdCamel,
                         @RequestParam(value = "tenantId", required = false) String tenantId,
                         @RequestParam(value = "tenant_id", required = false) String tenantIdSnake,
                         HttpServletResponse response) {
        // 同时接受 snake / camel 两套参数名（门户契约 auth_code/app_id，模板 DTO 别名 authCode/appId）
        String authCode = authCodeSnake != null ? authCodeSnake : authCodeCamel;
        String appId = appIdSnake != null ? appIdSnake : appIdCamel;
        String tenant = tenantId != null ? tenantId : tenantIdSnake;
        log.info("[sso] callback app_id={} tenantId={} auth_code_prefix={}", appId, tenant,
                authCode == null ? null : authCode.substring(0, Math.min(6, authCode.length())));
        String location;
        try {
            SsoLoginService.Result r = loginService.loginByAuthCode(authCode, appId, tenant);
            location = props.getFrontendCallbackPath() + "#token=" + enc(r.getToken())
                    + "&username=" + enc(r.getUsername())
                    + "&displayName=" + enc(r.getDisplayName())
                    + "&role=" + enc(r.getRole())
                    + "&created=" + r.isCreated();
        } catch (BizException e) {
            // 细节只进日志；进浏览器 URL 的一律固定短句（不泄露门户/校验细节）
            log.warn("[sso] callback rejected code={} msg={}", e.getCode(), e.getMessage());
            location = props.getFrontendCallbackPath() + "#error=" + enc(userMessage(e.getCode())) + "&code=" + e.getCode();
        } catch (Exception e) {
            log.error("[sso] callback error", e);
            location = props.getFrontendCallbackPath() + "#error=" + enc(userMessage(500)) + "&code=500";
        }
        // 相对 Location（RFC 7231 允许）：避免容器内 sendRedirect 拼出 http://内网 host 的绝对地址
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", location);
        response.setHeader("Cache-Control", "no-store");
    }

    /** 浏览器可见的固定短句：登录失败（代码 N），N=业务码，排障看后端日志 */
    static String userMessage(int code) {
        return "登录失败（代码 " + code + "）";
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
