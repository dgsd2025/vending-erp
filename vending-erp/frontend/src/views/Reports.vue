<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  reportApi,
  type GrossMarginResp,
  type InventorySummaryResp,
} from '@/api/report'
import { finreportApi, type ProfitResp } from '@/api/finreport'

/**
 * 报表页(M1-6):毛利报表(月份切换 × SKU/机器两维)+ 月度进销存汇总。
 * 口径(§13 定死):毛利 = 实收 − 移动加权成本;销售额 = 正常 + 退款(负),兑换收入 0 计成本,测试不计;
 * 无采购史 SKU 毛利显「—(成本待补)」,不进合计。
 * 七律#3(M1-10 P1-1 整改):毛利表 SKU 行/机器行名词可点,下钻单品/机器详情(row.key 即 id)。
 */
const router = useRouter()

const month = ref<string>('')
const months = ref<string[]>([])
const activeTab = ref<'sku' | 'machine' | 'inventory' | 'profit'>('sku')

const gmLoading = ref(false)
const gmSku = ref<GrossMarginResp | null>(null)
const gmMachine = ref<GrossMarginResp | null>(null)
const invLoading = ref(false)
const inv = ref<InventorySummaryResp | null>(null)
const profitLoading = ref(false)
const profit = ref<ProfitResp | null>(null)

async function loadAll() {
  gmLoading.value = true
  invLoading.value = true
  profitLoading.value = true
  try {
    const [sku, machine, summary, pl] = await Promise.all([
      reportApi.grossMargin(month.value || undefined, 'sku'),
      reportApi.grossMargin(month.value || undefined, 'machine'),
      reportApi.inventorySummary(month.value || undefined),
      finreportApi.profit(month.value || undefined),
    ])
    gmSku.value = sku
    gmMachine.value = machine
    inv.value = summary
    profit.value = pl
    // 月份候选 = 销售口径月 ∪ 利润表入账月(利润表可能只有流水没销售)
    months.value = Array.from(new Set([...sku.months, ...pl.months])).sort()
    if (!month.value) month.value = sku.month || pl.period
  } finally {
    gmLoading.value = false
    invLoading.value = false
    profitLoading.value = false
  }
}
onMounted(loadAll)

/** 利润表行样式:小计/合计加粗描边 */
function plRowClass(scope: { row: { subtotal: boolean } }) {
  return scope.row.subtotal ? 'pl-subtotal-row' : ''
}

