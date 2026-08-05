package top.aole.vend.modules.doc.domain;

import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static top.aole.vend.modules.doc.domain.enums.DocStatus.*;

/**
 * 单据状态机:按 doc_type 配置合法流转,非法流转抛 BizException。
 *
 * 基础流转(所有类型):
 *   草稿 → 待确认 / 已作废
 *   待确认 → 已确认 / 草稿(退回) / 已作废
 *   已确认 → 已完成 / 已红冲
 * 采购入库追加(M3 应付环节):已确认 → 待结算 → 已结算 → 已完成
 * 出库上架追加(P0-4 预挂单):待确认 → 预挂单;预挂单 → 已确认(超48h转正) / 已作废(被导入单冲抵)
 *
 * 硬规则:单据不许删(全系统无 delete 入口);已确认后唯一出路=完成或红冲。
 */
public final class DocStateMachine {

    /** 基础流转表 */
    private static final Map<DocStatus, Set<DocStatus>> BASE = new EnumMap<>(DocStatus.class);
    /** 采购入库专属追加 */
    private static final Map<DocStatus, Set<DocStatus>> PURCHASE_EXTRA = new EnumMap<>(DocStatus.class);
    /** 出库上架专属追加 */
    private static final Map<DocStatus, Set<DocStatus>> TRANSFER_EXTRA = new EnumMap<>(DocStatus.class);

    static {
        BASE.put(DRAFT, EnumSet.of(PENDING_CONFIRM, VOID));
        BASE.put(PENDING_CONFIRM, EnumSet.of(CONFIRMED, DRAFT, VOID));
        BASE.put(CONFIRMED, EnumSet.of(COMPLETED, RED_FLUSHED));

        PURCHASE_EXTRA.put(CONFIRMED, EnumSet.of(PENDING_SETTLE, COMPLETED, RED_FLUSHED));
        PURCHASE_EXTRA.put(PENDING_SETTLE, EnumSet.of(SETTLED));
        PURCHASE_EXTRA.put(SETTLED, EnumSet.of(COMPLETED));

        TRANSFER_EXTRA.put(PENDING_CONFIRM, EnumSet.of(CONFIRMED, PRE_PENDING, DRAFT, VOID));
        TRANSFER_EXTRA.put(PRE_PENDING, EnumSet.of(CONFIRMED, VOID));
    }

    private DocStateMachine() {
    }

    /** 查询 docType 在 from 状态下允许流向的目标集合 */
    public static Set<DocStatus> allowedTargets(DocType docType, DocStatus from) {
        Set<DocStatus> extra = extraOf(docType).get(from);
        if (extra != null) {
            return extra;
        }
        return BASE.getOrDefault(from, Collections.emptySet());
    }

    /** 校验流转合法性,非法抛 BizException(带类型/来去状态,人话报错) */
    public static void assertTransition(DocType docType, DocStatus from, DocStatus to) {
        if (!allowedTargets(docType, from).contains(to)) {
            throw new BizException(String.format(
                    "非法状态流转:[%s]单据不允许从[%s]流转到[%s]",
                    docType.getLabel(), from.getLabel(), to.getLabel()));
        }
    }

    private static Map<DocStatus, Set<DocStatus>> extraOf(DocType docType) {
        if (docType == DocType.PURCHASE_IN) {
            return PURCHASE_EXTRA;
        }
        if (docType == DocType.TRANSFER_OUT) {
            return TRANSFER_EXTRA;
        }
        return Collections.emptyMap();
    }
}
