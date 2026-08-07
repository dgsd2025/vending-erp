package top.aole.vend.modules.money.domain.enums;

import lombok.Getter;

/**
 * 利润表行映射(附录D 定死,P0-6 / §13.1):每类资金流水在利润表**有且只有一个去处**。
 *
 * 简版利润表:销售收入 − 销售成本 = 毛利;毛利 − 平台手续费 − 杂费 − 损耗 ± 成本调整
 * + 其他收入(赔付/平台外/补贴)= 经营利润。
 * 「不进利润表」= 本金往来类(付供应商货款/账户互转/平台结算划转/资金调整),
 * 只动资产负债不动损益。
 *
 * 铁律:pl_line 由 {@link CashFlowCategory} 唯一推导,任何写入方不许自行指定——
 * 这是"每类流水有且只有一个去处"的代码级保证(CashFlowWriter 强制)。
 */
@Getter
public enum PlLine {

    /** 销售收入(正常单实收;退款为负) */
    SALES_INCOME("销售收入", true),
    /** 销售成本(移动加权结转) */
    SALES_COST("销售成本", true),
    /** 平台手续费("平台这个月吃掉了我多少钱") */
    PLATFORM_FEE("平台手续费", true),
    /** 杂费(电费/维修/杂支/设备购置,§7.4 设备台账仅展示不进流水,购置支出进此行) */
    MISC_EXPENSE("杂费", true),
    /** 损耗(盘亏/报损;净损耗=损耗−已获赔,报表逻辑) */
    SHRINKAGE("损耗", true),
    /** 成本调整(P0-1 单价错已售部分,不追溯,进此行承接) */
    COST_ADJUST("成本调整", true),
    /** 其他收入-赔付(索赔到账,§13 场景6) */
    OTHER_INCOME_CLAIM("其他收入-赔付", true),
    /** 其他收入-平台外(机器故障线下直收,§13 场景5) */
    OTHER_INCOME_OFFLINE("其他收入-平台外", true),
    /** 其他收入-补贴(厂家兑换/活动补贴到账) */
    OTHER_INCOME_SUBSIDY("其他收入-补贴", true),
    /** 不进利润表:本金往来类(付供应商货款/账户互转/平台结算划转/资金调整) */
    NON_PL("不进利润表", false);

    /** 落库中文值(cash_flow.pl_line) */
    private final String label;
    /** 是否参与利润表聚合 */
    private final boolean inProfitLoss;

    PlLine(String label, boolean inProfitLoss) {
        this.label = label;
        this.inProfitLoss = inProfitLoss;
    }
}
