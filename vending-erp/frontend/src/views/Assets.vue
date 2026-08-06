<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  finreportApi,
  type AssetSnapshotResp,
  type SnapshotRow,
} from '@/api/finreport'

/**
 * p10 资产家底(M3-6,对照 UI-mockup):
 * 一条公式讲清家底(§13.1 效力最高):库存 + 平台欠我的 + 账上现金 + 索赔应收 − 我欠供应商的 = 净家底。
 * 五分项卡各可下钻来源列表(七律#3 任何数字能点进去);趋势折线轻量 SVG(不引图表库);
 * 月度归档幂等重算当月、锁账月永不重算。
 */

const loading = ref(false)
const snap = ref<AssetSnapshotResp | null>(null)
const trend = ref<SnapshotRow[]>([])
const archives = ref<SnapshotRow[]>([])
const archiving = ref(false)

const drill = ref<'' | 'inventory' | 'pending' | 'cash' | 'claim' | 'payable'>('')
const drillOpen = computed({
  get: () => drill.value !== '',
  set: (open: boolean) => {
    if (!open) drill.value = ''
  },
})

async function loadAll() {
  loading.value = true
  try {
    const [s, t, a] = await Promise.all([
      finreportApi.assets(),
      finreportApi.trend(12),
      finreportApi.archives(),
    ])
    snap.value = s
    trend.value = t
    archives.value = a
  } finally {
    loading.value = false
  }
}
onMounted(loadAll)

const currentPeriod = () => new Date().toISOString().slice(0, 7)

async function archiveNow() {
  try {
    await ElMessageBox.confirm(
      `把「现在」的家底定格为 ${currentPeriod()} 月度快照?同月重复归档会幂等覆盖重算;月份一旦锁账则永不重算。`,
      '归档本月快照',
      { confirmButtonText: '归档', cancelButtonText: '再想想' },
    )
  } catch {
    return
  }
  archiving.value = true
  try {
    const row = await finreportApi.archive(currentPeriod())
    ElMessage.success(`已归档 ${row.period}:净家底 ¥${fmt(row.netAsset)}`)
    await loadAll()
  } finally {
    archiving.value = false
  }
}

/** 比上月:最近归档 vs 即时净家底 */
const delta = computed(() => {
  const s = snap.value
  if (!s || s.prevNetAsset == null) return null
  return Number((s.netAsset - s.prevNetAsset).toFixed(2))
})

/** 趋势折线数据:归档点(旧→新)+「今天」即时点 */
const points = computed(() => {
  const s = snap.value
  const list: { label: string; value: number; today: boolean }[] = trend.value.map((t) => ({
    label: `${t.period.slice(5)}月`,
    value: Number(t.netAsset),
    today: false,
  }))
  if (s) list.push({ label: '今天', value: Number(s.netAsset), today: true })
  return list
})

