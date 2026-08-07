package top.aole.vend.modules.money.domain.enums;

import lombok.Getter;
import top.aole.vend.common.exception.BizException;

/**
 * 凭证类型(attachment.att_type,§9.4):无凭证不能进"已付款/已结算"(后续票强制)。
 * 「照片」为盘点/报损现场照通用类型(DDL 注释已列,一并支持)。
 */
@Getter
public enum AttachmentType {

    TRANSFER_SHOT("转账截图"),
    PLATFORM_BILL("平台账单"),
    CLAIM_PROOF("赔付凭证"),
    INVOICE("发票"),
    PHOTO("照片");

    private final String label;

    AttachmentType(String label) {
        this.label = label;
    }

    public static AttachmentType ofLabel(String label) {
        for (AttachmentType t : values()) {
            if (t.label.equals(label)) {
                return t;
            }
        }
        throw new BizException("未知凭证类型:" + label + "(可选:转账截图/平台账单/赔付凭证/发票/照片)");
    }
}
