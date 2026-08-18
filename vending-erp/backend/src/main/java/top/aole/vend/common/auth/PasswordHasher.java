package top.aole.vend.common.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** PBKDF2-HMAC-SHA256 密码哈希（无第三方依赖）。格式 iter:salt:hash（Base64）。 */
public final class PasswordHasher {
    private static final int ITER = 120_000;
    private static final SecureRandom RND = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RND.nextBytes(salt);
        return ITER + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derive(password, salt, ITER));
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] p = stored.split(":");
        if (p.length != 3) return false;
        try {
            int iter = Integer.parseInt(p[0]);
            byte[] salt = Base64.getDecoder().decode(p[1]);
            byte[] expected = Base64.getDecoder().decode(p[2]);
            return MessageDigest.isEqual(derive(password, salt, iter), expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iter, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }
}
