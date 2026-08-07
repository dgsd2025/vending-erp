package top.aole.vend.modules.doc.domain.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * "单据已确认"领域事件:库存引擎唯一的写入触发源。
 * 同步(同事务)派发——过账失败(如负库存拦截)会连同确认动作一起回滚。
 */
@Getter
@RequiredArgsConstructor
public class DocConfirmedEvent {

    /** 过账阶段:预挂单只锁仓库侧;预挂单转正/冲抵补机器侧 */
    public enum PostingPhase {
        /** 常规:按 doc_type 方向仓库+机器两侧全过账 */
        FULL,
        /** 预挂单(P0-4):只写仓库侧锁定,不写机器库存 */
        WAREHOUSE_ONLY,
        /** 预挂单超48h转正:仓库侧已锁,只补机器侧 */
        MACHINE_ONLY
    }

    private final DocHead head;
    private final List<DocItem> items;
    private final PostingPhase phase;
    /**
     * 业务时间戳:落 stock_ledger.biz_time,机器库存增量推算按它排序(修穿行场景11乱序导入)。
     * 导入通道(M1-3)应传后台补货记录的真实时间;为空时退化为 biz_date 当日 00:00。
     */
    private final LocalDateTime bizTime;
}
