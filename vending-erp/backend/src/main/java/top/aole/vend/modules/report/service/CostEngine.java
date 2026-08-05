package top.aole.vend.modules.report.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.report.dto.ReportDtos.LedgerEvent;
import top.aole.vend.modules.report.dto.ReportDtos.SaleEvent;
import top.aole.vend.modules.report.mapper.ReportQueryMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 移动加权成本引擎(M1-6,按 DESIGN_DOC 附录C 成本调整过账契约实现)。
 *
 * 事件流 = 仓库账流水(采购入库/期初/盘盈/盘亏/报损/成本调整…) + 销售记录,按业务时间时序合并遍历:
 * - 入库行(qty>0 带金额):数量、金额同步累加,单位成本随之重算;
 *   负库存时入库 = 从 0 重建金额池(负数量欠账保留,负值挂账丢弃——对齐冲刺0对平算法 s0_3_gross.py);
 * - qty=0 成本调整行:只动金额,单位成本随之变(附录C,M1-7 成本调整单过账产物);
 * - 出库行(盘亏/报损):按当前单位成本结转;
 * - 转移行(出库上架/退库):只是货换了地方,不动全局成本池,仅记单位成本快照;
 * - 销售:按当前单位成本结转(退款=逆向回池);负库存沿用最近均价,兜底=至今累计加权价;
 * - 无采购史 SKU:单位成本=NULL,毛利显「—(成本待补)」,禁 0 参与加权(§13.2-6)。
 *
 * 全程动态重算(数据量小,5k 销售 + 数百流水毫秒级),支撑任意按月切片;
 * persist=true 时把成本快照回写 sale_record.cost_amount 与 ledger 出库/转移行(M3 结算/M4 BI 用)。
 */
@Service
@RequiredArgsConstructor
public class CostEngine {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int SCALE = 6;

    /** 转移类单据:全局成本池无感(货只是换地方) */
    private static final String TYPE_TRANSFER_OUT = "出库上架";
    private static final String TYPE_RETURN_BACK = "退库";

    private final ReportQueryMapper reportQueryMapper;

    // ============================== 结果结构 ==============================

    /** 单 SKU 成本池(附录C 状态机) */
    @Data
    public static class Pool {
        private BigDecimal qty = BigDecimal.ZERO;
        private BigDecimal val = BigDecimal.ZERO;
        /** 最近一次结转均价(负库存沿用) */
        private BigDecimal lastAvg;
        /** 累计入库(有金额的):兜底加权价 = inbAmt / inbQty */
        private BigDecimal inbQty = BigDecimal.ZERO;
        private BigDecimal inbAmt = BigDecimal.ZERO;

        /** 是否有采购史(无 → 成本 NULL,禁 0 加权) */
        public boolean hasCost() {
            return inbQty.signum() > 0;
        }

