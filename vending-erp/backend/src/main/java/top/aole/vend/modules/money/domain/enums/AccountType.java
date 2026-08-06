package top.aole.vend.modules.money.domain.enums;

import lombok.Getter;
import top.aole.vend.common.exception.BizException;

/**
 * 资金账户类型:真实 4 类 + 虚拟 2 类(§7.4)。
 * 虚拟账户不参与钱盘实数核对,余额由业务流水推算,不可手工收支。
 */
@Getter
public enum AccountType {

    WECHAT("微信", false),
    ALIPAY("支付宝", false),
    BANK_CARD("银行卡", false),
    CASH("现金", false),
    /** 平台待结算(PLATFORM 模式虚账:销售发生即入,结算单核销;DIRECT 模式不存在,附录D) */
    PLATFORM_PENDING("平台待结算", true),
    /** 老板垫付(个人卡代付经营支出的往来虚账) */
    BOSS_ADVANCE("老板垫付", true);

    /** 落库中文值(account.account_type) */
    private final String label;
    /** 是否虚拟账户 */
    private final boolean virtual;

    AccountType(String label, boolean virtual) {
        this.label = label;
        this.virtual = virtual;
    }

    public static AccountType ofLabel(String label) {
        for (AccountType t : values()) {
            if (t.label.equals(label)) {
                return t;
            }
        }
        throw new BizException("未知账户类型:" + label + "(可选:微信/支付宝/银行卡/现金/平台待结算/老板垫付)");
    }
}
