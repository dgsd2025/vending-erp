package top.aole.vend.modules.stocktake.money.domain.enums;

import lombok.Getter;
import top.aole.vend.common.exception.BizException;

/**
 * 资金调整原因枚举(M3-5,审计 P1-7:账户/金额/原因枚举/老板确认→流水)。
 *
 * 方向约束写死在枚举里(建单时校验,防"盘盈却填负数"这类脑抽):
 * - 盘盈:实际 > 系统 → 只能收;
 * - 盘亏 / 手续费漏记:实际 < 系统(漏记支出=系统偏高)→ 只能支;
 * - 期初错 / 其他:两个方向都可能;其他必填备注(留痕才许兜底)。
 */
@Getter
public enum CashAdjustReason {

    /** 盘盈(实际多于系统)→ 收 */
    SURPLUS("盘盈", Direction.IN_ONLY, false),
    /** 盘亏(实际少于系统)→ 支 */
    DEFICIT("盘亏", Direction.OUT_ONLY, false),
    /** 手续费漏记(漏记支出,系统余额偏高)→ 支 */
    FEE_MISSED("手续费漏记", Direction.OUT_ONLY, false),
    /** 期初余额设错(期初只能设一次,修正一律走本通道)→ 可收可支 */
    OPENING_ERROR("期初错", Direction.BOTH, false),
    /** 其他(必备注) */
    OTHER("其他", Direction.BOTH, true);

    public enum Direction { IN_ONLY, OUT_ONLY, BOTH }

    /** 落库中文值 */
    private final String label;
    /** 允许方向 */
    private final Direction allowed;
    /** 是否强制备注 */
    private final boolean remarkRequired;

    CashAdjustReason(String label, Direction allowed, boolean remarkRequired) {
        this.label = label;
        this.allowed = allowed;
        this.remarkRequired = remarkRequired;
    }

    public static CashAdjustReason ofLabel(String label) {
        for (CashAdjustReason r : values()) {
            if (r.label.equals(label)) {
                return r;
            }
        }
        throw new BizException("未知资金调整原因:" + label + "(可选:盘盈/盘亏/手续费漏记/期初错/其他)");
    }

    /** 方向合法性校验(direction=收/支) */
    public void assertDirection(String direction) {
        boolean in = "收".equals(direction);
        if (allowed == Direction.IN_ONLY && !in) {
            throw new BizException("原因「" + label + "」= 实际多于系统,方向只能是「收」(正数调增)");
        }
        if (allowed == Direction.OUT_ONLY && in) {
            throw new BizException("原因「" + label + "」= 实际少于系统,方向只能是「支」(负数调减)");
        }
    }
}
