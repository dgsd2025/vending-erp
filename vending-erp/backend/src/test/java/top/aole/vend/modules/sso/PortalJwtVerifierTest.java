package top.aole.vend.modules.sso;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.signers.JWTSignerUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.sso.application.PortalClaims;
import top.aole.vend.modules.sso.application.PortalJwtVerifier;
import top.aole.vend.modules.sso.infrastructure.PortalPublicKeyCache;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 门户 JWT 验签纯单测（不起 Spring、不连库）：RS256 签名 / aud / iss 多值 / exp / alg 混淆 / 换钥拒签。
 * 覆盖 ole-portal-sso 硬约束 1（issuer 多值）与 4（完整校验缺一不可）。
 */
class PortalJwtVerifierTest {

    private static final String APP_ID = "vend_test_app";
    private static final List<String> ISSUERS = Arrays.asList("aole-portal", "yunshan-portal");
    private static KeyPair portalKey;
    private static KeyPair otherKey;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        portalKey = g.generateKeyPair();
        otherKey = g.generateKeyPair();
    }

    private static Map<String, Object> basePayload() {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> p = new HashMap<>();
        p.put("iss", "aole-portal");
        p.put("aud", APP_ID);
        p.put("sub", "10086");
        p.put("portal_uid", "10086");
        p.put("tenant_id", "eco");
        p.put("name", "测试员");
        p.put("phone", "13800000000");
        p.put("iat", now);
        p.put("exp", now + 60);
        return p;
    }

    private static String sign(Map<String, Object> payload, KeyPair kp) {
        return JWT.create().addPayloads(payload).setSigner(JWTSignerUtil.rs256(kp.getPrivate())).sign();
    }

    private static PortalClaims verify(String token) {
        return PortalJwtVerifier.verify(token, portalKey.getPublic(), APP_ID, ISSUERS, 60);
    }

    @Test
    void happyPath_extractsClaims() {
        PortalClaims c = verify(sign(basePayload(), portalKey));
        assertEquals("10086", c.getPortalUid());
        assertEquals("eco", c.getTenantId());
        assertEquals("测试员", c.getName());
        assertEquals("13800000000", c.getPhone());
        assertEquals("aole-portal", c.getIssuer());
    }

    @Test
    void legacyIssuer_stillAccepted() {
        Map<String, Object> p = basePayload();
        p.put("iss", "yunshan-portal");
        assertEquals("yunshan-portal", verify(sign(p, portalKey)).getIssuer());
    }

    @Test
    void unknownIssuer_rejected() {
        Map<String, Object> p = basePayload();
        p.put("iss", "evil-portal");
        BizException e = assertThrows(BizException.class, () -> verify(sign(p, portalKey)));
        assertEquals(401, e.getCode());
        assertTrue(e.getMessage().contains("issuer"));
    }

    @Test
    void wrongAudience_rejected() {
        Map<String, Object> p = basePayload();
        p.put("aud", "some_other_app");
        BizException e = assertThrows(BizException.class, () -> verify(sign(p, portalKey)));
        assertTrue(e.getMessage().contains("audience"));
    }

    @Test
    void audienceAsArray_accepted() {
        Map<String, Object> p = basePayload();
        p.put("aud", Arrays.asList("x", APP_ID));
        assertEquals("10086", verify(sign(p, portalKey)).getPortalUid());
    }

    @Test
    void expired_rejected() {
        Map<String, Object> p = basePayload();
        long now = System.currentTimeMillis() / 1000;
        p.put("iat", now - 600);
        p.put("exp", now - 300);
        BizException e = assertThrows(BizException.class, () -> verify(sign(p, portalKey)));
        assertTrue(e.getMessage().contains("过期"));
    }

    @Test
    void missingExp_rejected() {
        // hutool validateDate 对缺 exp 的票会放行，验签器必须显式拒
        Map<String, Object> p = basePayload();
        p.remove("exp");
        BizException e = assertThrows(BizException.class, () -> verify(sign(p, portalKey)));
        assertEquals(401, e.getCode());
        assertTrue(e.getMessage().contains("exp"));
    }

    @Test
    void signedByOtherKey_rejected() {
        BizException e = assertThrows(BizException.class, () -> verify(sign(basePayload(), otherKey)));
        assertTrue(e.getMessage().contains("签名"));
    }

    @Test
    void algNone_rejected() {
        String token = JWT.create().addPayloads(basePayload()).setSigner(JWTSignerUtil.none()).sign();
        BizException e = assertThrows(BizException.class, () -> verify(token));
        assertTrue(e.getMessage().contains("RS256"));
    }

    @Test
    void hs256WithPublicKeyBytes_rejected() {
        // 经典 alg 混淆：拿公钥当 HMAC 密钥签 HS256，必须被 alg 白名单挡住
        String token = JWT.create().addPayloads(basePayload())
                .setSigner(JWTSignerUtil.hs256(portalKey.getPublic().getEncoded())).sign();
        assertThrows(BizException.class, () -> verify(token));
    }

    @Test
    void garbage_rejected() {
        assertThrows(BizException.class, () -> verify("not.a.jwt"));
        assertThrows(BizException.class, () -> verify(""));
        assertThrows(BizException.class, () -> verify(null));
    }

    @Test
    void pemRoundTrip_parsesPortalPublicKey() {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(portalKey.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        PublicKey parsed = PortalPublicKeyCache.parsePem(pem);
        assertArrayEquals(portalKey.getPublic().getEncoded(), parsed.getEncoded());
        // 用解析出的公钥能验签
        assertEquals("10086", PortalJwtVerifier.verify(sign(basePayload(), portalKey), parsed, APP_ID,
                Collections.singletonList("aole-portal"), 60).getPortalUid());
    }
}