const fmt = (v: number | null | undefined, dash = '—') =>
  v == null ? dash : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const fmtQty = (v: number | null | undefined) => (v == null ? '—' : Number(v).toLocaleString())
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / BI 经营分析 / 报表</div>
    <div class="ledger-title">
      <h2>报表</h2>
      <span class="sub">毛利报表(SKU / 机器两维)+ 月度进销存汇总</span>
    </div>
    <el-alert type="info" :closable="false" class="mb-12px">
      <template #title>
        口径写死(§13):毛利 = <b>实收金额 − 移动加权成本</b> · 销售额 = 正常 + 退款(负) ·
        兑换收入 0 但计成本 · 测试不计 · 无采购史 SKU 毛利显「—(成本待补)」不进合计
      </template>
    </el-alert>

    <el-card shadow="never" class="mb-12px">
      <div class="flex items-center gap-12px flex-wrap">
        <b>报表月份</b>
        <el-select v-model="month" style="width: 140px" @change="loadAll">
          <el-option v-for="m in months" :key="m" :label="m" :value="m" />
        </el-select>
        <el-button size="small" @click="loadAll">刷新</el-button>
        <el-tag effect="plain" type="success" size="large" style="margin-left: auto">
          数据截至 {{ gmSku?.dataAsOf ?? '——(尚未导入)' }}
        </el-tag>
      </div>
    </el-card>

    <!-- 月度汇总卡 -->
    <div class="grid grid-cols-4 gap-12px mb-12px" v-if="gmSku">
      <el-card shadow="never">
        <div class="text-12px text-gray-500">本月销售额</div>
        <div class="text-22px font-bold">¥{{ fmt(gmSku.totalSalesAmt, '0.00') }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="text-12px text-gray-500">加权成本(已计部分)</div>
        <div class="text-22px font-bold">¥{{ fmt(gmSku.totalCostAmt, '0.00') }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="text-12px text-gray-500">毛利</div>
        <div class="text-22px font-bold text-green-700">¥{{ fmt(gmSku.totalGrossProfit, '0.00') }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="text-12px text-gray-500">毛利率(有成本口径)</div>
        <div class="text-22px font-bold">{{ gmSku.totalMarginPct == null ? '—' : `${gmSku.totalMarginPct}%` }}</div>
        <div v-if="gmSku.noCostCount" class="text-11px text-amber-600">
          ⚠️ {{ gmSku.noCostCount }} 个无成本 SKU 未计入
        </div>
      </el-card>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <!-- 毛利 × SKU -->
        <el-tab-pane label="毛利报表 · 按商品" name="sku">
          <el-table :data="gmSku?.rows ?? []" v-loading="gmLoading" size="small" max-height="560">
            <el-table-column label="商品" min-width="190">
              <template #default="{ row }">
                <b v-if="row.key != null" class="name-link" @click="router.push(`/products/${row.key}`)">{{ row.name }} ↗</b>
                <b v-else>{{ row.name }}</b>
                <span class="text-11px text-gray-400 ml-4px">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="销量" align="right" width="80">
              <template #default="{ row }">{{ fmtQty(row.salesQty) }}</template>
            </el-table-column>
            <el-table-column label="销售额" align="right" width="110">
              <template #default="{ row }">¥{{ fmt(row.salesAmt) }}</template>
            </el-table-column>
            <el-table-column label="加权成本" align="right" width="110">
              <template #default="{ row }">
                <span v-if="row.hasCost">¥{{ fmt(row.costAmt) }}</span>
                <el-tooltip v-else content="无采购史,先补录采购入库(禁 0 参与加权,§13)">
                  <span class="text-amber-600">—(成本待补)</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="毛利" align="right" width="110">
              <template #default="{ row }">
                <b v-if="row.hasCost" :class="Number(row.grossProfit) < 0 ? 'text-red-600' : 'text-green-700'">
                  ¥{{ fmt(row.grossProfit) }}
                </b>
                <span v-else class="text-amber-600">—</span>
              </template>
            </el-table-column>
            <el-table-column label="毛利率" align="right" width="90">
              <template #default="{ row }">
                {{ row.hasCost && row.marginPct != null ? `${row.marginPct}%` : '—' }}
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="本月没有销售数据" :image-size="60" />
            </template>
          </el-table>
          <div class="text-12px text-gray-600 mt-8px">
            合计:销售额 <b>¥{{ fmt(gmSku?.totalSalesAmt) }}</b> · 成本 <b>¥{{ fmt(gmSku?.totalCostAmt) }}</b> ·
            毛利 <b class="text-green-700">¥{{ fmt(gmSku?.totalGrossProfit) }}</b> ·
            毛利率 <b>{{ gmSku?.totalMarginPct == null ? '—' : `${gmSku?.totalMarginPct}%` }}</b>
            <span v-if="gmSku?.noCostCount" class="text-amber-600">
              (另有 {{ gmSku?.noCostCount }} 个无成本 SKU,销售额 ¥{{
                fmt((gmSku?.totalSalesAmt ?? 0) - (gmSku?.costedSalesAmt ?? 0))
              }} 未计毛利)
            </span>
          </div>
        </el-tab-pane>

        <!-- 毛利 × 机器 -->
        <el-tab-pane label="毛利报表 · 按机器" name="machine">
          <el-table :data="gmMachine?.rows ?? []" v-loading="gmLoading" size="small" max-height="560">
            <el-table-column label="机器" min-width="160">
              <template #default="{ row }">
                <b v-if="row.key != null" class="name-link" @click="router.push(`/machines/${row.key}`)">{{ row.name }} ↗</b>
                <b v-else>{{ row.name }}</b>
                <span class="text-11px text-gray-400 ml-4px">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="销量" align="right" width="90">
              <template #default="{ row }">{{ fmtQty(row.salesQty) }}</template>
            </el-table-column>
            <el-table-column label="销售额" align="right" width="120">
              <template #default="{ row }">¥{{ fmt(row.salesAmt) }}</template>
            </el-table-column>
            <el-table-column label="加权成本" align="right" width="120">
              <template #default="{ row }">¥{{ fmt(row.costAmt) }}</template>
            </el-table-column>
            <el-table-column label="毛利" align="right" width="120">
              <template #default="{ row }">
                <b :class="Number(row.grossProfit) < 0 ? 'text-red-600' : 'text-green-700'">¥{{ fmt(row.grossProfit) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="毛利率" align="right" width="90">
              <template #default="{ row }">{{ row.marginPct != null ? `${row.marginPct}%` : '—' }}</template>
            </el-table-column>
            <el-table-column label="" min-width="140">
              <template #default="{ row }">
                <el-tag v-if="row.noCostSkuCount" type="warning" size="small">
                  ⚠️ {{ row.noCostSkuCount }} 笔无成本销售未计
                </el-tag>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="本月没有销售数据" :image-size="60" />
            </template>
          </el-table>
        </el-tab-pane>

        <!-- 进销存汇总 -->
        <el-tab-pane label="进销存汇总" name="inventory">
          <p class="text-12px text-gray-500 mt-0">
            全局口径(仓库+机器合计,销售即出库);金额按移动加权成本;期末上月 = 期初下月,连续结转。
          </p>
          <el-table :data="inv?.rows ?? []" v-loading="invLoading" size="small" max-height="520">
            <el-table-column label="商品" min-width="180" fixed>
              <template #default="{ row }">
                <b v-if="row.productId != null" class="name-link" @click="router.push(`/products/${row.productId}`)">{{ row.name }} ↗</b>
                <b v-else>{{ row.name }}</b>
                <span class="text-11px text-gray-400 ml-4px">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="期初数量" align="right" width="90">
              <template #default="{ row }">{{ fmtQty(row.openingQty) }}</template>
            </el-table-column>
            <el-table-column label="期初金额" align="right" width="100">
              <template #default="{ row }">{{ row.hasCost ? `¥${fmt(row.openingAmt)}` : '—' }}</template>
            </el-table-column>
            <el-table-column label="入库数量" align="right" width="90">
              <template #default="{ row }">{{ fmtQty(row.inQty) }}</template>
            </el-table-column>
            <el-table-column label="入库金额" align="right" width="100">
              <template #default="{ row }">{{ row.hasCost ? `¥${fmt(row.inAmt)}` : '—' }}</template>
            </el-table-column>
            <el-table-column label="出库数量" align="right" width="90">
              <template #default="{ row }">{{ fmtQty(row.outQty) }}</template>
            </el-table-column>
            <el-table-column label="出库金额" align="right" width="100">
              <template #default="{ row }">{{ row.hasCost ? `¥${fmt(row.outAmt)}` : '—' }}</template>
            </el-table-column>
            <el-table-column label="期末数量" align="right" width="90">
              <template #default="{ row }">
                <b :class="Number(row.closingQty) < 0 ? 'text-red-600' : ''">{{ fmtQty(row.closingQty) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="期末金额" align="right" width="100">
              <template #default="{ row }">{{ row.hasCost ? `¥${fmt(row.closingAmt)}` : '—' }}</template>
            </el-table-column>
            <template #empty>
              <el-empty description="本月没有进销存数据" :image-size="60" />
            </template>
          </el-table>
          <div class="text-12px text-gray-600 mt-8px" v-if="inv?.total">
            合计:期初 {{ fmtQty(inv.total.openingQty) }} 件 / ¥{{ fmt(inv.total.openingAmt) }} ·
            入库 {{ fmtQty(inv.total.inQty) }} 件 / ¥{{ fmt(inv.total.inAmt) }} ·
            出库 {{ fmtQty(inv.total.outQty) }} 件 / ¥{{ fmt(inv.total.outAmt) }} ·
            期末 {{ fmtQty(inv.total.closingQty) }} 件 / <b>¥{{ fmt(inv.total.closingAmt) }}</b>
          </div>
        </el-tab-pane>

        <!-- 简版利润表(M3-6) -->
        <el-tab-pane label="利润表" name="profit">
          <p class="text-12px text-gray-500 mt-0">
            口径 §13.1(效力最高):毛利 − 平台手续费 − 杂费 − 损耗 ± 成本调整 + 其他收入 ± 上期调整 =
            <b>经营利润</b>;按<b>入账月</b>聚合,每类资金流水在利润表有且只有一个去处。
          </p>
          <el-alert v-if="profit?.settleBanner" type="warning" :closable="false" class="mb-8px">
            <template #title>{{ profit.settleBanner }}</template>
          </el-alert>
          <el-alert v-if="profit?.locked" type="info" :closable="false" class="mb-8px">
            <template #title>🔒 {{ profit.lockedNote }}</template>
          </el-alert>
          <el-alert v-for="d in profit?.lockDiffNotes ?? []" :key="d.settlementId" type="warning" :closable="false" class="mb-8px">
            <template #title>
              ⚠️ 结算单 {{ d.stmtNo }}({{ d.periodStart }} ~ {{ d.periodEnd }} · {{ d.stlStatus }}):{{ d.note }}
            </template>
          </el-alert>

          <el-table :data="profit?.rows ?? []" v-loading="profitLoading" size="small"
                    :row-class-name="plRowClass" :show-header="true">
            <el-table-column label="项目" min-width="140">
              <template #default="{ row }">
                <b v-if="row.subtotal">{{ row.label }}</b>
                <span v-else>{{ row.label }}</span>
              </template>
            </el-table-column>
            <el-table-column label="金额(+增利 / −减利)" align="right" width="160">
              <template #default="{ row }">
                <b :class="Number(row.amount) < 0 ? 'text-red-600' : 'text-green-700'">
                  {{ Number(row.amount) < 0 ? '−' : '' }}¥{{ fmt(Math.abs(Number(row.amount))) }}
                </b>
              </template>
            </el-table-column>
            <el-table-column label="这行是什么(人话)" min-width="300">
              <template #default="{ row }">
                <span class="text-12px text-gray-500">{{ row.note }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="本月还没有可入表的销售或资金流水" :image-size="60" />
            </template>
          </el-table>
          <div class="text-12px text-gray-600 mt-8px" v-if="profit">
            {{ profit.period }} 经营利润
            <b :class="Number(profit.operatingProfit) < 0 ? 'text-red-600' : 'text-green-700'">
              ¥{{ fmt(profit.operatingProfit) }}
            </b>
            <span class="text-gray-400">
              · 另有本金往来(付货款/结算划转/互转/资金调整)净额 ¥{{ fmt(profit.nonPlNet) }},只动家底不动损益
            </span>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
:deep(.pl-subtotal-row) {
  background: var(--green-soft);
  font-weight: 600;
}
</style>
