<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  reportApi,
  type ProductOverviewResp,
  type StockLedgerRow,
} from '@/api/report'
import {
  pageAliases,
  pageOpLogs,
  pagePriceLogs,
  type OpLog,
  type PriceLog,
  type SkuAlias,
} from '@/api/basedata'
import { useAppStore } from '@/stores/app'

/**
 * 单品详情页(M1-9,对照 mockup p14「体检报告」,设计七律#3:任何名词都能点进去):
 * 关键数字卡(两级库存/30天销量/毛利率/加权成本/够卖天数)+ 销量走势 + 库存分布 +
 * 进出流水 + 大事记(op_log + price_log)。数据全部来自只读聚合接口 + 既有 basedata 查询。
 * 查询页,豁免任务流程条(七律#2 豁免条款)。
 */
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const data = ref<ProductOverviewResp | null>(null)
const ledgerRows = ref<StockLedgerRow[]>([])
const aliases = ref<SkuAlias[]>([])
const opLogs = ref<OpLog[]>([])
const priceLogs = ref<PriceLog[]>([])

const productId = computed(() => Number(route.params.id))

async function load() {
  if (!productId.value) return
  loading.value = true
  try {
    const [overview, ledger, aliasPage, ops, prices] = await Promise.all([
      reportApi.productOverview(productId.value),
      reportApi.productLedger(productId.value, 50),
      pageAliases({ productId: productId.value, size: 50 }),
      pageOpLogs({ targetType: 'product', targetId: productId.value, size: 20 }),
      pagePriceLogs({ productId: productId.value, size: 20 }),
    ])
    data.value = overview
    ledgerRows.value = ledger
    aliases.value = aliasPage.records
    opLogs.value = ops.records
    priceLogs.value = prices.records
    if (overview.dataAsOf) useAppStore().setDataAsOf(overview.dataAsOf.slice(0, 10))
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(productId, load)

// ---------- 展示辅助 ----------
const n = (v: number | string | null | undefined, digits = 0) =>
  v == null ? '—' : Number(v).toLocaleString(undefined, { maximumFractionDigits: digits })
const money = (v: number | string | null | undefined) =>
  v == null ? '—' : `¥${Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })}`

const statusChip = (s: string | null | undefined) =>
  s === '在售' ? 'c-green' : s === '清仓中' ? 'c-amber' : 'c-gray'

/** 周走势条形图(纯 CSS bar,mockup spark 风格) */
const maxWeekQty = computed(() =>
  Math.max(1, ...(data.value?.weeklyTrend ?? []).map((w) => Number(w.salesQty))),
)
const maxDistQty = computed(() =>
  Math.max(1, ...(data.value?.machineDist ?? []).map((m) => Number(m.salesQty30))),
)

/** 大事记:price_log + op_log 合并时间倒排(p14 大事记) */
const events = computed(() => {
  const list: { time: string; chip: string; text: string }[] = []
  for (const pl of priceLogs.value) {
    list.push({
      time: pl.createTime ?? '',
      chip: 'c-amber',
      text: `改价 ${pl.oldPrice != null ? '¥' + pl.oldPrice : '—'} → ¥${pl.newPrice}(${pl.changeSource})`,
    })
  }
  for (const op of opLogs.value) {
    list.push({ time: op.opTime, chip: 'c-blue', text: `${op.userName} · ${op.action}` })
  }
  for (const ph of data.value?.purchaseHist ?? []) {
    list.push({
      time: ph.bizTime,
      chip: 'c-gray',
      text: `${ph.docType} ${n(ph.qty)} 件${ph.supplierName ? ' · ' + ph.supplierName : ''}(${ph.docNo})`,
    })
  }
  return list.sort((a, b) => (a.time < b.time ? 1 : -1)).slice(0, 12)
})
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">
      园区小卖 ERP / 库存管理 /
      <a class="crumb-link" @click="router.push('/inventory')">库存总览</a>
      / 单品详情
    </div>
    <div class="ledger-title" v-if="data">
      <h2>{{ data.productName }}</h2>
      <span class="chip c-gray num">{{ data.skuCode }}</span>
      <span v-if="data.category" class="chip c-gray">{{ data.category }}</span>
      <span class="chip" :class="statusChip(data.productStatus)">{{ data.productStatus }}</span>
      <span style="margin-left: auto; display: flex; gap: 8px">
        <el-button size="small" @click="router.push('/products')">编辑档案 →</el-button>
        <el-button size="small" type="primary" plain @click="router.push('/inventory')">库存总览 →</el-button>
      </span>
    </div>
    <p class="ledger-note">
      每个单品都有这样一页「体检报告」:卖得怎么样、赚多少、货在哪、跟谁进、发生过什么。
      <span class="chip c-green" style="margin-left: 6px">🕐 数据截至 {{ data?.dataAsOf ? data.dataAsOf.replace('T', ' ').slice(0, 16) : '——' }}</span>
    </p>

    <!-- 关键数字卡(p14 六卡) -->
    <div class="stat-grid" v-if="data">
      <div class="ledger-card stat">
        <div class="lb">30天销量</div>
        <div class="vv num">{{ n(data.salesQty30) }}<small>{{ data.unit || '件' }}</small></div>
        <span class="mini">日均 {{ n(data.dailyAvg30, 1) }} / 天</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">30天销售 / 毛利</div>
        <div class="vv num">{{ money(data.salesAmt30) }}</div>
        <span class="mini" v-if="data.grossProfit30 != null">毛利 {{ money(data.grossProfit30) }}</span>
        <span class="mini" v-else style="color: var(--amber)">毛利 —(成本待补)</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">当前库存</div>
        <div class="vv num">{{ n(data.totalQty) }}<small>{{ data.unit || '件' }}</small></div>
        <span class="mini">仓 {{ n(data.warehouseQty) }} + 机 {{ n(data.machineQtyTotal) }}</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">够卖天数</div>
        <div class="vv num" :style="data.daysOfStock != null && Number(data.daysOfStock) < 7 ? 'color:var(--red)' : ''">
          {{ n(data.daysOfStock, 1) }}
        </div>
        <span class="mini">= 合计 ÷ 30天日均</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">毛利率(30天)</div>
        <div class="vv num">{{ data.marginPct30 != null ? data.marginPct30 + '%' : '—' }}</div>
        <span class="mini" v-if="!data.hasCost" style="color: var(--amber)">无采购史,成本待补</span>
        <span class="mini" v-else>毛利 = 实收 − 移动加权成本</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">移动加权成本</div>
        <div class="vv num">{{ data.unitCost != null ? '¥' + data.unitCost : '—' }}</div>
        <span class="mini">存货价值 {{ money(data.stockAmount) }}</span>
      </div>
    </div>

    <div class="two-col" v-if="data">
      <!-- 左:基础信息 + 采购史 -->
      <div class="ledger-card">
        <h3>📇 基础信息 · 别名映射</h3>
        <div class="kv">
          <span class="mini">编码 / 分类</span><b class="num">{{ data.skuCode }} · {{ data.category ?? '—' }}</b>
          <span class="mini">整件规格</span><b>{{ data.boxSpec != null ? n(data.boxSpec) + ' ' + (data.unit || '件') + '/箱' : '—' }}</b>
          <span class="mini">保质期</span><b>{{ data.shelfLifeDays != null ? data.shelfLifeDays + ' 天' : '—' }}</b>
          <span class="mini">参考售价</span><b>{{ money(data.refPrice) }}</b>
          <span class="mini">别名映射</span>
          <span>
            <template v-if="aliases.length">
              <span v-for="a in aliases.slice(0, 3)" :key="a.id" class="chip c-green" style="margin-right: 4px">
                {{ a.aliasName }}
              </span>
              <span class="mini">✓ {{ aliases.length }} 个已绑</span>
            </template>
            <span v-else class="chip c-amber">⚠️ 尚未绑定后台别名</span>
          </span>
        </div>
        <h6 class="blk">🧾 成本 · 供应商(近 {{ data.purchaseHist.length }} 笔,累计 {{ n(data.purchaseTotalQty) }} 件 {{ money(data.purchaseTotalAmt) }})</h6>
        <el-table :data="data.purchaseHist" size="small">
          <el-table-column label="日期" width="100">
            <template #default="{ row }">{{ (row.bizTime ?? '').slice(0, 10) }}</template>
          </el-table-column>
          <el-table-column label="来源" min-width="110">
            <template #default="{ row }">
              {{ row.supplierName ?? (row.docType === '期初' ? '期初迁入' : '—') }}
              <span class="chip c-gray" style="margin-left: 4px">{{ row.docType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="数量" align="right" width="80">
            <template #default="{ row }"><span class="num">{{ n(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column label="单价" align="right" width="90">
            <template #default="{ row }"><span class="num">{{ row.unitCost != null ? Number(row.unitCost).toFixed(2) : '—' }}</span></template>
          </el-table-column>
          <el-table-column label="金额" align="right" width="100">
            <template #default="{ row }"><span class="num">{{ money(row.amount) }}</span></template>
          </el-table-column>
          <template #empty><el-empty description="暂无采购史(成本待补)" :image-size="50" /></template>
        </el-table>
      </div>

      <!-- 右:销量走势 + 机器分布 -->
      <div class="ledger-card">
        <h3>📈 销量走势 <span class="hint">近 8 周(周一为一周起点)· 绿=销量</span></h3>
        <div class="spark-wrap">
          <div v-for="(w, i) in data.weeklyTrend" :key="w.label" class="spark-col">
            <div
              class="spark-bar"
              :class="{ hot: i === data.weeklyTrend.length - 1 }"
              :style="{ height: Math.max(4, (Number(w.salesQty) / maxWeekQty) * 72) + 'px' }"
              :title="`${w.label} 起周:${w.salesQty} 件 · ¥${w.salesAmt}`"
            ></div>
            <span class="mini spark-lb">{{ w.label.slice(5) }}</span>
          </div>
        </div>
        <p class="mini" style="margin: 6px 0 0">
          悬停看每周件数/金额;毛利随成本引擎动态算,无成本周不计。
        </p>
        <h6 class="blk">🏭 库存分布 · 30 天销量(在哪、卖得动在哪)</h6>
        <div class="hbar">
          <span class="nm">🏬 仓库(现存)</span>
          <div class="tk"><i :style="{ width: Math.min(100, (Number(data.warehouseQty) / Math.max(1, Number(data.totalQty))) * 100) + '%' }"></i></div>
          <span class="vl num">{{ n(data.warehouseQty) }}</span>
        </div>
        <div v-for="m in data.machineDist" :key="m.machineId" class="hbar">
          <span class="nm">{{ m.machineName }}</span>
          <div class="tk"><i :style="{ width: (Number(m.salesQty30) / maxDistQty) * 100 + '%' }"></i></div>
          <span class="vl num">卖{{ n(m.salesQty30) }} · 存{{ n(m.stockQty ?? 0) }}</span>
        </div>
        <el-empty v-if="!data.machineDist.length" description="30 天内无机器销量" :image-size="50" />
      </div>
    </div>

    <!-- 进出流水 + 大事记 -->
    <div class="two-col" v-if="data">
      <div class="ledger-card">
        <h3>📜 进出流水 <span class="hint">每一笔都有单据 · 近 50 条</span></h3>
        <el-table :data="ledgerRows" size="small" max-height="360">
          <el-table-column label="时间" width="140">
            <template #default="{ row }">{{ (row.bizTime ?? '').replace('T', ' ').slice(0, 16) }}</template>
          </el-table-column>
          <el-table-column label="单据" width="150">
            <template #default="{ row }"><span class="num mini">{{ row.docNo }}</span></template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }"><span class="chip c-blue">{{ row.docType }}</span></template>
          </el-table-column>
          <el-table-column label="账本" width="90">
            <template #default="{ row }">{{ row.locationType === '机器' ? (row.machineName ?? '机器') : '🏬 仓库' }}</template>
          </el-table-column>
          <el-table-column label="±" align="right" width="70">
            <template #default="{ row }">
              <b class="num" :style="{ color: Number(row.changeQty) < 0 ? 'var(--red)' : 'var(--green)' }">
                {{ Number(row.changeQty) > 0 ? '+' : '' }}{{ n(row.changeQty) }}
              </b>
            </template>
          </el-table-column>
          <el-table-column label="结存" align="right" width="70">
            <template #default="{ row }"><span class="num">{{ n(row.balanceQty) }}</span></template>
          </el-table-column>
          <template #empty><el-empty description="暂无流水" :image-size="50" /></template>
        </el-table>
      </div>
      <div class="ledger-card">
        <h3>📜 大事记 <span class="hint">改价留痕 + 档案操作 + 进货节点(自动汇集)</span></h3>
        <div v-if="events.length" class="events">
          <div v-for="(e, i) in events" :key="i" class="event-row">
            <span class="chip" :class="e.chip">{{ (e.time ?? '').replace('T', ' ').slice(5, 16) }}</span>
            <span>{{ e.text }}</span>
          </div>
        </div>
        <el-empty v-else description="暂无大事记" :image-size="50" />
        <p class="mini" style="margin-top: 10px">
          — AI 单品体检(结论 + 🔬 过程)随里程碑 4「AI 全家桶」点亮 —
        </p>
      </div>
    </div>

    <p class="ledger-foot-note">
      — 每个单品都是这一页;从 库存管理 / 商品列表 点商品名即达(七律#3:任何名词都能点进去)—
    </p>
  </div>
</template>

<style scoped>
.crumb-link {
  color: var(--green);
  cursor: pointer;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.stat {
  padding: 12px 14px;
  margin-bottom: 0;
}
.stat .lb {
  font-size: 12.5px;
  color: var(--ink2);
}
.stat .vv {
  font-size: 24px;
  font-weight: 700;
  margin: 4px 0 2px;
}
.stat .vv small {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink2);
  margin-left: 2px;
}
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.kv {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: 6px 14px;
  font-size: 13px;
  line-height: 1.9;
  margin-top: 8px;
}
h6.blk {
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
  margin: 14px 0 8px;
  font-weight: 700;
}
.spark-wrap {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  height: 96px;
  padding: 4px 2px 0;
}
.spark-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  height: 100%;
}
.spark-bar {
  width: 70%;
  max-width: 34px;
  background: #bcd6c6;
  border-radius: 3px 3px 0 0;
}
.spark-bar.hot {
  background: var(--green);
}
.spark-lb {
  font-size: 10px;
}
.hbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 7px 0;
}
.hbar .nm {
  width: 120px;
  font-size: 12.5px;
  text-align: right;
  color: var(--ink2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.hbar .tk {
  flex: 1;
  height: 16px;
  background: #ece6d8;
  border-radius: 4px;
  overflow: hidden;
}
.hbar .tk i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #3c8563, #2f6e52);
  border-radius: 4px;
}
.hbar .vl {
  width: 110px;
  font-weight: 600;
  font-size: 12.5px;
}
.events {
  font-size: 12.5px;
  line-height: 2.1;
}
.event-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
</style>
