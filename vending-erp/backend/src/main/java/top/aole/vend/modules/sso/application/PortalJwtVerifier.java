package top.aole.vend.modules.sso.application;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSignerUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.sso.config.SsoProperties;
import top.aole.vend.modules.sso.infrastructure.PortalPublicKeyCache;

import java.security.PublicKey;
import java.util.Date;
import java.util.List;

/**
 * 门户 JWT 完整校验（ole-portal-sso 硬约束 4）：RS256 签名 + aud=appId + iss ∈ 多值白名单 + exp/nbf/iat。
 * 用 hutool-jwt（项目已依赖 hutool-all），不引新库；iss 多值需手动比对，不用单值 requireIssuer。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalJwtVerifier {

    private static final String ALG = "RS256";

    private final SsoProperties props;
    private final PortalPublicKeyCache keyCache;

    /** 用缓存公钥验；签名不过时刷新一次公钥再验（兼容门户轮换密钥） */
    public PortalClaims verify(String jwt) {
        try {
            return verify(jwt, keyCache.get(), props.getAppId(), props.acceptedIssuers(), props.getClockSkewSeconds());
        } catch (BizException first) {
            if (first.getCode() != 401 || !first.getMessage().contains("签名")) throw first;
            log.info("[sso] signature check failed, refreshing portal public key once");
            keyCache.invalidate();
            return verify(jwt, keyCache.get(), props.getAppId(), props.acceptedIssuers(), props.getClockSkewSeconds());
        }
    }

    /**
     * 纯函数版（单测直接调）：
     * 1) header.alg 必须是 RS256（防 alg=none / HS256 混淆）
     * 2) 用门户公钥验签
     * 3) exp / nbf / iat 时间校验（允许 clockSkew 秒偏差）
     * 4) aud 必须包含 appId
     * 5) iss 必须在允许列表内
     */
    public static PortalClaims verify(String token, PublicKey publicKey, String expectedAud,
                                      List<String> acceptedIssuers, long clockSkewSeconds) {
        if (token == null || token.trim().isEmpty()) throw new BizException(401, "门户 JWT 为空");
        JWT jwt;
        try {
            jwt = JWT.of(token.trim());
        } catch (Exception e) {
            throw new BizException(401, "门户 JWT 格式错误");
        }
        if (!ALG.equals(jwt.getAlgorithm())) {
            throw new BizException(401, "门户 JWT 算法不是 RS256：" + jwt.getAlgorithm());
        }
        boolean sigOk;
        try {
            sigOk = jwt.setSigner(JWTSignerUtil.rs256(publicKey)).verify();
        } catch (Exception e) {
            sigOk = false;
        }
        if (!sigOk) throw new BizException(401, "门户 JWT 签名校验失败");

        try {
            JWTValidator.of(jwt).validateDate(new Date(), clockSkewSeconds);
        } catch (ValidateException e) {
            throw new BizException(401, "门户 JWT 已过期或未生效");
        }

        Object aud = jwt.getPayload("aud");
        if (!audienceContains(aud, expectedAud)) {
            throw new BizException(401, "门户 JWT audience 不匹配（期望 " + expectedAud + "）");
        }

        String iss = str(jwt.getPayload("iss"));
        if (iss == null || !acceptedIssuers.contains(iss)) {
            throw new BizException(401, "门户 JWT issuer 不匹配：实际 " + iss + "，允许 " + acceptedIssuers);
        }

        String portalUid = str(jwt.getPayload("portal_uid"));
        if (portalUid == null) portalUid = str(jwt.getPayload("sub"));
        if (portalUid == null) throw new BizException(401, "门户 JWT 缺 portal_uid");

        return new PortalClaims(portalUid, str(jwt.getPayload("tenant_id")),
                str(jwt.getPayload("name")), str(jwt.getPayload("phone")), iss);
    }

    /** aud 可能是字符串或数组 */
    private static boolean audienceContains(Object aud, String expected) {
        if (aud == null || expected == null || expected.isEmpty()) return false;
        if (aud instanceof Iterable) {
            for (Object o : (Iterable<?>) aud) if (expected.equals(str(o))) return true;
            return false;
        }
        return expected.equals(str(aud));
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}
