<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  biApi,
  type BasketResp,
  type BiDim,
  type MatrixResp,
  type PriceCompareResp,
  type QuadrantPoint,
  type QuadrantResp,
  type StockoutLossResp,
} from '@/api/bi'

/**
 * BI 经营分析(p6,§10.1):销售看"卖得动",利润看"值得卖"。
 * 招牌视图=单品四象限(销量×毛利率,轻量 SVG 散点);六维 Tab 矩阵;毛利与销售并排。
 * 口径 §13:毛利=实收−移动加权成本;兑换/线下收入0计成本;测试不计;无采购史毛利显「—」不造假。
 * AI 洞察位灰(等 AI 票 M4-3~）。
 */
const router = useRouter()
const route = useRoute()

// 支持深链 /bi?month=2026-07(便于分享某月分析 + 直接定位)
const month = ref<string>(typeof route.query.month === 'string' ? route.query.month : '')
const months = ref<string[]>([])
const dataAsOf = ref<string | null>(null)
const activeDim = ref<BiDim>('machine')

const quadrant = ref<QuadrantResp | null>(null)
const matrix = ref<MatrixResp | null>(null)
const basket = ref<BasketResp | null>(null)
const stockout = ref<StockoutLossResp | null>(null)
const priceCmp = ref<PriceCompareResp | null>(null)
const loading = ref(false)
const matrixLoading = ref(false)

const dimTabs: { key: BiDim; label: string }[] = [
  { key: 'machine', label: '按机器' },
  { key: 'category', label: '按品类' },
  { key: 'product', label: '按单品' },
  { key: 'slot', label: '按货道' },
  { key: 'timeslot', label: '时段·星期' },
  { key: 'supplier', label: '按供应商' },
]

async function loadTop() {
  loading.value = true
  try {
    const [q, b, s, p] = await Promise.all([
      biApi.quadrant(month.value || undefined),
      biApi.basket(month.value || undefined),
      biApi.stockoutLoss(month.value || undefined),
      biApi.priceCompare(),
    ])
    quadrant.value = q
    basket.value = b
    stockout.value = s
    priceCmp.value = p
    months.value = q.months
    dataAsOf.value = q.dataAsOf
    if (!month.value) month.value = q.month
  } finally {
    loading.value = false
  }
}

async function loadMatrix() {
  matrixLoading.value = true
  try {
    matrix.value = await biApi.matrix(month.value || undefined, activeDim.value)
  } finally {
    matrixLoading.value = false
  }
}

function onDimChange(dim: BiDim) {
  activeDim.value = dim
  loadMatrix()
}

async function reloadAll() {
  await loadTop()
  await loadMatrix()
}

onMounted(reloadAll)

// ---------- 四象限散点(轻量 SVG,别引图表库) ----------
const W = 560
const H = 380
const PAD = 46
const quadColor: Record<string, string> = {
  明星: '#2f9e44',
  引流: '#1c7ed6',
  利基: '#f08c00',
  淘汰: '#e03131',
}
const quadLabel: Record<string, string> = {
  明星: '⭐ 明星(多备货·多货道)',
  引流: '🚰 引流(保供不断货)',
  利基: '💎 利基(试提量·放利润机)',
  淘汰: '⛔ 淘汰(清仓下架)',
}

const scatter = computed(() => {
  const pts = quadrant.value?.points ?? []
  if (!pts.length) return null
  const maxQty = Math.max(...pts.map((p) => Number(p.salesQty)), 1)
  const margins = pts.map((p) => Number(p.marginPct))
  const maxM = Math.max(...margins, 1)
  const minM = Math.min(...margins, 0)
  const spanM = maxM - minM || 1
  const qMed = Number(quadrant.value?.qtyMedian ?? 0)
  const mMed = Number(quadrant.value?.marginMedian ?? 0)
  const x = (qty: number) => PAD + (qty / maxQty) * (W - PAD * 2)
  const y = (m: number) => H - PAD - ((m - minM) / spanM) * (H - PAD * 2)
  return {
    maxQty,
    maxM,
    minM,
    xMed: x(qMed),
    yMed: y(mMed),
    dots: pts.map((p) => ({
      cx: x(Number(p.salesQty)),
      cy: y(Number(p.marginPct)),
      color: quadColor[p.quadrant ?? ''] ?? '#868e96',
      p,
    })),
    x,
    y,
  }
})

