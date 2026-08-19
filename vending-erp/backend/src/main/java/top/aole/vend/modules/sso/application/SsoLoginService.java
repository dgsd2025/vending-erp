package top.aole.vend.modules.sso.application;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.auth.AuthTokenService;
import top.aole.vend.common.auth.AuthUser;
import top.aole.vend.common.auth.AuthUserMapper;
import top.aole.vend.common.auth.PasswordHasher;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.sso.config.SsoProperties;
import top.aole.vend.modules.sso.infrastructure.PortalSsoClient;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 门户 SSO 登录主流程（场景 2：老子系统 + 本地用户表，加 portal_uid 绑定）：
 * <pre>
 *   auth_code → 门户 exchange → RS256 验签（aud/iss/exp）→ URL tenantId 与 JWT tenant_id 双源校验 → tenant ∈ SSO_ALLOWED_TENANT_IDS（空=全拒）
 *   → 按 portal_uid 找本地 auth_user → 找不到：首登自动建号（最低默认角色）→ 签本系统 Bearer token
 * </pre>
 * 首登口径（本系统拍板，偏离 ole-portal-sso 硬约束 6 的"默认拒绝建号"，因本系统走邀请码自助注册、门户已是可信来源）：
 * <ul>
 *   <li>先按 portal_uid 精确匹配；</li>
 *   <li>再按 username=手机号 匹配"尚未绑定 portal_uid 且角色==默认最低角色"的老账号 → 回写 portal_uid 绑定（手机号仅用于首次绑定，硬约束 8；老板等高权限账号不自动绑）；</li>
 *   <li>都没有 → 新建：username=手机号（被占则 portal_&lt;uid&gt;），姓名=JWT name，角色=register-default-role；</li>
 *   <li>「首个账号自动老板」只在 auth_user 表确实为空时触发，SSO 建号不会把普通门户用户抬成老板。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoLoginService {

    private static final SecureRandom RND = new SecureRandom();

    private final SsoProperties props;
    private final PortalSsoClient client;
    private final PortalJwtVerifier verifier;
    private final AuthUserMapper userMapper;
    private final AuthTokenService tokenService;

    @Value("${vend.auth.register-default-role:店员}")
    private String defaultRole;

    /** 登录结果：交给前端 setSession 的三件套 + 是否本次新建 */
    @lombok.Value
    public static class Result {
        String token;
        String username;
        String displayName;
        String role;
        boolean created;
    }

    @Transactional(rollbackFor = Exception.class)
    public Result loginByAuthCode(String authCode, String appId, String urlTenantId) {
        if (!props.isEnabled()) throw new BizException(400, "SSO 未启用");
        if (!props.isConfigured()) throw new BizException(500, "SSO 配置不完整（SSO_PORTAL_BASE_URL / SSO_APP_ID）");
        if (authCode == null || authCode.trim().isEmpty()) throw new BizException(400, "auth_code 缺失");
        if (appId != null && !appId.trim().isEmpty() && !appId.trim().equals(props.getAppId())) {
            throw new BizException(400, "app_id 不匹配");
        }

        // 1. 兑换
        JSONObject data = client.exchange(authCode.trim());
        String jwt = data.getStr("jwt");
        if (jwt == null || jwt.isEmpty()) throw new BizException(502, "门户响应缺 jwt");

        // 2. 验签（RS256 + aud + iss 多值 + exp）
        PortalClaims claims = verifier.verify(jwt);

        // 3. URL tenantId 与 JWT tenant_id 双源校验（硬约束 5）；本系统单租户，URL 没带时不强求
        String urlTenant = blankToNull(urlTenantId);
        if (urlTenant != null && !urlTenant.equals(claims.getTenantId())) {
            log.warn("[sso] tenant mismatch url={} jwt={}", urlTenant, claims.getTenantId());
            throw new BizException(401, "租户校验失败");
        }

        // 3b. 租户白名单 SSO_ALLOWED_TENANT_IDS（跨系统统一口径 2026-08-19）：缺省空 = 拒绝所有；JWT 无 tenant_id 也拒
        List<String> allowedTenants = props.allowedTenants();
        String jwtTenant = blankToNull(claims.getTenantId());
        if (jwtTenant == null || !allowedTenants.contains(jwtTenant)) {
            log.warn("[sso] tenant not allowed jwt={} allowed={}", jwtTenant, allowedTenants);
            throw new BizException(403, "该租户未开通本系统");
        }

        // exchange 顶层 name/phone 与 JWT claims 取并集（JWT 优先）
        String name = firstNonBlank(claims.getName(), data.getStr("name"));
        String phone = firstNonBlank(claims.getPhone(), data.getStr("phone"));
        String portalUid = claims.getPortalUid();

        // 4. 找本地账号 / 首登建号
        boolean created = false;
        AuthUser user = userMapper.selectOne(new LambdaQueryWrapper<AuthUser>()
                .eq(AuthUser::getPortalUid, portalUid).last("limit 1"));
        if (user == null && phone != null) {
            AuthUser byPhone = userMapper.selectOne(new LambdaQueryWrapper<AuthUser>()
                    .eq(AuthUser::getUsername, phone).last("limit 1"));
            // 只自动绑「未绑定 + 角色==默认最低角色」的老账号；老板/财务等高权限账号一律不自动绑（走建号，防越权接管）
            if (byPhone != null && blankToNull(byPhone.getPortalUid()) == null && Objects.equals(byPhone.getRole(), defaultRole)) {
                byPhone.setPortalUid(portalUid);
                user = byPhone;
                log.info("[sso] bind existing user id={} username={} → portal_uid={}", user.getId(), user.getUsername(), portalUid);
            } else if (byPhone != null) {
                log.info("[sso] skip auto-bind username={} role={} portal_uid_bound={} → create new", byPhone.getUsername(), byPhone.getRole(), byPhone.getPortalUid() != null);
            }
        }
        if (user == null) {
            user = createUser(portalUid, name, phone);
            created = true;
        }

        if (user.getStatus() == null || user.getStatus() != 1) throw new BizException(403, "账号已停用，请联系管理员");

        // 同步姓名（门户是主数据）
        if (name != null && !Objects.equals(user.getDisplayName(), name) && name.length() <= 64) {
            user.setDisplayName(name);
        }
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        String token = tokenService.issue(user.getId(), user.getDisplayName(), user.getRole());
        log.info("[sso] login ok userId={} username={} role={} created={}", user.getId(), user.getUsername(), user.getRole(), created);
        return new Result(token, user.getUsername(), user.getDisplayName(), user.getRole(), created);
    }

    private AuthUser createUser(String portalUid, String name, String phone) {
        String username = phone != null && phone.matches("^[A-Za-z0-9_\\-]{3,32}$") ? phone : "portal_" + portalUid;
        if (userMapper.selectCount(new LambdaQueryWrapper<AuthUser>().eq(AuthUser::getUsername, username)) > 0) {
            username = "portal_" + portalUid;
        }
        if (username.length() > 64) username = username.substring(0, 64);
        String displayName = firstNonBlank(name, phone, "门户用户" + portalUid);
        if (displayName.length() > 64) displayName = displayName.substring(0, 64);

        // 「首个账号自动老板」只在表为空时成立；否则给最低默认角色（与邀请码注册同一口径）
        boolean tableEmpty = userMapper.selectCount(null) == 0;

        AuthUser u = new AuthUser();
        u.setUsername(username);
        // SSO 账号无本地密码：写入一段随机不可知口令的哈希（列 NOT NULL，且不可能被密码登录命中）
        byte[] rnd = new byte[32];
        RND.nextBytes(rnd);
        u.setPasswordHash(PasswordHasher.hash(Base64.getUrlEncoder().withoutPadding().encodeToString(rnd)));
        u.setDisplayName(displayName);
        u.setRole(tableEmpty ? "老板" : defaultRole);
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.now());
        u.setLastLoginAt(LocalDateTime.now());
        u.setPortalUid(portalUid);
        userMapper.insert(u);
        log.info("[sso] first login → created user id={} username={} role={} portal_uid={}", u.getId(), username, u.getRole(), portalUid);
        return u;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) if (blankToNull(x) != null) return x.trim();
        return null;
    }
}
