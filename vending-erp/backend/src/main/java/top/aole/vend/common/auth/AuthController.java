package top.aole.vend.common.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.common.result.R;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 账号：邀请码注册 + 登录 + 自省（2026-08-19 全系统统一契约 · dev-standards《邀请码注册-全系统盘点与落地方案》）。
 * 邀请码 REGISTER_INVITE_CODE（默认 dgsd1985）常量时间比较、放在所有校验之前；失败不建记录、不记用户输错的值。
 * 首个注册账号自动成为「老板」（引导），其余用默认角色（vend.auth.register-default-role）。
 */
@Slf4j
@Api(tags = "账号")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthUserMapper userMapper;
    private final AuthTokenService tokenService;

    @Value("${vend.auth.invite-code:dgsd1985}")
    private String inviteCode;
    @Value("${vend.auth.register-default-role:店员}")
    private String defaultRole;

    @ApiOperation("邀请码注册")
    @PostMapping("/register")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        if (!isValidInviteCode(body.get("inviteCode"))) {
            log.warn("[auth] invite_code_rejected");
            throw new BizException(400, "邀请码不正确");
        }
        String username = trim(body.get("username"));
        String password = body.get("password") == null ? "" : body.get("password");
        String displayName = trim(body.get("displayName"));
        if (!username.matches("^[A-Za-z0-9_\\-]{3,32}$")) throw new BizException(400, "账号 3-32 位,仅限字母、数字、下划线、横线");
        if (password.length() < 8 || password.length() > 128) throw new BizException(400, "密码至少 8 位");
        if (displayName.isEmpty()) displayName = username;
        if (displayName.length() > 30) throw new BizException(400, "姓名最长 30 字");
        if (userMapper.selectCount(new LambdaQueryWrapper<AuthUser>().eq(AuthUser::getUsername, username)) > 0) {
            throw new BizException(409, "该账号已注册");
        }
        boolean first = userMapper.selectCount(null) == 0;
        AuthUser u = new AuthUser();
        u.setUsername(username);
        u.setPasswordHash(PasswordHasher.hash(password));
        u.setDisplayName(displayName);
        u.setRole(first ? "老板" : defaultRole);
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.now());
        u.setLastLoginAt(LocalDateTime.now());
        userMapper.insert(u);
        log.info("[auth] register ok username={} role={}", username, u.getRole());
        return R.ok(tokenPayload(u), "注册成功");
    }

    @ApiOperation("登录")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = trim(body.get("username"));
        String password = body.get("password") == null ? "" : body.get("password");
        AuthUser u = userMapper.selectOne(new LambdaQueryWrapper<AuthUser>().eq(AuthUser::getUsername, username).last("limit 1"));
        if (u == null || u.getStatus() == null || u.getStatus() != 1 || !PasswordHasher.verify(password, u.getPasswordHash())) {
            throw new BizException(401, "账号或密码错误");
        }
        u.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(u);
        return R.ok(tokenPayload(u), "登录成功");
    }

    @ApiOperation("当前账号")
    @GetMapping("/me")
    public R<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        AuthTokenService.Claims c = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? tokenService.verify(auth.substring(7).trim()) : null;
        if (c == null) throw new BizException(401, "未登录");
        Map<String, Object> m = new HashMap<>();
        m.put("userId", c.userId);
        m.put("displayName", c.name);
        m.put("role", c.role);
        return R.ok(m);
    }

    private Map<String, Object> tokenPayload(AuthUser u) {
        Map<String, Object> m = new HashMap<>();
        m.put("token", tokenService.issue(u.getId(), u.getDisplayName(), u.getRole()));
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("role", u.getRole());
        return m;
    }

    private boolean isValidInviteCode(String input) {
        if (input == null) return false;
        byte[] a = input.trim().getBytes(StandardCharsets.UTF_8);
        byte[] b = inviteCode.trim().getBytes(StandardCharsets.UTF_8);
        return a.length > 0 && MessageDigest.isEqual(a, b);
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