const quadGroups = computed(() => {
  const g: Record<string, QuadrantPoint[]> = { 明星: [], 引流: [], 利基: [], 淘汰: [] }
  for (const p of quadrant.value?.points ?? []) {
    if (p.quadrant && g[p.quadrant]) g[p.quadrant].push(p)
  }
  for (const k of Object.keys(g)) g[k].sort((a, b) => Number(b.salesQty) - Number(a.salesQty))
  return g
})

const hovered = ref<QuadrantPoint | null>(null)

const fmt = (v: number | null | undefined, dash = '—') =>
  v == null ? dash : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const fmtQty = (v: number | null | undefined) => (v == null ? '—' : Number(v).toLocaleString())
const pct = (v: number | null | undefined) => (v == null ? '—' : `${v}%`)

function goProduct(id: number | null) {
  if (id) router.push(`/products/${id}`)
}
function goMachine(id: number | null) {
  if (id) router.push(`/machines/${id}`)
}
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / BI 经营分析</div>
    <div class="ledger-title">
      <h2>BI 经营分析</h2>
      <span class="sub">销售看"卖得动",利润看"值得卖" · 机器 / 品类 / 单品 / 货道 / 时段 / 供应商 六维切着看</span>
    </div>

    <el-alert type="info" :closable="false" class="mb-12px">
      <template #title>
        <b>⚡ 口径写死(§13)</b>:毛利 = 实收金额 − 移动加权成本 · 销售额 = 正常 + 退款(负) ·
        兑换/线下收入按 0 但计成本 · 测试不计 · 无采购史/取不到的数一律显「—」<b>不造假</b>
      </template>
    </el-alert>

    <!-- 月份切换 -->
    <el-card shadow="never" class="mb-12px">
      <div class="flex items-center gap-12px flex-wrap">
        <b>分析月份</b>
        <el-select v-model="month" style="width: 140px" @change="reloadAll">
          <el-option v-for="m in months" :key="m" :label="m" :value="m" />
        </el-select>
        <el-button size="small" @click="reloadAll">刷新</el-button>
        <el-tag effect="plain" type="success" size="large" style="margin-left: auto">
          数据截至 {{ dataAsOf ?? '——(尚未导入)' }}
        </el-tag>
      </div>
    </el-card>

    <!-- AI 洞察位(等 AI 票) -->
    <el-card shadow="never" class="mb-12px ai-placeholder">
      <div class="flex items-center gap-12px">
        <span style="font-size: 18px">🤖</span>
        <div class="flex-1">
          <b class="text-gray-500">AI 经营洞察 · 自然语言问数</b>
          <div class="text-12px text-gray-400">
            "上个月哪台机毛利最高?" "东鹏最近四周卖得怎么样?" —— AI 转只读查询出表出图 · 待 AI 票(M4-3~)接入
          </div>
        </div>
        <el-tag type="info" effect="plain">敬请期待</el-tag>
      </div>
    </el-card>

    <!-- 招牌视图:单品四象限 -->
    <el-card shadow="never" class="mb-12px">
      <template #header>
        <div class="flex items-center gap-8px">
          <b>单品四象限</b>
          <span class="text-12px text-gray-400">销量 × 毛利率 · 阈值=中位数 · 3 秒定策略</span>
          <span class="text-12px text-gray-400" style="margin-left: auto" v-if="quadrant">
            阈值:销量 {{ fmtQty(quadrant.qtyMedian) }} · 毛利率 {{ pct(quadrant.marginMedian) }}
          </span>
        </div>
      </template>
      <div class="quad-wrap">
        <!-- 散点 SVG -->
        <div class="quad-svg">
          <svg v-if="scatter" :viewBox="`0 0 ${W} ${H}`" width="100%" preserveAspectRatio="xMidYMid meet">
            <!-- 象限底色 -->
            <rect :x="scatter.xMed" :y="PAD" :width="W - PAD - scatter.xMed" :height="scatter.yMed - PAD"
              fill="#2f9e44" opacity="0.06" />
            <rect :x="PAD" :y="PAD" :width="scatter.xMed - PAD" :height="scatter.yMed - PAD"
              fill="#f08c00" opacity="0.06" />
            <rect :x="scatter.xMed" :y="scatter.yMed" :width="W - PAD - scatter.xMed" :height="H - PAD - scatter.yMed"
              fill="#1c7ed6" opacity="0.06" />
            <rect :x="PAD" :y="scatter.yMed" :width="scatter.xMed - PAD" :height="H - PAD - scatter.yMed"
              fill="#e03131" opacity="0.06" />
            <!-- 中位数十字线 -->
            <line :x1="scatter.xMed" :y1="PAD" :x2="scatter.xMed" :y2="H - PAD" stroke="#adb5bd"
              stroke-dasharray="4 4" />
            <line :x1="PAD" :y1="scatter.yMed" :x2="W - PAD" :y2="scatter.yMed" stroke="#adb5bd"
              stroke-dasharray="4 4" />
            <!-- 轴 -->
            <line :x1="PAD" :y1="H - PAD" :x2="W - PAD" :y2="H - PAD" stroke="#ced4da" />
            <line :x1="PAD" :y1="PAD" :x2="PAD" :y2="H - PAD" stroke="#ced4da" />
            <text :x="W - PAD" :y="H - PAD + 20" text-anchor="end" font-size="11" fill="#868e96">销量 →</text>
            <text :x="PAD - 8" :y="PAD - 8" text-anchor="start" font-size="11" fill="#868e96">↑ 毛利率</text>
            <!-- 象限角标 -->
            <text :x="W - PAD - 4" :y="PAD + 14" text-anchor="end" font-size="12" fill="#2f9e44" font-weight="700">明星</text>
            <text :x="PAD + 4" :y="PAD + 14" text-anchor="start" font-size="12" fill="#f08c00" font-weight="700">利基</text>
            <text :x="W - PAD - 4" :y="H - PAD - 6" text-anchor="end" font-size="12" fill="#1c7ed6" font-weight="700">引流</text>
            <text :x="PAD + 4" :y="H - PAD - 6" text-anchor="start" font-size="12" fill="#e03131" font-weight="700">淘汰</text>
            <!-- 点 -->
            <circle v-for="(d, i) in scatter.dots" :key="i" :cx="d.cx" :cy="d.cy" r="5" :fill="d.color"
              opacity="0.72" stroke="#fff" stroke-width="0.8" style="cursor: pointer"
              @mouseenter="hovered = d.p" @mouseleave="hovered = null" @click="goProduct(d.p.productId)" />
          </svg>
          <el-empty v-else description="本月暂无可分类单品(需有销量且有成本)" :image-size="60" />
          <div v-if="hovered" class="quad-tip">
            <b>{{ hovered.name }}</b> · {{ hovered.quadrant }}<br />
            销量 {{ fmtQty(hovered.salesQty) }} · 毛利率 {{ pct(hovered.marginPct) }} · 毛利额 ¥{{ fmt(hovered.grossProfit) }}
          </div>
        </div>
        <!-- 四象限文字分组 -->
        <div class="quad-groups">
          <div v-for="q in (['明星', '引流', '利基', '淘汰'] as const)" :key="q" class="quad-box"
            :style="{ borderLeftColor: quadColor[q] }">
            <div class="quad-box-t" :style="{ color: quadColor[q] }">
              {{ quadLabel[q] }} <span class="text-gray-400">({{ quadGroups[q].length }})</span>
            </div>
            <div class="quad-box-items">
              <span v-for="p in quadGroups[q].slice(0, 6)" :key="p.productId" class="quad-chip"
                @click="goProduct(p.productId)">{{ p.name }}</span>
              <span v-if="!quadGroups[q].length" class="text-12px text-gray-300">—</span>
            </div>
          </div>
        </div>
      </div>
      <div v-if="quadrant?.noCostPoints?.length" class="text-12px text-amber-600 mt-8px">
        ⚠️ {{ quadrant.noCostPoints.length }} 个单品有销量但无采购史(毛利率算不出),不入象限,单列「成本待补」
      </div>
    </el-card>

    <!-- 六维矩阵 -->
    <el-card shadow="never" class="mb-12px">
      <template #header>
        <div class="flex items-center gap-8px flex-wrap">
          <b>维度 × 指标矩阵</b>
          <span class="text-12px text-gray-400">销售(卖得动) 与 毛利(值得卖) 并排看 —— 卖得多 ≠ 赚得多</span>
        </div>
      </template>
      <el-radio-group :model-value="activeDim" @change="(v: string | number | boolean | undefined) => onDimChange(v as BiDim)" size="small" class="mb-12px">
        <el-radio-button v-for="t in dimTabs" :key="t.key" :value="t.key">{{ t.label }}</el-radio-button>
      </el-radio-group>

      <!-- 机器维 -->
      <el-table v-if="activeDim === 'machine'" v-loading="matrixLoading" :data="matrix?.rows ?? []" size="small" border>
        <el-table-column label="机器" min-width="130">
          <template #default="{ row }">
            <a class="link" @click="goMachine(row.key)">{{ row.name }}</a>
          </template>
        </el-table-column>
        <el-table-column label="销售额" align="right" width="110">
          <template #default="{ row }">¥{{ fmt(row.salesAmt) }}</template>
        </el-table-column>
        <el-table-column label="日均销售" align="right" width="100">
          <template #default="{ row }">{{ row.dailyAvgAmt == null ? '—' : `¥${fmt(row.dailyAvgAmt)}` }}</template>
        </el-table-column>
        <el-table-column label="毛利额" align="right" width="110">
          <template #default="{ row }">
            <b class="text-green-700">{{ row.grossProfit == null ? '—' : `¥${fmt(row.grossProfit)}` }}</b>
          </template>
        </el-table-column>
        <el-table-column label="毛利率" align="right" width="90">
          <template #default="{ row }">{{ pct(row.marginPct) }}</template>
        </el-table-column>
        <el-table-column label="存销比" align="right" width="90">
          <template #default="{ row }">{{ row.stockDays == null ? '—' : `${row.stockDays} 天` }}</template>
        </el-table-column>
        <el-table-column label="缺货损失" align="right" width="100">
          <template #default="{ row }">
            <span class="text-red-600">{{ row.stockoutLossAmt == null ? '—' : `¥${fmt(row.stockoutLossAmt)}` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="损耗额" align="right" width="90">
          <template #default="{ row }">{{ row.lossAmt == null ? '—' : `¥${fmt(row.lossAmt)}` }}</template>
        </el-table-column>
      </el-table>

      <!-- 品类维 -->
      <el-table v-else-if="activeDim === 'category'" v-loading="matrixLoading" :data="matrix?.rows ?? []" size="small" border>
        <el-table-column label="品类" prop="name" min-width="110" />
        <el-table-column label="销售额" align="right" width="110">
          <template #default="{ row }">¥{{ fmt(row.salesAmt) }}</template>
        </el-table-column>
        <el-table-column label="占比" align="right" width="80">
          <template #default="{ row }">{{ pct(row.sharePct) }}</template>
        </el-table-column>
        <el-table-column label="毛利额" align="right" width="110">
          <template #default="{ row }"><b class="text-green-700">{{ row.grossProfit == null ? '—' : `¥${fmt(row.grossProfit)}` }}</b></template>
        </el-table-column>
        <el-table-column label="毛利率" align="right" width="90">
          <template #default="{ row }">{{ pct(row.marginPct) }}</template>
        </el-table-column>
        <el-table-column label="动销率" align="right" width="120">
          <template #default="{ row }">
            {{ pct(row.activeRate) }}
            <span class="text-11px text-gray-400" v-if="row.onSaleSkuCount != null">({{ row.soldSkuCount }}/{{ row.onSaleSkuCount }})</span>
          </template>
        </el-table-column>
        <el-table-column label="损耗额" align="right" width="90">
          <template #default="{ row }">{{ row.lossAmt == null ? '—' : `¥${fmt(row.lossAmt)}` }}</template>
        </el-table-column>
      </el-table>

      <!-- 单品维 -->
      <el-table v-else-if="activeDim === 'product'" v-loading="matrixLoading" :data="matrix?.rows ?? []" size="small" border
        max-height="480">
        <el-table-column label="商品" min-width="160">
          <template #default="{ row }"><a class="link" @click="goProduct(row.key)">{{ row.name }}</a></template>
        </el-table-column>
        <el-table-column label="品类" prop="category" width="90">
          <template #default="{ row }">{{ row.category ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="销量" align="right" width="80">
          <template #default="{ row }">{{ fmtQty(row.salesQty) }}</template>
        </el-table-column>
        <el-table-column label="销售额" align="right" width="100">
          <template #default="{ row }">¥{{ fmt(row.salesAmt) }}</template>
        </el-table-column>
        <el-table-column label="毛利率" align="right" width="90">
          <template #default="{ row }">
            <span v-if="row.hasCost === false" class="text-amber-600">—(成本待补)</span>
            <span v-else>{{ pct(row.marginPct) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="周转天" align="right" width="90">
          <template #default="{ row }">{{ row.stockDays == null ? '—' : `${row.stockDays} 天` }}</template>
        </el-table-column>
        <el-table-column label="损耗额" align="right" width="90">
          <template #default="{ row }">{{ row.lossAmt == null ? '—' : `¥${fmt(row.lossAmt)}` }}</template>
        </el-table-column>
      </el-table>

      <!-- 货道维 -->
      <el-table v-else-if="activeDim === 'slot'" v-loading="matrixLoading" :data="matrix?.rows ?? []" size="small" border
        max-height="480">
        <el-table-column label="机器 · 货道" prop="name" min-width="160" />
        <el-table-column label="绑定商品" prop="code" min-width="130">
          <template #default="{ row }">{{ row.code ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="出货量" align="right" width="90">
          <template #default="{ row }">{{ fmtQty(row.salesQty) }}</template>
        </el-table-column>
        <el-table-column label="货道日均毛利" align="right" width="120">
          <template #default="{ row }">
            <b class="text-green-700">{{ row.dailyAvgAmt == null ? '—' : `¥${fmt(row.dailyAvgAmt)}` }}</b>
          </template>
        </el-table-column>
        <el-table-column label="现量(推算)" align="right" width="120">
          <template #default="{ row }">
            <span v-if="row.estShared">按品合并·拆不开</span>
            <span v-else>{{ row.estQty == null ? '—' : fmtQty(row.estQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="吞货率" align="right" width="90">
          <template #default="{ row }">{{ pct(row.swallowRate) }}</template>
        </el-table-column>
      </el-table>

      <!-- 时段·星期维 -->
      <div v-else-if="activeDim === 'timeslot'" v-loading="matrixLoading">
        <div class="text-12px text-gray-400 mb-8px">§10.1:时段维只看"什么点最好卖",毛利格不适用(—)</div>
        <div class="grid grid-cols-2 gap-12px">
          <el-table :data="matrix?.rows ?? []" size="small" border>
            <el-table-column label="时段(2h)" prop="name" min-width="100" />
            <el-table-column label="销售额" align="right" width="110">
              <template #default="{ row }">¥{{ fmt(row.salesAmt) }}</template>
            </el-table-column>
            <el-table-column label="占比" align="right" width="90">
              <template #default="{ row }">
                <div class="heat-cell">
                  <div class="heat-bar" :style="{ width: `${Number(row.sharePct ?? 0) * 4}px` }"></div>
                  {{ pct(row.sharePct) }}
                </div>
              </template>
            </el-table-column>
          </el-table>
          <el-table :data="matrix?.weekdayRows ?? []" size="small" border>
            <el-table-column label="星期" prop="name" min-width="80" />
            <el-table-column label="销售额" align="right" width="110">
              <template #default="{ row }">¥{{ fmt(row.salesAmt) }}</template>
            </el-table-column>
            <el-table-column label="占比" align="right" width="90">
              <template #default="{ row }">{{ pct(row.sharePct) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <!-- 供应商维 -->
      <el-table v-else v-loading="matrixLoading" :data="matrix?.rows ?? []" size="small" border>
        <el-table-column label="供应商" prop="name" min-width="130" />
        <el-table-column label="采购额" align="right" width="110">
          <template #default="{ row }">{{ row.purchaseAmt == null ? '—' : `¥${fmt(row.purchaseAmt)}` }}</template>
        </el-table-column>
        <el-table-column label="依赖占比" align="right" width="100">
          <template #default="{ row }">{{ pct(row.purchaseSharePct) }}</template>
        </el-table-column>
        <el-table-column label="毛利贡献" align="right" width="110">
          <template #default="{ row }"><b class="text-green-700">{{ row.marginContribution == null ? '—' : `¥${fmt(row.marginContribution)}` }}</b></template>
        </el-table-column>
        <el-table-column label="归属SKU" align="right" width="90">
          <template #default="{ row }">{{ row.attributedSkuCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="实收差异率" align="right" width="110">
          <template #default="{ row }">{{ pct(row.receiveDiffRate) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无供应商关联的采购单(本数据成本来自期初,无采购入库带供应商)" :image-size="60" />
        </template>
      </el-table>
    </el-card>

    <div class="grid grid-cols-2 gap-12px mb-12px">
      <!-- 客单价/连带/支付 -->
      <el-card shadow="never">
        <template #header><b>客单价 · 连带 · 支付方式</b></template>
        <div v-if="basket" class="flex gap-12px mb-12px">
          <div class="stat-box">
            <div class="stat-v">¥{{ fmt(basket.avgOrderValue) }}</div>
            <div class="stat-l">客单价</div>
          </div>
          <div class="stat-box">
            <div class="stat-v">{{ fmtQty(basket.orderCount) }}</div>
            <div class="stat-l">订单数</div>
          </div>
          <div class="stat-box">
            <div class="stat-v">{{ pct(basket.multiItemSharePct) }}</div>
            <div class="stat-l">多件单占比({{ basket.multiItemOrderCount }})</div>
          </div>
        </div>
        <div class="text-12px text-gray-500 mb-4px">💡 连带 TOP 组合(指导相邻货道摆放)</div>
        <el-table :data="basket?.combos ?? []" size="small" border max-height="180">
          <el-table-column label="组合" min-width="200">
            <template #default="{ row }">{{ row.productA }} + {{ row.productB }}</template>
          </el-table-column>
          <el-table-column label="次数" align="right" width="80" prop="times" />
          <template #empty><el-empty description="本月无多件同单组合" :image-size="48" /></template>
        </el-table>
        <div class="text-12px text-gray-500 mt-8px mb-4px">支付方式占比</div>
        <div class="flex gap-8px flex-wrap">
          <el-tag v-for="p in basket?.payMethods ?? []" :key="p.payMethod" effect="plain" type="info">
            {{ p.payMethod }} ¥{{ fmt(p.amt) }} · {{ pct(p.sharePct) }}
          </el-tag>
        </div>
      </el-card>

      <!-- 缺货损失 -->
      <el-card shadow="never">
        <template #header>
          <div class="flex items-center gap-8px">
            <b>缺货损失估算</b>
            <span class="text-12px text-gray-400" style="margin-left: auto" v-if="stockout">
              合计 ¥{{ fmt(stockout.totalEstLoss) }}
            </span>
          </div>
        </template>
        <el-table :data="stockout?.rows ?? []" size="small" border max-height="240">
          <el-table-column label="机器 / 商品" min-width="160">
            <template #default="{ row }">{{ row.machineName }} / {{ row.productName }}</template>
          </el-table-column>
          <el-table-column label="缺货天" align="right" width="90">
            <template #default="{ row }">{{ row.stockoutDays }}/{{ row.coverageDays }} 天</template>
          </el-table-column>
          <el-table-column label="日均毛利" align="right" width="100">
            <template #default="{ row }">¥{{ fmt(row.dailyGross) }}</template>
          </el-table-column>
          <el-table-column label="估算损失" align="right" width="100">
            <template #default="{ row }"><b class="text-red-600">¥{{ fmt(row.estLoss) }}</b></template>
          </el-table-column>
          <template #empty><el-empty description="本月无可估算的缺货损失(需库存快照锚点)" :image-size="48" /></template>
        </el-table>
        <div class="text-11px text-gray-400 mt-8px" v-if="stockout">{{ stockout.note }}</div>
      </el-card>
    </div>

    <!-- 调价前后对比 -->
    <el-card shadow="never" class="mb-12px">
      <template #header><b>调价前后 14 天对比</b> <span class="text-12px text-gray-400">定价 PDCA 的 C · price_log 有生效日的记录</span></template>
      <el-table :data="priceCmp?.rows ?? []" size="small" border max-height="320">
        <el-table-column label="商品" prop="productName" min-width="160" />
        <el-table-column label="调价" align="center" width="130">
          <template #default="{ row }">¥{{ fmt(row.oldPrice) }} → ¥{{ fmt(row.newPrice) }}</template>
        </el-table-column>
        <el-table-column label="生效日" prop="effectDate" width="110" />
        <el-table-column label="前日均销量" align="right" width="110">
          <template #default="{ row }">{{ row.beforeAvgQty == null ? '—' : fmt(row.beforeAvgQty) }}</template>
        </el-table-column>
        <el-table-column label="后日均销量" align="right" width="130">
          <template #default="{ row }">
            {{ row.afterAvgQty == null ? '—' : fmt(row.afterAvgQty) }}
            <el-tag v-if="row.partial" size="small" type="warning" effect="plain">未满14天</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="销量变化" align="right" width="100">
          <template #default="{ row }">
            <span v-if="row.qtyChangePct == null">—</span>
            <span v-else :class="Number(row.qtyChangePct) < 0 ? 'text-red-600' : 'text-green-700'">
              {{ Number(row.qtyChangePct) > 0 ? '+' : '' }}{{ row.qtyChangePct }}%
            </span>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无带生效日的调价记录" :image-size="48" /></template>
      </el-table>
    </el-card>

    <p class="foot-note">— 指标口径:动销率/存销比/售罄率按行业通行定义;缺货损失=缺货天数×有货日日均毛利,无锚点不估(不造假) —</p>
  </div>
</template>

<style scoped>
.ai-placeholder {
  background: #f7f8fa;
  border: 1px dashed var(--el-border-color);
}
.quad-wrap {
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: 16px;
}
@media (max-width: 768px) {
  .quad-wrap {
    grid-template-columns: 1fr;
  }
}
.quad-svg {
  position: relative;
  min-height: 300px;
}
.quad-tip {
  position: absolute;
  top: 8px;
  left: 8px;
  background: rgba(33, 37, 41, 0.9);
  color: #fff;
  font-size: 12px;
  line-height: 1.6;
  padding: 6px 10px;
  border-radius: 6px;
  pointer-events: none;
}
.quad-groups {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.quad-box {
  border-left: 3px solid #ccc;
  padding: 6px 10px;
  background: #fafafa;
  border-radius: 4px;
}
.quad-box-t {
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 4px;
}
.quad-box-items {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.quad-chip {
  font-size: 11px;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 1px 6px;
  cursor: pointer;
}
.quad-chip:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.stat-box {
  flex: 1;
  text-align: center;
  padding: 10px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}
.stat-v {
  font-size: 20px;
  font-weight: 700;
}
.stat-l {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.link {
  color: var(--el-color-primary);
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}
.heat-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}
.heat-bar {
  height: 8px;
  background: linear-gradient(90deg, #74c0fc, #1c7ed6);
  border-radius: 4px;
}
.foot-note {
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 16px 0;
}
</style>
