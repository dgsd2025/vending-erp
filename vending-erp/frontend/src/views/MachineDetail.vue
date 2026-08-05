<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { reportApi, type MachineOverviewResp, type SlotRow } from '@/api/report'
import { pageOpLogs, type OpLog } from '@/api/basedata'
import { useAppStore } from '@/stores/app'

/**
 * 机器详情页(M1-9,对照 mockup p15,设计七律#3:任何名词都能点进去):
 * 机器信息卡 + 货道 planogram 网格(slot 绑品+容量+现量)+ 该机销量与毛利 +
 * 补货史(转移单流水)+ 档案操作留痕。查询页,豁免任务流程条。
 */
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const data = ref<MachineOverviewResp | null>(null)
const opLogs = ref<OpLog[]>([])

const machineId = computed(() => Number(route.params.id))

async function load() {
  if (!machineId.value) return
  loading.value = true
  try {
    const [overview, ops] = await Promise.all([
      reportApi.machineOverview(machineId.value),
      pageOpLogs({ targetType: 'machine', targetId: machineId.value, size: 10 }),
    ])
    data.value = overview
    opLogs.value = ops.records
    if (overview.dataAsOf) useAppStore().setDataAsOf(overview.dataAsOf.slice(0, 10))
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(machineId, load)

const n = (v: number | string | null | undefined, digits = 0) =>
  v == null ? '—' : Number(v).toLocaleString(undefined, { maximumFractionDigits: digits })
const money = (v: number | string | null | undefined) =>
  v == null ? '—' : `¥${Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })}`

const statusChip = (s: string | null | undefined) =>
  s === '在线' || s === '在售' ? 'c-green' : s === '故障' ? 'c-red' : 'c-gray'

/** planogram 格子配色(mockup p12/p15):红=缺货 黄=偏低 绿=正常 灰=空道 */
function slotClass(s: SlotRow): string {
  if (!s.productId) return 'empty'
  const cap = Number(s.capacity ?? 0)
  const qty = Number(s.currentQty ?? 0)
  if (cap <= 0) return 'ok'
  const ratio = qty / cap
  if (ratio <= 0.15) return 'bad'
  if (ratio <= 0.4) return 'warn'
  return 'ok'
}

const maxDayAmt = computed(() =>
  Math.max(1, ...(data.value?.dailyTrend ?? []).map((d) => Number(d.salesAmt))),
)
const maxTopQty = computed(() =>
  Math.max(1, ...(data.value?.topSkus ?? []).map((t) => Number(t.salesQty30))),
)
/** 机器账为负 = 缺后台快照锚点(期初只导了销售史),填充率不出数,亮提示 */
const stockNegative = computed(() => Number(data.value?.machineStockQty ?? 0) < 0)
const fillPct = computed(() => {
  if (!data.value || Number(data.value.capacityTotal) <= 0 || stockNegative.value) return null
  return Math.round((Number(data.value.machineStockQty) / Number(data.value.capacityTotal)) * 100)
})
const fmtAsOf = (v: string | null | undefined) =>
  v ? v.replace('T', ' ').slice(0, 16) : '——'
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">
      园区小卖 ERP / 机器 /
      <a class="crumb-link" @click="router.push('/settings')">机器与货道</a>
      / 机器详情
    </div>
    <div class="ledger-title" v-if="data">
      <h2>{{ data.machineName }}</h2>
      <span class="chip c-gray num">{{ data.deviceId }}</span>
      <span class="chip" :class="statusChip(data.machineStatus)">{{ data.machineStatus }}</span>
      <span class="chip c-gray">{{ data.slots.length || data.slotCount || '—' }} 货道</span>
      <span v-if="data.location" class="chip c-blue">{{ data.location }}</span>
      <span style="margin-left: auto; display: flex; gap: 8px">
        <el-button size="small" @click="router.push('/settings')">货道配置 →</el-button>
        <el-button size="small" type="primary" plain @click="router.push('/inventory')">库存总览 →</el-button>
      </span>
    </div>
    <p class="ledger-note" v-if="data">
      档案:{{ data.location || '点位待录' }} · 机型 {{ data.model || '—' }} · 上线
      {{ data.onlineDate || '—' }} · 机器账以厂家后台为权威(快照+增量推算)
      <span class="chip c-green" style="margin-left: 6px">🕐 数据截至 {{ fmtAsOf(data.dataAsOf) }}</span>
    </p>

    <!-- 关键数字卡(p15 六卡) -->
    <div class="stat-grid" v-if="data">
      <div class="ledger-card stat">
        <div class="lb">本月销售({{ data.month ?? '—' }})</div>
        <div class="vv num">{{ money(data.monthSalesAmt) }}</div>
        <span class="mini" v-if="data.salesSharePct != null">全场占比 {{ data.salesSharePct }}%</span>
        <span class="mini" v-else>本月暂无销售</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">本月毛利</div>
        <div class="vv num">{{ money(data.monthGrossProfit) }}</div>
        <span class="mini" v-if="data.monthMarginPct != null">毛利率 {{ data.monthMarginPct }}%</span>
        <span class="mini" v-else style="color: var(--amber)">—(成本待补或无销售)</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">本月销量</div>
        <div class="vv num">{{ n(data.monthSalesQty) }}<small>件</small></div>
        <span class="mini">日均 {{ money(data.dailyAvgAmt) }}(按有销售天数)</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">机内库存</div>
        <div class="vv num" :style="stockNegative ? 'color:var(--red)' : ''">
          {{ n(data.machineStockQty) }}<small v-if="!stockNegative && Number(data.capacityTotal) > 0">/{{ n(data.capacityTotal) }}</small>
        </div>
        <span class="mini" v-if="stockNegative" style="color: var(--red)">🚨 缺后台快照锚点(期初只导销售史),导快照/盘点后转正</span>
        <span class="mini" v-else>快照 + 增量推算</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">货道填充率</div>
        <div class="vv num" :style="fillPct != null && fillPct < 50 ? 'color:var(--red)' : ''">
          {{ fillPct != null ? fillPct + '%' : '—' }}
        </div>
        <span class="mini">{{ fillPct == null ? (stockNegative ? '机器账未校准,不出数' : '货道容量未配置') : fillPct < 50 ? '偏低,建议优先补货' : '健康' }}</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">补货次数(近10单)</div>
        <div class="vv num">{{ data.transferHist.length }}</div>
        <span class="mini">转移单唯一生产者 = 后台导入</span>
      </div>
    </div>

    <div class="two-col" v-if="data">
      <!-- 左:销售走势 + TOP SKU -->
      <div class="ledger-card">
        <h3>📈 销售 · 毛利(近 14 天) <span class="hint">绿=销售额 · 悬停看毛利</span></h3>
        <div class="spark-wrap">
          <div v-for="(d, i) in data.dailyTrend" :key="d.label" class="spark-col">
            <div
              class="spark-bar"
              :class="{ hot: i === data.dailyTrend.length - 1 }"
              :style="{ height: Math.max(3, (Number(d.salesAmt) / maxDayAmt) * 72) + 'px' }"
              :title="`${d.label}:¥${d.salesAmt} · ${d.salesQty} 件 · 毛利 ¥${d.grossProfit ?? '—'}`"
            ></div>
            <span class="mini spark-lb">{{ d.label.slice(8) }}</span>
          </div>
        </div>
        <h6 class="blk">🧃 本机 TOP SKU(30 天,什么在这台卖得动)</h6>
        <div v-for="t in data.topSkus" :key="t.productId" class="hbar">
          <span class="nm link" @click="router.push(`/products/${t.productId}`)">{{ t.productName }} ↗</span>
          <div class="tk"><i :style="{ width: (Number(t.salesQty30) / maxTopQty) * 100 + '%' }"></i></div>
          <span class="vl num">{{ n(t.salesQty30) }} 件 · {{ money(t.salesAmt30) }}</span>
        </div>
        <el-empty v-if="!data.topSkus.length" description="30 天内无销量" :image-size="50" />
      </div>

      <!-- 右:补货史 + 运维留痕 -->
      <div class="ledger-card">
        <h3>🚚 补货史 <span class="hint">该机转移单(出库上架/退库)· 近 10 单</span></h3>
        <el-table :data="data.transferHist" size="small">
          <el-table-column label="日期" width="100">
            <template #default="{ row }">{{ row.bizDate }}</template>
          </el-table-column>
          <el-table-column label="单号" min-width="140">
            <template #default="{ row }"><span class="num mini">{{ row.docNo }}</span></template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <span class="chip" :class="row.docType === '退库' ? 'c-amber' : 'c-blue'">{{ row.docType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="品项" align="right" width="60">
            <template #default="{ row }"><span class="num">{{ row.itemCount }}</span></template>
          </el-table-column>
          <el-table-column label="件数" align="right" width="70">
            <template #default="{ row }"><span class="num">{{ n(row.totalQty) }}</span></template>
          </el-table-column>
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <span class="chip" :class="row.sourceType === '导入' ? 'c-blue' : 'c-gray'">{{ row.sourceType }}</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无补货记录(转移单随后台补货记录导入生成)" :image-size="50" /></template>
        </el-table>
        <h6 class="blk">📜 档案操作留痕</h6>
        <div v-if="opLogs.length" class="events">
          <div v-for="op in opLogs" :key="op.id" class="event-row">
            <span class="chip c-gray">{{ (op.opTime ?? '').replace('T', ' ').slice(5, 16) }}</span>
            <span>{{ op.userName }} · {{ op.action }}</span>
          </div>
        </div>
        <p v-else class="mini">暂无档案改动记录</p>
      </div>
    </div>

    <!-- 货道 planogram -->
    <div class="ledger-card" v-if="data">
      <h3>
        🗄 货道 planogram
        <span class="hint">格子 = 货道:编号 / 绑定商品 / 现量÷容量;🔴缺货 🟡偏低 ▫️空道 · 改绑去「设置中心 → 货道配置」</span>
      </h3>
      <div v-if="data.slots.length" class="plano">
        <div
          v-for="s in data.slots"
          :key="s.slotId"
          class="cell"
          :class="slotClass(s)"
          :title="s.productName ? `${s.slotNo} · ${s.productName}(${s.currentQty ?? 0}/${s.capacity ?? '—'})` : `${s.slotNo} · 空货道`"
          @click="s.productId && router.push(`/products/${s.productId}`)"
        >
          <b class="num">{{ s.slotNo }}</b>
          <span class="cell-name">{{ s.productName ?? '空货道' }}</span>
          <span class="num cell-qty">{{ s.productId ? `${n(s.currentQty ?? 0)}/${n(s.capacity)}` : '＋绑商品' }}</span>
        </div>
      </div>
      <el-empty v-else description="货道未配置 —— 去「设置中心 → 机器与货道 → 货道配置」批量初始化" :image-size="60" />
    </div>

    <p class="ledger-foot-note">
      — 每台机器都是这一页;从 设置中心机器列表 / 报表机器维度 点机器名即达 —
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
  font-size: 13px;
  font-weight: 600;
  color: var(--ink2);
}
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
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
  gap: 6px;
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
  max-width: 26px;
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
  width: 150px;
  font-size: 12.5px;
  text-align: right;
  color: var(--ink2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.hbar .nm.link {
  color: var(--green);
  cursor: pointer;
  font-weight: 600;
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
  width: 130px;
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
.plano {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 5px;
  font-size: 10px;
  text-align: center;
}
.cell {
  border-radius: 6px;
  padding: 6px 3px;
  border: 1px solid var(--line);
  background: var(--green-soft);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-height: 52px;
}
.cell.warn {
  background: var(--amber-soft);
  border-color: var(--amber);
}
.cell.bad {
  background: var(--red-soft);
  border: 1.5px solid var(--red);
}
.cell.bad .cell-qty {
  color: var(--red);
  font-weight: 700;
}
.cell.empty {
  background: #eee9dd;
  border: 1px dashed var(--line2);
  color: #a89f8a;
  cursor: default;
}
.cell-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
