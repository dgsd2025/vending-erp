<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeProductStatus,
  pageAliasPending,
  pageProducts,
  type Product,
} from '@/api/basedata'
import ProductFormDialog from './ProductFormDialog.vue'
import AliasManageDialog from './AliasManageDialog.vue'
import AliasPendingDrawer from './AliasPendingDrawer.vue'

/**
 * 商品档案面板(对照 mockup p3 列表部分):
 * 筛选 chip + 搜索 + 列表 + 新建/编辑/状态流转 + 别名管理 + 待绑队列。
 * 30 天销量/毛利率等经营数字属 M1-6 单品分析,此处显示占位「—」;行上留详情下钻钩子。
 * compact 模式给设置中心 Tab 用(隐藏下钻提示文案)。
 */
const props = defineProps<{ compact?: boolean }>()
const router = useRouter()

const rows = ref<Product[]>([])
const total = ref(0)
const loading = ref(false)
const pendingCount = ref(0)

const query = reactive({
  current: 1,
  size: 20,
  keyword: '',
  productStatus: '' as string,
})

const STATUS_CHIPS = [
  { label: '全部', value: '', chip: 'c-gray' },
  { label: '在售', value: '在售', chip: 'c-green' },
  { label: '清仓中', value: '清仓中', chip: 'c-amber' },
  { label: '停售', value: '停售', chip: 'c-red' },
]

const formVisible = ref(false)
const editing = ref<Product | null>(null)
const aliasVisible = ref(false)
const aliasProduct = ref<Product | null>(null)
const pendingVisible = ref(false)

const grossMargin = computed(() => (p: Product) => {
  const cost = Number(p.refCost)
  const price = Number(p.refPrice)
  if (!cost || !price || price <= 0) return null
  return Math.round(((price - cost) / price) * 1000) / 10
})

async function load() {
  loading.value = true
  try {
    const page = await pageProducts({
      current: query.current,
      size: query.size,
      keyword: query.keyword || undefined,
      productStatus: query.productStatus || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadPendingCount() {
  const page = await pageAliasPending({ current: 1, size: 1, pendingStatus: '待绑定' })
  pendingCount.value = page.total
}

function filterStatus(value: string) {
  query.productStatus = value
  query.current = 1
  load()
}

function openCreate() {
  editing.value = null
  formVisible.value = true
}

function openEdit(row: Product) {
  editing.value = row
  formVisible.value = true
}

function openAlias(row: Product) {
  aliasProduct.value = row
  aliasVisible.value = true
}

async function flip(row: Product, target: string) {
  const tips: Record<string, string> = {
    停售: '停售 ≠ 删除:有流水的商品永不删,只是不再采购/补货/售卖,历史照查。',
    清仓中: '清仓中:停止采购、不进补货建议,仍允许上架/退货/报损,把仓库残余卖完。',
    在售: '恢复在售:重新参与采购与补货计算。',
  }
  await ElMessageBox.confirm(`${tips[target]}\n确认把「${row.productName}」置为「${target}」?`, '状态流转', {
    type: 'warning',
  })
  await changeProductStatus(row.id!, target)
  ElMessage.success(`已置为「${target}」(op_log 已留痕)`)
  load()
}

function statusChip(status?: string) {
  if (status === '在售') return 'c-green'
  if (status === '清仓中') return 'c-amber'
  return 'c-red'
}

onMounted(() => {
  load()
  loadPendingCount()
})

defineExpose({ reload: load })
</script>

<template>
  <div>
    <div class="ledger-card" style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
      <span
        v-for="c in STATUS_CHIPS"
        :key="c.value"
        class="chip filter-chip"
        :class="[c.chip, { active: query.productStatus === c.value }]"
        @click="filterStatus(c.value)"
      >
        {{ c.label }}
      </span>
      <span
        class="chip filter-chip"
        style="background: #fff; border: 1.5px dashed var(--amber); color: var(--amber)"
        @click="pendingVisible = true"
      >
        ⚠️ 待绑别名 {{ pendingCount }}
      </span>
      <el-input
        v-model="query.keyword"
        placeholder="搜索商品名 / 编码 / 条码…"
        clearable
        style="margin-left: auto; width: 230px"
        @keyup.enter="query.current = 1; load()"
        @clear="query.current = 1; load()"
      />
      <el-button type="primary" @click="openCreate">＋ 新建商品</el-button>
    </div>

    <div class="ledger-card" style="padding: 0; overflow: hidden">
      <el-table :data="rows" v-loading="loading" @row-dblclick="openEdit">
        <el-table-column prop="skuCode" label="编码" width="90">
          <template #default="{ row }">
            <span class="num mini">{{ row.skuCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">
            <b class="name-link" @click="router.push(`/products/${row.id}`)">{{ row.productName }} ↗</b>
            <span v-if="row.legacyCode" class="chip c-gray" style="margin-left: 6px">原码 {{ row.legacyCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="80" />
        <el-table-column label="参考成本" width="90" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.refCost != null ? Number(row.refCost).toFixed(2) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="参考售价" width="90" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.refPrice != null ? Number(row.refPrice).toFixed(2) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="参考毛利率" width="100" align="right">
          <template #default="{ row }">
            <span class="num">{{ grossMargin(row) != null ? grossMargin(row) + '%' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="30天销量" width="90" align="right">
          <template #default>
            <span class="mini">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="chip" :class="statusChip(row.productStatus)">{{ row.productStatus }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link size="small" @click="openAlias(row)">别名</el-button>
            <el-button v-if="row.productStatus === '在售'" link type="warning" size="small" @click="flip(row, '清仓中')">清仓</el-button>
            <el-button v-if="row.productStatus !== '停售'" link type="danger" size="small" @click="flip(row, '停售')">停售</el-button>
            <el-button v-if="row.productStatus !== '在售'" link type="success" size="small" @click="flip(row, '在售')">恢复</el-button>
            <el-button link type="success" size="small" @click="router.push(`/products/${row.id}`)">详情 ▸</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="display: flex; justify-content: flex-end; padding: 10px 14px">
        <el-pagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>
    <p v-if="!props.compact" class="ledger-foot-note">
      — 30天销量 / 动销标签 / 单品体检报告在 M1-6「单品分析」接入销售数据后点亮;当前先把档案与别名地基打牢 —
    </p>

    <ProductFormDialog v-model:visible="formVisible" :product="editing" @saved="load" />
    <AliasManageDialog v-model:visible="aliasVisible" :product="aliasProduct" @changed="loadPendingCount" />
    <AliasPendingDrawer v-model:visible="pendingVisible" @changed="loadPendingCount(); load()" />
  </div>
</template>
