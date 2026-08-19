package top.aole.vend.common.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Enumeration;

/**
 * 账号门禁（2026-08-19 邀请码注册 · 全系统统一契约）：
 * 身份唯一来源 = /auth/register|login 签发的 Bearer 令牌；据令牌把 X-User-Name / X-User-Role 重注入到请求头
 * （业务代码仍按占位头读，零改动 = ADR-001「可信网关重注入」在应用内落地）。
 * 无令牌访问业务接口 → 401；vend.auth.placeholder-headers-enabled=true 时（仅本地开发）才信任客户端自带占位头。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
public class AuthGateFilter extends OncePerRequestFilter {

    private final AuthTokenService tokenService;

    @Value("${vend.auth.placeholder-headers-enabled:false}")
    private boolean placeholderHeadersEnabled;

    /** /v1/sso/ = 平台门户 SSO 回调（无登录态入口，ole-portal-sso checklist 第 4 步） */
    private static final String[] PUBLIC_PREFIXES = {
            "/auth/", "/v1/sso/", "/v1/health", "/actuator", "/doc.html", "/webjars/", "/swagger-resources", "/v2/api-docs", "/v3/api-docs", "/swagger-ui", "/favicon.ico", "/error"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) path = path.substring(ctx.length());
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublic(path)) { chain.doFilter(request, response); return; }

        String auth = request.getHeader("Authorization");
        AuthTokenService.Claims c = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? tokenService.verify(auth.substring(7).trim()) : null;
        if (c != null) {
            chain.doFilter(new IdentityRequest(request, c.name, c.role), response);
            return;
        }
        if (placeholderHeadersEnabled) { chain.doFilter(request, response); return; }
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
    }

    private static boolean isPublic(String path) {
        for (String p : PUBLIC_PREFIXES) if (path.startsWith(p)) return true;
        return false;
    }

    /** 覆盖 X-User-Name / X-User-Role 两个头（percent-encode，与 Operators.resolve 解码口径一致） */
    static final class IdentityRequest extends HttpServletRequestWrapper {
        private final String name;
        private final String role;
        IdentityRequest(HttpServletRequest req, String name, String role) { super(req); this.name = enc(name); this.role = enc(role); }
        private static String enc(String v) { try { return URLEncoder.encode(v == null ? "" : v, "UTF-8"); } catch (UnsupportedEncodingException e) { return ""; } }
        @Override public String getHeader(String h) {
            if ("X-User-Name".equalsIgnoreCase(h)) return name;
            if ("X-User-Role".equalsIgnoreCase(h)) return role;
            return super.getHeader(h);
        }
        @Override public Enumeration<String> getHeaders(String h) {
            if ("X-User-Name".equalsIgnoreCase(h)) return Collections.enumeration(Collections.singletonList(name));
            if ("X-User-Role".equalsIgnoreCase(h)) return Collections.enumeration(Collections.singletonList(role));
            return super.getHeaders(h);
        }
    }
}
