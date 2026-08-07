package top.aole.vend.modules.bi.service;

import top.aole.vend.modules.bi.mapper.BiQueryMapper.BiMachineLedgerRow;
import top.aole.vend.modules.bi.mapper.BiQueryMapper.BiSaleRow;
import top.aole.vend.modules.bi.mapper.BiQueryMapper.BiSnapshotRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 缺货轨迹估算(M4-1,§10.1「缺货损失估算」):
 * 机器×SKU 的日末库存轨迹 = 最近快照锚点 + 之后的增量(转移 ± / 销售 −,按业务时间戳),
 * 与 StockService「锚点+增量推算」同一口径,只是逐日回放而非只算当下。
 *
 * 诚实边界:
 * - 锚点(machine_stock_snapshot)之前的天数无从推算 → 不计入 coverage,不造假;
 * - 缺货日 = 覆盖日的日末推算库存 ≤ 0;
 * - 纯静态工具类(无状态),单测可直打。
 */
public final class StockoutEstimator {

    private StockoutEstimator() {
    }

    /** 单机×单 SKU 的月度轨迹结果 */
    public static class Trace {
        /** 月内可推算的天数(第一个可用锚点当天起) */
        public int coverageDays;
        /** 日末库存 ≤0 的天数 */
        public int stockoutDays;
        /** 覆盖日中有货(日末>0)的天数 */
        public int inStockDays;
    }

    /**
     * 回放 [monthStart, monthEndInclusive] 逐日日末库存。
     * snapshots/ledgers/sales 必须是同一机器×SKU 的行、按业务时间升序;
     * 无任何快照锚点 → 返回 null(算不了,不算)。
     *
     * 锚点选择:取 ≤ 当前时点的最近快照作为重置点(快照优先于同刻增量);
     * 月中出现新快照 → 轨迹在该刻重置(对齐"与后台对账,差异大提示盘点"的锚点语义)。
     */
    public static Trace trace(LocalDate monthStart, LocalDate monthEndInclusive,
                              List<BiSnapshotRow> snapshots,
                              List<BiMachineLedgerRow> ledgers,
                              List<BiSaleRow> sales) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        // 起点锚:≤ 月初 0 点的最近快照;没有 → 用月内第一个快照(其前的天数不覆盖)
        LocalDateTime monthStartTime = monthStart.atStartOfDay();
        BiSnapshotRow anchor = null;
        for (BiSnapshotRow s : snapshots) {
            if (!s.getSnapshotTime().isAfter(monthStartTime)) {
                anchor = s;
            }
        }
        LocalDate coverFrom;
        BigDecimal stock;
        LocalDateTime cursor;
        if (anchor != null) {
            coverFrom = monthStart;
            stock = anchor.getQty();
            cursor = anchor.getSnapshotTime();
        } else {
            BiSnapshotRow first = null;
            for (BiSnapshotRow s : snapshots) {
                if (s.getSnapshotTime().isAfter(monthStartTime)
                        && !s.getSnapshotTime().toLocalDate().isAfter(monthEndInclusive)) {
                    first = s;
                    break;
                }
            }
            if (first == null) {
                return null; // 月内外都够不着锚点
            }
            coverFrom = first.getSnapshotTime().toLocalDate();
            stock = first.getQty();
            cursor = first.getSnapshotTime();
        }

        // 合并事件流:快照(重置)、转移(±)、销售(−;退款 +;测试不动货)
        List<Object[]> events = new ArrayList<>(); // [time, kind(0快照/1转移/2销售), value]
        for (BiSnapshotRow s : snapshots) {
            if (s.getSnapshotTime().isAfter(cursor)) {
                events.add(new Object[]{s.getSnapshotTime(), 0, s.getQty()});
            }
        }
        for (BiMachineLedgerRow l : ledgers) {
            if (l.getBizTime().isAfter(cursor)) {
                events.add(new Object[]{l.getBizTime(), 1, l.getChangeQty()});
            }
        }
        for (BiSaleRow s : sales) {
            if (!s.getBizTime().isAfter(cursor)) {
                continue;
            }
            String type = s.getOrderType() == null ? "正常" : s.getOrderType();
            // 与 StockService 锚点+增量口径完全一致:仅 正常+兑换 扣机内货(退款/测试/线下补录不动)
            if (!"正常".equals(type) && !"兑换".equals(type)) {
                continue;
            }
            events.add(new Object[]{s.getBizTime(), 2, s.getQty().negate()});
        }
        events.sort((a, b) -> {
            int c = ((LocalDateTime) a[0]).compareTo((LocalDateTime) b[0]);
            if (c != 0) {
                return c;
            }
            return Integer.compare((Integer) a[1], (Integer) b[1]); // 同刻:快照先重置,再增量
        });

        Trace t = new Trace();
        int idx = 0;
        for (LocalDate day = coverFrom; !day.isAfter(monthEndInclusive); day = day.plusDays(1)) {
            LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
            while (idx < events.size() && ((LocalDateTime) events.get(idx)[0]).isBefore(dayEnd)) {
                Object[] e = events.get(idx++);
                BigDecimal v = (BigDecimal) e[2];
                if ((Integer) e[1] == 0) {
                    stock = v; // 快照重置
                } else {
                    stock = stock.add(v);
                }
            }
            t.coverageDays++;
            if (stock.signum() <= 0) {
                t.stockoutDays++;
            } else {
                t.inStockDays++;
            }
        }
        return t;
    }
}
