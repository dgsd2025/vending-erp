package top.aole.vend.modules.basedata.interfaces;

import cn.hutool.core.util.StrUtil;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * 经手人占位方案:从 header X-User-Name 取操作人姓名(SSO 接入后替换)。
 * 中文名走 HTTP 头会被 percent-encode,这里兼容解码;没传时记「未知操作人」。
 */
public final class Operators {

    public static final String HEADER = "X-User-Name";

    private Operators() {
    }

    public static String resolve(String rawHeader) {
        if (StrUtil.isBlank(rawHeader)) {
            return "未知操作人";
        }
        String value = rawHeader.trim();
        if (value.contains("%")) {
            try {
                value = URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException ignore) {
                // 解码失败就按原样记
            }
        }
        return value;
    }
}
