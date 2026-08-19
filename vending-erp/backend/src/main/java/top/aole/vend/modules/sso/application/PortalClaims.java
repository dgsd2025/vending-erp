package top.aole.vend.modules.sso.application;

import lombok.Value;

/** 门户 JWT 验签通过后抽出的关键 claims（只取本系统需要的字段）。 */
@Value
public class PortalClaims {
    /** 门户 yc_portal_member.id 字符串形态（长期身份键，硬约束 8） */
    String portalUid;
    /** 门户租户 ID（String 形态，可能为空） */
    String tenantId;
    String name;
    String phone;
    String issuer;
}
