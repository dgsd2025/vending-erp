<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { reportApi, type StockLedgerRow, type StockResp, type StockRow } from '@/api/report'

/**
 * 库存管理页(M1-6,对照 mockup p13):
 * 每个商品:在哪(仓库/机器两级)、有多少、值多少 —— 一张表说清。
 * 库存不能手改——只能被单据改;每个数字点开都是流水。负库存红灯行。
 */

const router = useRouter()
const loading = ref(false)
const data = ref<StockResp | null>(null)
const keyword = ref('')
const filterMode = ref<'all' | 'negative' | 'active'>('all')

async function load() {
  loading.value = true
  try {
    data.value = await reportApi.stock()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const rows = computed(() => {
  if (!data.value) return []
  let list = data.value.rows
  if (filterMode.value === 'negative') list = list.filter((r) => r.negative)
  if (filterMode.value === 'active') list = list.filter((r) => Number(r.totalQty) !== 0)
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(
      (r) => r.name.toLowerCase().includes(kw) || (r.code ?? '').toLowerCase().includes(kw),
    )
  }
  return list
})

const activeCount = computed(() => data.value?.rows.filter((r) => Number(r.totalQty) !== 0).length ?? 0)

const fmtQty = (v: number | null | undefined) => (v == null ? '—' : Number(v).toLocaleString())
const rowClass = ({ row }: { row: StockRow }) => (row.negative ? 'neg-row' : '')
const fmtAmt = (v: number | null | undefined) =>
  v == null ? '—(成本待补)' : `¥${Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })}`

// ---------- 单品流水抽屉(p13:每个数字点开都是流水) ----------
const ledgerVisible = ref(false)
const ledgerRows = ref<StockLedgerRow[]>([])
const ledgerProduct = ref<StockRow | null>(null)
const ledgerLoading = ref(false)

async function openLedger(row: StockRow) {
  ledgerProduct.value = row
  ledgerVisible.value = true
  ledgerLoading.value = true
  try {
    ledgerRows.value = await reportApi.productLedger(row.productId, 100)
  } finally {
    ledgerLoading.value = false
  }
}

// ---------- 成本重算 ----------
const recalcLoading = ref(false)
async function doRecalc() {
  recalcLoading.value = true
  try {
    const r = await reportApi.recalc()
    ElMessage.success(`成本快照已回写:销售 ${r.saleUpdated} 行 · 流水 ${r.ledgerUpdated} 行(${r.products} 个 SKU)`)
    await load()
  } finally {
    recalcLoading.value = false
  }
}
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 日常台账 / 库存管理</div>
    <div class="ledger-title">
      <h2>库存管理</h2>
      <span class="sub">每个商品:在哪、有多少、值多少 —— 一张表说清</span>
    </div>
    <el-alert type="info" :closable="false" class="mb-12px">
      <template #title>
        库存不能手改——只能被单据改(采购入库/出库上架/销售/盘点/报损),每个数字点开都是流水。
        <b>仓库列 = 自己账(Σ流水),机器列 = 推算账(最近快照 + 之后按业务时间增量)</b>
      </template>
    </el-alert>

    <!-- 筛选条(p13 chips) -->
    <el-card shadow="never" class="mb-12px">
      <div class="flex items-center gap-8px flex-wrap">
        <el-check-tag :checked="filterMode === 'all'" @change="filterMode = 'all'">
          全部 {{ data?.rows.length ?? 0 }}
        </el-check-tag>
        <el-check-tag :checked="filterMode === 'active'" @change="filterMode = 'active'">
          有库存 {{ activeCount }}
        </el-check-tag>
        <el-check-tag
          :checked="filterMode === 'negative'"
          @change="filterMode = 'negative'"
          :class="data?.negativeCount ? 'neg-tag' : ''"
        >
          🚨 负库存 {{ data?.negativeCount ?? 0 }}
        </el-check-tag>
        <el-input
          v-model="keyword"
          placeholder="搜商品名 / 编码…"
          clearable
          style="width: 220px; margin-left: auto"
        />
        <el-button size="small" :loading="recalcLoading" @click="doRecalc">♻️ 成本重算回写</el-button>
        <el-button size="small" @click="load">刷新</el-button>
        <el-tag effect="plain" type="success" size="large">
          数据截至 {{ data?.dataAsOf ?? '——(尚未导入)' }}
        </el-tag>
      </div>
    </el-card>

    <!-- 两级库存主表 -->
    <el-card shadow="never">
      <el-table :data="rows" size="small" :row-class-name="rowClass" max-height="640">
        <el-table-column label="商品" min-width="180" fixed>
          <template #default="{ row }">
            <b class="name-link" @click="router.push(`/products/${row.productId}`)">{{ row.name }} ↗</b>
            <span class="text-11px text-gray-400 ml-4px">{{ row.code }}</span>
            <el-tag v-if="row.productStatus && row.productStatus !== '在售'" size="small" type="warning" class="ml-4px">
              {{ row.productStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="🏬 仓库" align="right" width="90">
          <template #default="{ row }">
            <b :class="Number(row.warehouseQty) < 0 ? 'text-red-600' : ''">{{ fmtQty(row.warehouseQty) }}</b>
          </template>
        </el-table-column>
        <el-table-column
          v-for="m in data?.machines ?? []"
          :key="m.machineId"
          :label="m.machineName"
          align="right"
          width="90"
        >
          <template #default="{ row }">
            <span
              v-if="row.machineQty[m.machineId] != null"
              :class="Number(row.machineQty[m.machineId]) < 0 ? 'text-red-600 font-bold' : ''"
            >
              {{ fmtQty(row.machineQty[m.machineId]) }}
            </span>
            <span v-else class="text-gray-300">—</span>
          </template>
        </el-table-column>
        <el-table-column label="合计" align="right" width="90">
          <template #default="{ row }">
            <b>{{ fmtQty(row.totalQty) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="加权单位成本" align="right" width="110">
          <template #default="{ row }">
            <span v-if="row.unitCost != null">¥{{ row.unitCost }}</span>
            <el-tooltip v-else content="无采购史:先补录采购入库,成本才有依据(禁 0 参与加权)">
              <span class="text-amber-600">—(成本待补)</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="成本金额" align="right" width="110">
          <template #default="{ row }">{{ fmtAmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.negative" type="danger" size="small">🚨 负库存</el-tag>
            <el-tag v-else-if="Number(row.totalQty) === 0" type="info" size="small">无库存</el-tag>
            <el-tag v-else type="success" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="" width="160">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openLedger(row)">流水 ▸</el-button>
            <el-button link type="success" size="small" @click="router.push(`/products/${row.productId}`)">单品页 →</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的商品" :image-size="60" />
        </template>
      </el-table>
      <div class="text-12px text-gray-500 mt-8px text-center">
        共 {{ rows.length }} 行 · 合计库存资产
        <b>{{ fmtAmt(data?.totalAmount) }}</b>
        (仓库 {{ fmtAmt(data?.warehouseAmount) }} + 机内 {{ fmtAmt(data?.machineAmount) }})
        —— 与资产家底页同一个数,单一真相源
      </div>
    </el-card>

    <p class="text-11px text-gray-400 text-center mt-8px">
      — 机器列 = 最近后台快照 + 之后单据/出货增量推算(与导入顺序无关);负库存 = 待补录采购红灯 —
    </p>

    <!-- 单品流水抽屉 -->
    <el-drawer v-model="ledgerVisible" size="640px" :title="`📜 ${ledgerProduct?.name ?? ''} · 库存流水(每一笔都有单据)`">
      <el-table :data="ledgerRows" v-loading="ledgerLoading" size="small">
        <el-table-column prop="bizTime" label="时间" width="150" />
        <el-table-column prop="docNo" label="单据" width="150">
          <template #default="{ row }">
            <span class="font-mono text-12px">{{ row.docNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ row.docType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账本" width="90">
          <template #default="{ row }">
            {{ row.locationType === '机器' ? (row.machineName ?? '机器') : '🏬 仓库' }}
          </template>
        </el-table-column>
        <el-table-column label="±" align="right" width="70">
          <template #default="{ row }">
            <b :class="Number(row.changeQty) < 0 ? 'text-red-600' : 'text-green-700'">
              {{ Number(row.changeQty) > 0 ? '+' : '' }}{{ row.changeQty }}
            </b>
          </template>
        </el-table-column>
        <el-table-column prop="balanceQty" label="结存" align="right" width="70" />
        <el-table-column label="单位成本" align="right" width="90">
          <template #default="{ row }">{{ row.unitCost == null ? '—' : `¥${row.unitCost}` }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无流水(销售出货记在销售记录,不在此账)" :image-size="60" />
        </template>
      </el-table>
      <p class="text-11px text-gray-400 mt-8px">
        注:销售出货不产生库存流水行(机器账按「快照+销售增量」推算),此处仅显示单据流水。
      </p>
    </el-drawer>
  </div>
</template>

<style scoped>
:deep(.neg-row) {
  background: #fef2f2;
}
.neg-tag {
  --el-color-primary: #dc2626;
}
</style>