const svg = computed(() => {
  const list = points.value
  if (!list.length) return { line: '', dots: [] as { x: number; y: number; label: string; value: number; today: boolean }[] }
  const w = 400
  const h = 100
  const x0 = 24
  const values = list.map((p) => p.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const step = list.length > 1 ? (w - x0 * 2) / (list.length - 1) : 0
  const dots = list.map((p, i) => ({
    x: x0 + i * step,
    y: 14 + (h - 28) * (1 - (p.value - min) / span),
    label: p.label,
    value: p.value,
    today: p.today,
  }))
  return { line: dots.map((d) => `${d.x},${d.y}`).join(' '), dots }
})

const fmt = (v: number | null | undefined, dash = '—') =>
  v == null ? dash : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const fmtQty = (v: number | null | undefined) => (v == null ? '—' : Number(v).toLocaleString())

const drillTitle = computed(() => ({
  inventory: '📦 库存资产:压在货上的钱(移动加权成本)',
  pending: '⏳ 平台待结算:平台还欠我的在途货款',
  cash: '💵 账户现金:真实账户余额(期初 + Σ流水)',
  claim: '📮 索赔应收:申请中的赔付(P0-6 公式项)',
  payable: '🏭 欠供应商:应付余额(负数 = 预付款)',
  '': '',
}[drill.value]))
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 钱账 / 资产家底</div>
    <div class="ledger-title">
      <h2>资产家底</h2>
      <span class="sub">这盘生意到底押了多少钱、现在值多少、回本了没</span>
    </div>
    <p class="ledger-note">
      一条公式讲清家底:<b>库存 + 平台欠我的 + 账上现金 + 索赔应收 − 我欠供应商的 = 净家底</b>。每个数字都能点进去看明细。
    </p>

    <el-alert v-if="snap?.settleBanner" type="warning" :closable="false" class="mb-12px">
      <template #title>{{ snap.settleBanner }}</template>
    </el-alert>

    <!-- 公式条:五分项 + 净家底 -->
    <el-card shadow="never" class="mb-12px formula-card">
      <div class="formula-strip" v-if="snap">
        <div class="fbox" @click="drill = 'inventory'">
          <div class="mini">📦 库存资产(成本)</div>
          <div class="num">¥{{ fmt(snap.inventoryAmount) }}</div>
          <div class="mini">仓库 {{ fmt(snap.warehouseAmount) }} + 机内 {{ fmt(snap.machineAmount) }} ↗</div>
        </div>
        <span class="op">＋</span>
        <div class="fbox" @click="drill = 'pending'">
          <div class="mini">⏳ 平台待结算</div>
          <div class="num" style="color: var(--blue)">¥{{ fmt(snap.platformPending) }}</div>
          <div class="mini">
            {{ snap.settleMode === 'PLATFORM' ? '在途货款(未核销)↗' : snap.settleMode === 'DIRECT' ? '直连模式无此项' : '结算模式待核实,暂按 0' }}
          </div>
        </div>
        <span class="op">＋</span>
        <div class="fbox" @click="drill = 'cash'">
          <div class="mini">💵 账户现金</div>
          <div class="num" style="color: var(--green)">¥{{ fmt(snap.cashTotal) }}</div>
          <div class="mini">{{ snap.cashRows.length }} 个真实账户 ↗</div>
        </div>
        <span class="op">＋</span>
        <div class="fbox" @click="drill = 'claim'">
          <div class="mini">📮 索赔应收</div>
          <div class="num" style="color: var(--blue)">¥{{ fmt(snap.claimReceivable) }}</div>
          <div class="mini">{{ snap.claimRows.length }} 笔申请中 ↗</div>
        </div>
        <span class="op">−</span>
        <div class="fbox" @click="drill = 'payable'">
          <div class="mini">🏭 欠供应商</div>
          <div class="num" style="color: var(--amber)">¥{{ fmt(snap.payableTotal) }}</div>
          <div class="mini">{{ snap.payableRows.length }} 家有往来余额 ↗</div>
        </div>
        <span class="op">=</span>
        <div class="fbox net">
          <div class="mini">🏦 净家底(流动)</div>
          <div class="num big">¥{{ fmt(snap.netAsset) }}</div>
          <div class="mini" v-if="delta != null">
            比最近归档({{ snap.prevPeriod }}){{ delta >= 0 ? '+' : '' }}¥{{ fmt(delta) }} {{ delta >= 0 ? '↑' : '↓' }}
          </div>
          <div class="mini" v-else>尚无归档基准,先归档一次</div>
        </div>
      </div>
      <div class="text-12px text-gray-500 mt-8px" v-if="snap">
        快照时刻 {{ snap.asOf }} · 数据截至 {{ snap.dataAsOf ?? '——(尚未导入)' }} ·
        口径 §13.1:设备只记台账折余,不进流动家底
      </div>
    </el-card>

    <div class="grid grid-cols-2 gap-12px mb-12px asset-two-col">
      <!-- 净家底趋势 -->
      <el-card shadow="never">
        <h3 class="card-h3">净家底趋势 <span class="text-12px text-gray-400">月度归档点 + 今天即时点</span></h3>
        <template v-if="points.length > 1">
          <svg viewBox="0 0 400 130" style="width: 100%; margin-top: 8px">
            <polyline :points="svg.line" fill="none" stroke="#2f6e52" stroke-width="3" stroke-linecap="round" />
            <g v-for="(d, i) in svg.dots" :key="i">
              <circle :cx="d.x" :cy="d.y" :r="d.today ? 5 : 4" :fill="d.today ? '#c0392b' : '#2f6e52'" />
              <text :x="d.x" y="122" font-size="10" fill="#5a6b60" text-anchor="middle">{{ d.label }}</text>
              <text :x="d.x" :y="d.y - 8" font-size="9" fill="#22312a" text-anchor="middle">{{ fmtQty(Math.round(d.value)) }}</text>
            </g>
          </svg>
          <p class="text-12px text-gray-500">这条线一直往上 = 生意在变厚;往下就要警惕(压货 or 亏钱)。</p>
        </template>
        <el-empty v-else description="归档满 1 个月后开始画线(先点右侧「归档本月快照」)" :image-size="60" />
      </el-card>

      <!-- 月度归档 -->
      <el-card shadow="never">
        <div class="flex items-center">
          <h3 class="card-h3">月度归档 <span class="text-12px text-gray-400">每月 1 日定格上月家底</span></h3>
          <el-button size="small" type="primary" style="margin-left: auto" :loading="archiving" @click="archiveNow">
            归档本月快照
          </el-button>
        </div>
        <el-table :data="archives" size="small" max-height="260">
          <el-table-column label="月份" width="90" prop="period" />
          <el-table-column label="净家底" align="right" width="110">
            <template #default="{ row }">
              <b>¥{{ fmt(row.netAsset) }}</b>
            </template>
          </el-table-column>
          <el-table-column label="库存" align="right" width="90">
            <template #default="{ row }">{{ fmt(row.inventoryAmount) }}</template>
          </el-table-column>
          <el-table-column label="现金" align="right" width="90">
            <template #default="{ row }">{{ fmt(row.cashTotal) }}</template>
          </el-table-column>
          <el-table-column label="应付" align="right" width="90">
            <template #default="{ row }">{{ fmt(row.payableTotal) }}</template>
          </el-table-column>
          <el-table-column label="" min-width="110">
            <template #default="{ row }">
              <span v-if="row.locked" class="chip c-amber">🔒 已锁账 · 永不重算</span>
              <span v-else class="chip c-green">可幂等重算</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没归档过,点「归档本月快照」定格第一笔家底" :image-size="50" />
          </template>
        </el-table>
      </el-card>
    </div>

    <p class="text-12px text-gray-400 text-center">
      — 设备只记台账和折余价值,不进钱账:小生意把折旧混进现金账,只会看不懂自己赚没赚 —
    </p>

    <!-- 下钻明细 -->
    <el-dialog v-model="drillOpen" :title="drillTitle" width="640px">
      <el-table v-if="drill === 'inventory'" :data="snap?.inventoryRows ?? []" size="small" max-height="420">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <span class="text-11px text-gray-400 ml-4px">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="现存(仓+机)" align="right" width="110">
          <template #default="{ row }">{{ fmtQty(row.qty) }}</template>
        </el-table-column>
        <el-table-column label="成本金额" align="right" width="120">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
      </el-table>
      <el-table v-else-if="drill === 'pending'" :data="snap?.pendingRows ?? []" size="small" max-height="420">
        <el-table-column label="业务月" prop="period" width="110" />
        <el-table-column label="笔数" align="right" width="90">
          <template #default="{ row }">{{ fmtQty(row.cnt) }}</template>
        </el-table-column>
        <el-table-column label="在途金额(正常−退款)" align="right" min-width="150">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty :description="snap?.settleMode === 'PLATFORM' ? '没有在途货款' : '结算模式未定型为平台归集,此项恒 0'" :image-size="50" />
        </template>
      </el-table>
      <el-table v-else-if="drill === 'cash'" :data="snap?.cashRows ?? []" size="small" max-height="420">
        <el-table-column label="账户" min-width="160">
          <template #default="{ row }">
            <b>{{ row.accountName }}</b>
            <span v-if="row.disabled" class="chip c-amber ml-4px">已停用</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" prop="accountType" width="90" />
        <el-table-column label="余额" align="right" width="120">
          <template #default="{ row }">¥{{ fmt(row.balance) }}</template>
        </el-table-column>
      </el-table>
      <el-table v-else-if="drill === 'claim'" :data="snap?.claimRows ?? []" size="small" max-height="420">
        <el-table-column label="索赔单号" prop="claimNo" min-width="150" />
        <el-table-column label="对象" prop="claimTarget" width="90" />
        <el-table-column label="金额" align="right" width="110">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="申请时间" prop="createTime" min-width="150" />
        <template #empty>
          <el-empty description="没有申请中的索赔" :image-size="50" />
        </template>
      </el-table>
      <el-table v-else-if="drill === 'payable'" :data="snap?.payableRows ?? []" size="small" max-height="420">
        <el-table-column label="供应商" prop="supplierName" min-width="160" />
        <el-table-column label="应付余额" align="right" width="130">
          <template #default="{ row }">
            <b :class="row.prepay ? 'text-green-700' : ''">¥{{ fmt(row.balance) }}</b>
            <span v-if="row.prepay" class="chip c-green ml-4px">预付款</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有未结的供应商往来" :image-size="50" />
        </template>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.formula-card :deep(.el-card__body) {
  background: linear-gradient(135deg, #fffdf8, #f4efe2);
}
.formula-strip {
  display: flex;
  align-items: stretch;
  gap: 10px;
  flex-wrap: wrap;
}
.fbox {
  text-align: center;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 10px;
  cursor: pointer;
  min-width: 128px;
}
.fbox:hover {
  border-color: var(--green);
}
.fbox .num {
  font-family: var(--num);
  font-size: 24px;
  font-weight: 600;
  color: var(--ink);
}
.fbox .num.big {
  font-size: 28px;
  color: #fff;
}
.fbox .mini {
  font-size: 11px;
  color: var(--ink2);
}
.fbox.net {
  background: var(--green-deep);
  cursor: default;
}
.fbox.net .mini {
  color: #9db8a8;
}
.op {
  align-self: center;
  font-size: 22px;
  color: var(--ink2);
}
.card-h3 {
  font-family: var(--serif);
  font-size: 15px;
  margin: 0 0 6px;
}
</style>
