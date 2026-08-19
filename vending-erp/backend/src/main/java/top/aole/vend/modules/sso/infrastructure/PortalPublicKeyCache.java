package top.aole.vend.modules.sso.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.sso.config.SsoProperties;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 门户 RS256 公钥缓存（ole-portal-sso 硬约束 3：公钥不入仓，启动后从门户拉取并缓存 10 分钟）。
 * 本系统没有 Redis，用进程内缓存代替（单实例部署等价；多实例时各自拉一份，也无正确性问题）。
 * 验签失败时可 {@link #invalidate()} 后重拉一次，兼容门户轮换密钥。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalPublicKeyCache {

    private final SsoProperties props;
    private final PortalSsoClient client;

    private volatile PublicKey cached;
    private volatile long expiresAtMillis;

    public PublicKey get() {
        PublicKey k = cached;
        if (k != null && System.currentTimeMillis() < expiresAtMillis) return k;
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() < expiresAtMillis) return cached;
            String pem = client.fetchPublicKeyPem();
            cached = parsePem(pem);
            expiresAtMillis = System.currentTimeMillis() + props.getPublicKeyTtlSeconds() * 1000L;
            log.info("[sso] portal public key refreshed, ttl={}s", props.getPublicKeyTtlSeconds());
            return cached;
        }
    }

    public synchronized void invalidate() {
        cached = null;
        expiresAtMillis = 0;
    }

    /** X.509 SubjectPublicKeyInfo PEM → RSA PublicKey */
    public static PublicKey parsePem(String pem) {
        try {
            String stripped = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new BizException(502, "门户公钥解析失败：" + e.getMessage());
        }
    }
}
