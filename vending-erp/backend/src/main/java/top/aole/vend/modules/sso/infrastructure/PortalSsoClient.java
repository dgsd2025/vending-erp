package top.aole.vend.modules.sso.infrastructure;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.sso.config.SsoProperties;

/**
 * 门户 SSO HTTP 客户端：exchange（auth_code → JWT）与 public-key（RS256 公钥 PEM）。
 * 上游契约见 ole-portal-sso SKILL「上游契约速查」：返回体 {code, message/msg, data, success?}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalSsoClient {

    private final SsoProperties props;

    /**
     * POST {portal}/v1/sso/exchange  body {authCode, appId, clientSecret}
     * → data {jwt, expiresIn, portalUid, name, phone}
     */
    public JSONObject exchange(String authCode) {
        String url = props.portalBase() + "/v1/sso/exchange";
        JSONObject body = new JSONObject()
                .set("authCode", authCode)
                .set("appId", props.getAppId());
        if (props.getClientSecret() != null && !props.getClientSecret().trim().isEmpty()) {
            body.set("clientSecret", props.getClientSecret().trim());
        }
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(body.toString())
                .timeout(props.getHttpTimeoutMs())
                .execute()) {
            if (!resp.isOk()) {
                log.warn("[sso] exchange http {} url={}", resp.getStatus(), url);
                throw new BizException(502, "门户 exchange HTTP " + resp.getStatus());
            }
            JSONObject parsed = JSONUtil.parseObj(resp.body());
            if (!isSuccess(parsed)) {
                String msg = parsed.getStr("message", parsed.getStr("msg", "未知错误"));
                log.warn("[sso] exchange rejected: {}", msg);
                throw new BizException(401, "门户 exchange 失败：" + msg);
            }
            JSONObject data = parsed.getJSONObject("data");
            if (data == null) throw new BizException(502, "门户 exchange 返回缺 data");
            return data;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[sso] exchange error url={} err={}", url, e.toString());
            throw new BizException(502, "门户不可达，请稍后重试或联系管理员");
        }
    }

    /** GET {portal}/v1/sso/public-key → data = PEM 字符串 */
    public String fetchPublicKeyPem() {
        String url = props.portalBase() + "/v1/sso/public-key";
        try (HttpResponse resp = HttpRequest.get(url).timeout(props.getHttpTimeoutMs()).execute()) {
            if (!resp.isOk()) throw new BizException(502, "门户公钥拉取 HTTP " + resp.getStatus());
            JSONObject parsed = JSONUtil.parseObj(resp.body());
            if (!isSuccess(parsed)) throw new BizException(502, "门户公钥拉取失败");
            String pem = parsed.getStr("data");
            if (pem == null || pem.trim().isEmpty()) throw new BizException(502, "门户公钥为空");
            return pem;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[sso] public-key fetch error url={} err={}", url, e.toString());
            throw new BizException(502, "门户公钥不可达，请稍后重试或联系管理员");
        }
    }

    /** 兼容 {code:200} / {code:0} / {success:true} 三种成功形态 */
    static boolean isSuccess(JSONObject r) {
        if (r == null) return false;
        if (r.containsKey("success")) return r.getBool("success", false);
        Integer code = r.getInt("code");
        return code != null && (code == 200 || code == 0);
    }
}
