package top.aole.vend.modules.stock.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水写手(包私有):stock_ledger 的唯一写入通道。
 *
 * 刻意 **不是 public 类**——doc 模块只能通过"单据已确认"事件间接触发写入
 * (StockPostingListener),全系统不存在"绕开单据直改库存"的公开方法。
 * 负库存拦截(P1-8)在这里执行:手工单默认拦截,豁免单放行并留 neg_stock_exempt 标记。
 */
@Component
@RequiredArgsConstructor
class StockLedgerWriter {

    private final StockLedgerMapper stockLedgerMapper;

    /**
     * 按单据明细写一条仓库账流水。
     *
     * @param signedQty 带方向数量(+入/-出)
     */
    void postWarehouse(DocHead head, DocItem item, BigDecimal signedQty, LocalDateTime bizTime) {
        // P1-3 并发透支修复:非豁免出库先锁该 SKU 商品行(FOR UPDATE)把"查余额+写流水"
        // 串行化,再用**当前读**(SUM ... FOR UPDATE)取最新已提交余额——快照读等到锁后
        // 读到的还是旧余额,拦截照样被穿透。P0-A 条件更新只防同一张单双过账,
        // 防不了两张不同单并发透支同一 SKU。豁免单(导入/红冲/期初)允许负库存,无需排队。
        BigDecimal current;
        if (signedQty.signum() < 0 && !Boolean.TRUE.equals(head.getNegStockExempt())) {
            stockLedgerMapper.lockProductRow(item.getProductId());
            current = nvl(stockLedgerMapper.sumWarehouseCurrent(item.getProductId()));
        } else {
            current = nvl(stockLedgerMapper.sumWarehouse(item.getProductId()));
        }
        BigDecimal after = current.add(signedQty);
        if (after.compareTo(BigDecimal.ZERO) < 0 && !Boolean.TRUE.equals(head.getNegStockExempt())) {
            throw new BizException(String.format(
                    "负库存拦截:商品[%d]仓库当前库存%s,本单需出库%s,差%s。" +
                            "请先补录采购入库;导入通道/红冲可豁免(neg_stock_exempt)",
                    item.getProductId(), current.stripTrailingZeros().toPlainString(),
                    signedQty.abs().stripTrailingZeros().toPlainString(),
                    after.abs().stripTrailingZeros().toPlainString()));
        }
        insert(head, item, StockLedger.LOC_WAREHOUSE, null, signedQty, after, bizTime);
    }

    /**
     * 成本调整流水(M1-7,附录C 过账契约):qty=0、amount=Δ金额,只动金额不动数量。
     * 移动加权引擎(M1-6)按 ledger 时序遍历时,qty=0 行只累加金额 → 单位成本随之变;
     * 结存数量不变(balance=当前值),不做负库存拦截(数量为 0 天然无拦截语义)。
     */
    void postCostAdjust(DocHead head, DocItem item, BigDecimal deltaAmount, LocalDateTime bizTime) {
        BigDecimal current = nvl(stockLedgerMapper.sumWarehouse(item.getProductId()));
        StockLedger ledger = new StockLedger();
        ledger.setProductId(item.getProductId());
        ledger.setLocationType(StockLedger.LOC_WAREHOUSE);
        ledger.setDocId(head.getId());
        ledger.setDocItemId(item.getId());
        ledger.setChangeQty(BigDecimal.ZERO);
        ledger.setBalanceQty(current);
        ledger.setUnitCost(null); // qty=0 行无单价语义;金额进 amount,加权引擎按契约只动金额
        ledger.setAmount(deltaAmount);
        ledger.setBizTime(bizTime);
        ledger.setCreateUser(head.getConfirmBy());
        stockLedgerMapper.insert(ledger);
    }

    /** 按单据明细写一条机器账流水(机器侧不做负库存拦截,权威在后台,差异走盘点) */
    void postMachine(DocHead head, DocItem item, BigDecimal signedQty, LocalDateTime bizTime) {
        BigDecimal current = nvl(stockLedgerMapper.sumMachineTransferAfter(
                head.getMachineId(), item.getProductId(), null));
        insert(head, item, StockLedger.LOC_MACHINE, head.getMachineId(),
                signedQty, current.add(signedQty), bizTime);
    }

    private void insert(DocHead head, DocItem item, String locationType, Long machineId,
                        BigDecimal changeQty, BigDecimal balanceQty, LocalDateTime bizTime) {
        StockLedger ledger = new StockLedger();
        ledger.setProductId(item.getProductId());
        ledger.setLocationType(locationType);
        ledger.setMachineId(machineId);
        ledger.setDocId(head.getId());
        ledger.setDocItemId(item.getId());
        ledger.setChangeQty(changeQty);
        ledger.setBalanceQty(balanceQty);
        // TODO(M1-6 采购/成本):移动加权成本引擎接上后,出库按加权成本、入库按单价重算;
        //   当前先落单据单价快照,无价单据为 NULL(禁 0 参与加权,§13 场景7)
        BigDecimal unitCost = item.getUnitPrice() == null
                || item.getUnitPrice().compareTo(BigDecimal.ZERO) == 0 ? null : item.getUnitPrice();
        ledger.setUnitCost(unitCost);
        ledger.setAmount(unitCost == null ? null : changeQty.multiply(unitCost));
        ledger.setBizTime(bizTime);
        ledger.setCreateUser(head.getConfirmBy());
        stockLedgerMapper.insert(ledger);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
