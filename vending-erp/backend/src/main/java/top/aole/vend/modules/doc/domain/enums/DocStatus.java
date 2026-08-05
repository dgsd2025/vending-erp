package top.aole.vend.modules.doc.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 单据状态机九状态(与 yc_vend_doc_head.doc_status 中文值一一对应)。
 * 合法流转由 {@link top.aole.vend.modules.doc.domain.DocStateMachine} 按 doc_type 配置。
 */
@Getter
public enum DocStatus {

    /** 草稿:唯一可改状态 */
    DRAFT("草稿"),
    /** 预挂单(P0-4):手工转移单确认后进此态,只锁仓库侧不写机器库存,等导入冲抵;超48h转正 */
    PRE_PENDING("预挂单"),
    /** 待确认 */
    PENDING_CONFIRM("待确认"),
    /** 已确认:库存过账触发点;此后不可改,只能红冲 */
    CONFIRMED("已确认"),
    /** 待结算(采购入库进入应付环节,M3) */
    PENDING_SETTLE("待结算"),
    /** 已结算(M3) */
    SETTLED("已结算"),
    /** 已完成 */
    COMPLETED("已完成"),
    /** 已红冲(M1-7 红冲连锁完成后原单进此态) */
    RED_FLUSHED("已红冲"),
    /** 已作废(仅未过账单据/被冲抵的预挂单) */
    VOID("已作废");

    @EnumValue
    @JsonValue
    private final String label;

    DocStatus(String label) {
        this.label = label;
    }
}
