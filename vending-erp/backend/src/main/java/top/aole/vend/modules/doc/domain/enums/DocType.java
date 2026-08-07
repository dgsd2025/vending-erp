package top.aole.vend.modules.doc.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 单据类型十枚举(与 yc_vend_doc_head.doc_type 中文值一一对应)。
 *
 * 每个类型自带库存方向配置(仓库/机器各 +1/-1/0),库存引擎按此过账,禁止散落 if-else。
 * 红冲=按原单方向反向过账;成本调整=qty=0/amount=Δ 调整流水(两者 M1-7 已实现);
 * 资金调整 postingDeferred=true(M3 生成 cash_flow,引擎跳过并留 TODO)。
 */
@Getter
public enum DocType {

    /** 采购入库:仓库 + */
    PURCHASE_IN("采购入库", "RK", 1, 0, false, false),
    /** 出库上架(仓库→机器转移单):仓库 − 机器 +;唯一生产者=后台补货记录导入(P0-4),手工单一律预挂单 */
    TRANSFER_OUT("出库上架", "CK", -1, 1, false, false),
    /** 退库(机器→仓库逆向转移):机器 − 仓库 + */
    RETURN_BACK("退库", "TK", 1, -1, false, false),
    /** 盘盈入库:仓库 + */
    GAIN_IN("盘盈入库", "PY", 1, 0, false, false),
    /** 盘亏出库:仓库 − */
    LOSS_OUT("盘亏出库", "PK", -1, 0, false, false),
    /** 报损:仓库 − */
    DAMAGE("报损", "BS", -1, 0, false, false),
    /** 红冲(M1-7 已实现):按原单 doc_type 方向反向过账(red_flush_of 指原单),免负库存拦截 */
    RED_FLUSH("红冲", "HC", 0, 0, true, false),
    /** 成本调整(M1-7 已实现,附录C):未售部分插 qty=0/amount=Δ 的 ledger 调整流水;已售进利润表"成本调整"行(pl_line,M3 报表实装),不追溯 */
    COST_ADJUST("成本调整", "CB", 0, 0, false, false),
    /** 资金调整(P1-7 钱盘差异唯一出口):不动库存,确认后生成 cash_flow,M3 */
    CASH_ADJUST("资金调整", "ZJ", 0, 0, false, true),
    /** 期初(上线向导一次性):仓库 +,天然豁免负库存 */
    OPENING("期初", "QC", 1, 0, true, false);

    /** 落库中文值 */
    @EnumValue
    @JsonValue
    private final String label;
    /** 单据号前缀 */
    private final String prefix;
    /** 仓库方向:+1 入 / -1 出 / 0 不动 */
    private final int warehouseDirection;
    /** 机器方向:+1 入 / -1 出 / 0 不动 */
    private final int machineDirection;
    /** 是否默认豁免负库存拦截(期初/红冲) */
    private final boolean negExemptByDefault;
    /** 过账逻辑是否延后实现(仅剩 资金调整→M3 生成 cash_flow;红冲/成本调整 M1-7 已实现) */
    private final boolean postingDeferred;

    DocType(String label, String prefix, int warehouseDirection, int machineDirection,
            boolean negExemptByDefault, boolean postingDeferred) {
        this.label = label;
        this.prefix = prefix;
        this.warehouseDirection = warehouseDirection;
        this.machineDirection = machineDirection;
        this.negExemptByDefault = negExemptByDefault;
        this.postingDeferred = postingDeferred;
    }

    /** 是否为仓库↔机器转移类单据(出库上架/退库) */
    public boolean isTransfer() {
        return machineDirection != 0;
    }
}