        /** 当前单位成本(展示口径):池内>0 用池均价,否则最近均价,兜底累计加权;无采购史 null */
        public BigDecimal currentAvg() {
            if (!hasCost()) {
                return null;
            }
            if (qty.signum() > 0) {
                return val.divide(qty, SCALE, RoundingMode.HALF_UP);
            }
            if (lastAvg != null) {
                return lastAvg;
            }
            return inbAmt.divide(inbQty, SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 按 (key, 月) 的销售聚合 */
    @Data
    public static class MonthAgg {
        private BigDecimal salesQty = BigDecimal.ZERO;
        /** 正常 + 退款(负);兑换/线下补录收入 0 */
        private BigDecimal salesAmt = BigDecimal.ZERO;
        private BigDecimal costAmt = BigDecimal.ZERO;
        /** 出现过无成本销售(SKU 维=整行无成本;机器维=部分无成本) */
        private boolean noCost;
        private int noCostSkuCount;
    }

    /** 按 (productId, 月) 的进销存聚合(全局口径:仓库+机器合计) */
    @Data
    public static class InvAgg {
        private BigDecimal inQty = BigDecimal.ZERO;
        private BigDecimal inAmt = BigDecimal.ZERO;
        private BigDecimal outQty = BigDecimal.ZERO;
        private BigDecimal outAmt = BigDecimal.ZERO;
        /** 期初(上月期末结转) */
        private BigDecimal openingQty = BigDecimal.ZERO;
        private BigDecimal openingVal = BigDecimal.ZERO;
        /** 月末池快照 */
        private BigDecimal closingQty = BigDecimal.ZERO;
        private BigDecimal closingVal = BigDecimal.ZERO;
        private boolean snapshotTaken;
    }

    @Data
    public static class Replay {
        /** productId+月 → 销售聚合(未绑定行 productId 用 UNBOUND_KEY) */
        private Map<String, MonthAgg> skuMonth = new LinkedHashMap<>();
        /** machineId+月 → 销售聚合 */
        private Map<String, MonthAgg> machineMonth = new LinkedHashMap<>();
        /** productId+月 → 进销存聚合 */
        private Map<String, InvAgg> invMonth = new LinkedHashMap<>();
        /** 各 SKU 终态成本池 */
        private Map<Long, Pool> pools = new HashMap<>();
        /** saleId → 结转成本(退款为负;无成本 SKU 不在内) */
        private Map<Long, BigDecimal> saleCost = new HashMap<>();
        /** 无成本 SKU 的销售行(cost_amount 应回写 NULL) */
        private List<Long> noCostSaleIds = new ArrayList<>();
        /** ledgerId → 单位成本快照(出库/转移行;无成本 SKU 不在内) */
        private Map<Long, BigDecimal> ledgerUnitCost = new HashMap<>();
        /** 事件流出现过的全部月份(升序,无空洞——中间月补齐) */
        private List<String> months = new ArrayList<>();

        public static final long UNBOUND_KEY = -1L;

        public static String key(long id, String period) {
            return id + "" + period;
        }
    }

    // ============================== 重放 ==============================

    public Replay replay() {
        List<LedgerEvent> ledgers = reportQueryMapper.ledgerEvents();
        List<SaleEvent> sales = reportQueryMapper.saleEvents();
        Replay r = new Replay();
        TreeSet<String> monthSet = new TreeSet<>();
        // 每 SKU 月度池快照(月内最后一次事件后的状态)
        Map<Long, TreeMap<String, BigDecimal[]>> poolSnaps = new HashMap<>();

        int li = 0;
        int si = 0;
        while (li < ledgers.size() || si < sales.size()) {
            boolean takeLedger;
            if (li >= ledgers.size()) {
                takeLedger = false;
            } else if (si >= sales.size()) {
                takeLedger = true;
            } else {
                // 同一时刻:先入库后销售(对齐冲刺0事件排序 (t, kind))
                takeLedger = !ledgers.get(li).getBizTime().isAfter(sales.get(si).getBizTime());
            }
            if (takeLedger) {
                LedgerEvent e = ledgers.get(li++);
                String period = e.getBizTime().format(PERIOD);
                monthSet.add(period);
                applyLedger(r, e, period, poolSnaps);
            } else {
                SaleEvent e = sales.get(si++);
                String period = e.getBizPeriod() != null ? e.getBizPeriod() : e.getBizTime().format(PERIOD);
                monthSet.add(period);
                applySale(r, e, period, poolSnaps);
            }
        }

        // 月份序列补齐空洞(进销存期初期末要连续结转)
        if (!monthSet.isEmpty()) {
            java.time.YearMonth m = java.time.YearMonth.parse(monthSet.first());
            java.time.YearMonth last = java.time.YearMonth.parse(monthSet.last());
            while (!m.isAfter(last)) {
                r.getMonths().add(m.toString());
                m = m.plusMonths(1);
            }
        }

        // 进销存:按月份序列为每个 SKU 结转 期初/期末
        fillInventoryCarry(r, poolSnaps);
        return r;
    }

    private void applyLedger(Replay r, LedgerEvent e, String period,
                             Map<Long, TreeMap<String, BigDecimal[]>> poolSnaps) {
        Pool pool = r.getPools().computeIfAbsent(e.getProductId(), k -> new Pool());
        String type = e.getDocType();
        boolean transfer = TYPE_TRANSFER_OUT.equals(type) || TYPE_RETURN_BACK.equals(type);
        if (transfer) {
            // 转移:全局池无感,只记单位成本快照(附录C:出库行按当前单位成本结转)
            BigDecimal avg = pool.currentAvg();
            if (avg != null) {
                r.getLedgerUnitCost().put(e.getId(), avg);
            }
            return;
        }

        InvAgg inv = r.getInvMonth().computeIfAbsent(
                Replay.key(e.getProductId(), period), k -> new InvAgg());
        int sign = e.getChangeQty().signum();
        if (sign > 0) {
            // 入库:数量、金额同步累加;负库存时金额池从 0 重建(负值挂账丢弃,对齐冲刺0)
            BigDecimal amt = e.getAmount();
            if (amt == null) {
                // 无金额入库(如盘盈无价):按当前均价折算,不改变单位成本
                BigDecimal avg = pool.currentAvg();
                amt = avg == null ? null : e.getChangeQty().multiply(avg);
            }
            BigDecimal baseVal = pool.getQty().signum() > 0 ? pool.getVal() : BigDecimal.ZERO;
            pool.setQty(pool.getQty().add(e.getChangeQty()));
            if (amt != null) {
                pool.setVal(baseVal.add(amt));
                pool.setInbQty(pool.getInbQty().add(e.getChangeQty()));
                pool.setInbAmt(pool.getInbAmt().add(amt));
                if (pool.getQty().signum() > 0) {
                    pool.setLastAvg(pool.getVal().divide(pool.getQty(), SCALE, RoundingMode.HALF_UP));
                }
                inv.setInAmt(inv.getInAmt().add(amt));
            }
            inv.setInQty(inv.getInQty().add(e.getChangeQty()));
        } else if (sign == 0) {
            // qty=0 成本调整行:只动金额,单位成本随之变(附录C)
            BigDecimal delta = e.getAmount() == null ? BigDecimal.ZERO : e.getAmount();
            pool.setVal(pool.getVal().add(delta));
            pool.setInbAmt(pool.getInbAmt().add(delta));
            if (pool.getQty().signum() > 0) {
                pool.setLastAvg(pool.getVal().divide(pool.getQty(), SCALE, RoundingMode.HALF_UP));
            }
            inv.setInAmt(inv.getInAmt().add(delta));
        } else {
            // 出库(盘亏/报损/红冲负行):按当前单位成本结转
            BigDecimal q = e.getChangeQty().abs();
            BigDecimal avg = pool.currentAvg();
            pool.setQty(pool.getQty().add(e.getChangeQty()));
            inv.setOutQty(inv.getOutQty().add(q));
            if (avg != null) {
                BigDecimal cost = q.multiply(avg);
                pool.setVal(pool.getVal().subtract(cost));
                pool.setLastAvg(avg);
                r.getLedgerUnitCost().put(e.getId(), avg);
                inv.setOutAmt(inv.getOutAmt().add(cost));
            }
        }
        snapPool(poolSnaps, e.getProductId(), period, pool);
    }

    private void applySale(Replay r, SaleEvent e, String period,
                           Map<Long, TreeMap<String, BigDecimal[]>> poolSnaps) {
        String type = e.getOrderType() == null ? "正常" : e.getOrderType();
        if ("测试".equals(type)) {
            return; // 三口径:测试不计(§13.2-3)
        }
        Long productKey = e.getProductId() == null ? Replay.UNBOUND_KEY : e.getProductId();
        MonthAgg sku = r.getSkuMonth().computeIfAbsent(Replay.key(productKey, period), k -> new MonthAgg());
        MonthAgg mac = e.getMachineId() == null ? null
                : r.getMachineMonth().computeIfAbsent(Replay.key(e.getMachineId(), period), k -> new MonthAgg());

        boolean refund = "退款".equals(type);
        boolean exchange = "兑换".equals(type);
        boolean offline = "线下补录".equals(type);
        // 销售额口径:正常全额、退款负(导出即负数,原样入);兑换/线下补录收入 0(§13.1 毛利口径 + 穿行场景4/5)
        BigDecimal amt = (exchange || offline) ? BigDecimal.ZERO : nvl(e.getAmountReceived());
        BigDecimal qty = nvl(e.getQty());
        sku.setSalesAmt(sku.getSalesAmt().add(amt));
        sku.setSalesQty(sku.getSalesQty().add(refund ? qty.negate() : qty));
        if (mac != null) {
            mac.setSalesAmt(mac.getSalesAmt().add(amt));
            mac.setSalesQty(mac.getSalesQty().add(refund ? qty.negate() : qty));
        }

        if (e.getProductId() == null) {
            sku.setNoCost(true);
            if (mac != null) {
                mac.setNoCost(true);
                mac.setNoCostSkuCount(mac.getNoCostSkuCount() + 1);
            }
            return;
        }
        Pool pool = r.getPools().computeIfAbsent(e.getProductId(), k -> new Pool());
        if (!pool.hasCost()) {
            // 无采购史:成本 NULL,毛利显「—」,禁 0 加权(§13.2-6);数量照常流转(进销存/负库存要看得见)
            sku.setNoCost(true);
            r.getNoCostSaleIds().add(e.getId());
            if (mac != null) {
                mac.setNoCost(true);
                mac.setNoCostSkuCount(mac.getNoCostSkuCount() + 1);
            }
            InvAgg invNc = r.getInvMonth().computeIfAbsent(
                    Replay.key(e.getProductId(), period), k -> new InvAgg());
            if (refund) {
                pool.setQty(pool.getQty().add(qty));
                invNc.setOutQty(invNc.getOutQty().subtract(qty));
            } else {
                pool.setQty(pool.getQty().subtract(qty));
                invNc.setOutQty(invNc.getOutQty().add(qty));
            }
            snapPool(poolSnaps, e.getProductId(), period, pool);
            return;
        }
        BigDecimal avg = pool.currentAvg();
        BigDecimal cost = qty.multiply(avg);
        InvAgg inv = r.getInvMonth().computeIfAbsent(
                Replay.key(e.getProductId(), period), k -> new InvAgg());
        if (refund) {
            // 退款逆向回池:收入负(原样)、成本负
            pool.setQty(pool.getQty().add(qty));
            pool.setVal(pool.getVal().add(cost));
            sku.setCostAmt(sku.getCostAmt().subtract(cost));
            if (mac != null) {
                mac.setCostAmt(mac.getCostAmt().subtract(cost));
            }
            r.getSaleCost().put(e.getId(), cost.negate().setScale(4, RoundingMode.HALF_UP));
            inv.setOutQty(inv.getOutQty().subtract(qty));
            inv.setOutAmt(inv.getOutAmt().subtract(cost));
        } else {
            pool.setQty(pool.getQty().subtract(qty));
            pool.setVal(pool.getVal().subtract(cost));
            pool.setLastAvg(avg);
            sku.setCostAmt(sku.getCostAmt().add(cost));
            if (mac != null) {
                mac.setCostAmt(mac.getCostAmt().add(cost));
            }
            r.getSaleCost().put(e.getId(), cost.setScale(4, RoundingMode.HALF_UP));
            inv.setOutQty(inv.getOutQty().add(qty));
            inv.setOutAmt(inv.getOutAmt().add(cost));
        }
        snapPool(poolSnaps, e.getProductId(), period, pool);
    }

    private static void snapPool(Map<Long, TreeMap<String, BigDecimal[]>> snaps,
                                 Long productId, String period, Pool pool) {
        snaps.computeIfAbsent(productId, k -> new TreeMap<>())
                .put(period, new BigDecimal[]{pool.getQty(), pool.getVal(), pool.hasCost() ? BigDecimal.ONE : BigDecimal.ZERO});
    }

    /** 按月份序列为每个 SKU 结转 期初/期末(无事件月照抄上月期末) */
    private void fillInventoryCarry(Replay r, Map<Long, TreeMap<String, BigDecimal[]>> poolSnaps) {
        for (Map.Entry<Long, TreeMap<String, BigDecimal[]>> e : poolSnaps.entrySet()) {
            Long productId = e.getKey();
            BigDecimal[] carry = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            for (String month : r.getMonths()) {
                BigDecimal[] snap = e.getValue().get(month);
                InvAgg inv = r.getInvMonth().computeIfAbsent(Replay.key(productId, month), k -> new InvAgg());
                // 期初 = 上月期末(carry);期末 = 本月快照(无事件 = 照抄期初)
                BigDecimal[] closing = snap != null ? snap : carry;
                inv.setOpeningQty(carry[0]);
                inv.setOpeningVal(carry[1]);
                inv.setClosingQty(closing[0]);
                inv.setClosingVal(closing[1]);
                inv.setSnapshotTaken(true);
                carry = closing;
            }
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
