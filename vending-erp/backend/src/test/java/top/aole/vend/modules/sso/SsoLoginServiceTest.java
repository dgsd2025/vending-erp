package top.aole.vend.modules.sso;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.aole.vend.common.auth.AuthTokenService;
import top.aole.vend.common.auth.AuthUser;
import top.aole.vend.common.auth.AuthUserMapper;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.sso.application.PortalClaims;
import top.aole.vend.modules.sso.application.PortalJwtVerifier;
import top.aole.vend.modules.sso.application.SsoLoginService;
import top.aole.vend.modules.sso.config.SsoProperties;
import top.aole.vend.modules.sso.infrastructure.PortalSsoClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SSO 登录主流程单测（Mockito，不起 Spring、不连库）：开关 / app_id / tenant 双源校验 / 首登建号口径。
 */
class SsoLoginServiceTest {

    private SsoProperties props;
    private PortalSsoClient client;
    private PortalJwtVerifier verifier;
    private AuthUserMapper mapper;
    private AuthTokenService tokens;
    private SsoLoginService svc;

    @BeforeEach
    void setUp() {
        props = new SsoProperties();
        props.setEnabled(true);
        props.setPortalBaseUrl("http://portal.test/api");
        props.setAppId("vend_app");
        client = mock(PortalSsoClient.class);
        verifier = mock(PortalJwtVerifier.class);
        mapper = mock(AuthUserMapper.class);
        tokens = mock(AuthTokenService.class);
        when(tokens.issue(anyLong(), anyString(), anyString())).thenReturn("tok");
        svc = new SsoLoginService(props, client, verifier, mapper, tokens);
        ReflectionTestUtils.setField(svc, "defaultRole", "店员");

        when(client.exchange("code1")).thenReturn(new JSONObject().set("jwt", "j").set("name", "门户名").set("phone", "13800000000"));
        when(verifier.verify("j")).thenReturn(new PortalClaims("10086", "eco", "门户名", "13800000000", "aole-portal"));
        // insert 时给主键
        doAnswer(inv -> { ((AuthUser) inv.getArgument(0)).setId(99L); return 1; }).when(mapper).insert(any(AuthUser.class));
    }

    @Test
    void disabled_rejected() {
        props.setEnabled(false);
        BizException e = assertThrows(BizException.class, () -> svc.loginByAuthCode("code1", "vend_app", null));
        assertEquals(400, e.getCode());
    }

    @Test
    void appIdMismatch_rejected() {
        BizException e = assertThrows(BizException.class, () -> svc.loginByAuthCode("code1", "other_app", null));
        assertEquals(400, e.getCode());
        verify(client, never()).exchange(anyString());
    }

    @Test
    void tenantMismatch_rejected() {
        // URL tenantId 与 JWT tenant_id 不一致 → 401（ole-portal-sso 硬约束 5）
        BizException e = assertThrows(BizException.class, () -> svc.loginByAuthCode("code1", "vend_app", "another-tenant"));
        assertEquals(401, e.getCode());
        verify(mapper, never()).insert(any(AuthUser.class));
    }

    @Test
    void tenantMatch_passes() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn(5L);            // 表非空 → 不晋升老板
        SsoLoginService.Result r = svc.loginByAuthCode("code1", "vend_app", "eco");
        assertEquals("tok", r.getToken());
        assertTrue(r.isCreated());
    }

    @Test
    void firstLogin_createsUserWithDefaultRole_notBoss() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        // 第一次 selectCount(username 占用查询)=0；第二次 selectCount(null 表总数)=3
        when(mapper.selectCount(any())).thenReturn(0L, 3L);
        SsoLoginService.Result r = svc.loginByAuthCode("code1", "vend_app", null);
        ArgumentCaptor<AuthUser> cap = ArgumentCaptor.forClass(AuthUser.class);
        verify(mapper).insert(cap.capture());
        AuthUser u = cap.getValue();
        assertEquals("13800000000", u.getUsername());
        assertEquals("门户名", u.getDisplayName());
        assertEquals("10086", u.getPortalUid());
        assertEquals("店员", u.getRole());
        assertNotNull(u.getPasswordHash());
        assertTrue(r.isCreated());
        assertEquals("店员", r.getRole());
    }

    @Test
    void firstLogin_emptyTable_becomesBoss() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn(0L, 0L);
        svc.loginByAuthCode("code1", "vend_app", null);
        ArgumentCaptor<AuthUser> cap = ArgumentCaptor.forClass(AuthUser.class);
        verify(mapper).insert(cap.capture());
        assertEquals("老板", cap.getValue().getRole());
    }

    @Test
    void existingByPortalUid_logsInWithoutCreate() {
        AuthUser existing = new AuthUser();
        existing.setId(7L); existing.setUsername("laoban"); existing.setDisplayName("老板");
        existing.setRole("老板"); existing.setStatus(1); existing.setPortalUid("10086");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        SsoLoginService.Result r = svc.loginByAuthCode("code1", "vend_app", "eco");
        assertFalse(r.isCreated());
        assertEquals("老板", r.getRole());
        assertEquals("laoban", r.getUsername());
        verify(mapper, never()).insert(any(AuthUser.class));
        verify(mapper).updateById(existing);
    }

    @Test
    void disabledUser_rejected403() {
        AuthUser existing = new AuthUser();
        existing.setId(7L); existing.setUsername("x"); existing.setDisplayName("x");
        existing.setRole("店员"); existing.setStatus(0); existing.setPortalUid("10086");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        BizException e = assertThrows(BizException.class, () -> svc.loginByAuthCode("code1", "vend_app", null));
        assertEquals(403, e.getCode());
    }
}
