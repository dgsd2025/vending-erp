package top.aole.vend.common.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 极简会话令牌（HMAC-SHA256 签名，无第三方依赖）：base64url(userId|name|role|exp).base64url(sig)。
 * 密钥 VEND_AUTH_SECRET；未配置用开发默认值（生产必须配）。
 */
@Component
public class AuthTokenService {

    @Value("${vend.auth.secret:vend-dev-secret-change-me}")
    private String secret;
    @Value("${vend.auth.token-ttl-seconds:2592000}")
    private long ttlSeconds;

    public static final class Claims {
        public final long userId; public final String name; public final String role; public final long exp;
        Claims(long userId, String name, String role, long exp) { this.userId = userId; this.name = name; this.role = role; this.exp = exp; }
    }

    public String issue(long userId, String name, String role) {
        long exp = System.currentTimeMillis() / 1000 + ttlSeconds;
        String payload = userId + "|" + name + "|" + role + "|" + exp;
        String p = b64(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + b64(sign(p));
    }

    public Claims verify(String token) {
        if (token == null) return null;
        int dot = token.lastIndexOf('.');
        if (dot <= 0) return null;
        String p = token.substring(0, dot);
        byte[] sig;
        try { sig = Base64.getUrlDecoder().decode(token.substring(dot + 1)); } catch (Exception e) { return null; }
        if (!MessageDigest.isEqual(sig, sign(p))) return null;
        String[] parts = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 4) return null;
        try {
            long exp = Long.parseLong(parts[3]);
            if (exp < System.currentTimeMillis() / 1000) return null;
            return new Claims(Long.parseLong(parts[0]), parts[1], parts[2], exp);
        } catch (NumberFormatException e) { return null; }
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
