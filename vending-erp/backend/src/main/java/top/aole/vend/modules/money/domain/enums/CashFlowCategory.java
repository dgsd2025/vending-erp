package top.aole.vend.modules.money.domain.enums;

import lombok.Getter;
import top.aole.vend.common.exception.BizException;

/**
 * 资金流水业务类别(cash_flow.category)→ 利润表行(pl_line)一一映射。
 *
 * 附录D 铁三条之二:每类流水在利润表**有且只有一个去处**——去处写死在枚举里,
 * CashFlowWriter 落库时由类别推导 pl_line,写入方(M3-2 付款/M3-4 结算/M3-5 调整/M3-6 索赔)
 * 只报类别,不许自报去处。集成测试对完备性做断言(每类别有唯一去处 + 全部 pl_line 被覆盖)。
 *
 * manualAllowed:是否允许出现在"资金调整单"(唯一的手工收支出口,M3-5)——
 * 虚拟账户(平台待结算/老板垫付)不可手工收支(其余额永远由业务单据推算)。
 */
@Getter
public enum CashFlowCategory {

    // ---- 收入侧 ----
    /** 销售收款:PLATFORM 模式入"平台待结算"虚账;DIRECT 模式直接入商户号真实账户(附录D) */
    SALE_INCOME("销售收款", PlLine.SALES_INCOME),
    /** 销售成本结转(移动加权,月度报表过账用) */
    SALE_COST("销售成本", PlLine.SALES_COST),
    /** 索赔到账(M3-6:claim 申请中→已到账) */
    CLAIM_INCOME("索赔到账", PlLine.OTHER_INCOME_CLAIM),
    /** 线下收入(机器故障直收,复合单,§13 场景5) */
    OFFLINE_INCOME("线下收入", PlLine.OTHER_INCOME_OFFLINE),
    /** 兑换收款/厂家补贴到账(兑换成本对冲载体 deduction 的现金侧) */
    EXCHANGE_SUBSIDY("兑换收款", PlLine.OTHER_INCOME_SUBSIDY),

    // ---- 费用侧 ----
    /** 平台手续费(M3-4 平台结算单) */
    PLATFORM_FEE("平台手续费", PlLine.PLATFORM_FEE),
    /** 电费 */
    UTILITY("电费", PlLine.MISC_EXPENSE),
    /** 维修 */
    REPAIR("维修", PlLine.MISC_EXPENSE),
    /** 杂支 */
    MISC("杂支", PlLine.MISC_EXPENSE),
    /** 设备购置(支出单 is_equipment→设备台账;台账只展示,现金支出进杂费行) */
    EQUIPMENT("设备购置", PlLine.MISC_EXPENSE),
    /** 损耗(盘亏/报损涉现金部分) */
    SHRINK_LOSS("损耗", PlLine.SHRINKAGE),
    /** 成本调整(P0-1 已售部分进利润表调整行) */
    COST_ADJUST_FLOW("成本调整", PlLine.COST_ADJUST),

    // ---- 本金往来(不进利润表)----
    /** 付供应商货款(M3-2 付款单) */
    SUPPLIER_PAYMENT("供应商付款", PlLine.NON_PL),
    /** 平台结算划转(货款结算:平台待结算虚账 → 真实账户,M3-4) */
    SETTLE_TRANSFER("货款结算", PlLine.NON_PL),
    /** 账户互转(两条流水一支一收) */
    ACCOUNT_TRANSFER("账户互转", PlLine.NON_PL),
    /** 资金调整(钱盘差异唯一出口,M3-5;虚拟账户禁用) */
    CASH_ADJUST("资金调整", PlLine.NON_PL);

    /** 落库中文值(cash_flow.category) */
    private final String label;
    /** 利润表唯一去处 */
    private final PlLine plLine;

    CashFlowCategory(String label, PlLine plLine) {
        this.label = label;
        this.plLine = plLine;
    }

    public static CashFlowCategory ofLabel(String label) {
        for (CashFlowCategory c : values()) {
            if (c.label.equals(label)) {
                return c;
            }
        }
        throw new BizException("未知流水类别:" + label);
    }
}
