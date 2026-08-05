<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getDocDetail, type DocDetail } from '@/api/doc'
import {
  pageMachines,
  pageOpLogs,
  pageProducts,
  pageSuppliers,
  type OpLog,
} from '@/api/basedata'

/**
 * 通用单据详情抽屉(M1-10 P2-3,七律#3:任何名词都能点进去):
 * 单据号在哪露脸(库存流水/单品进出流水/机器补货史/采购列表),点开都是这一张抽屉。
 * 读现成 GET /v1/docs/{id};展示 头(类型/状态/来源/入账月)+ 明细行 + 确认留痕(op_log)+ 红冲指向。
 * 用法:<DocDetailDrawer v-model="visible" :doc-id="docId" />
 */
const props = defineProps<{ modelValue: boolean; docId: number | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const router = useRouter()
const loading = ref(false)
const detail = ref<DocDetail | null>(null)
const docLogs = ref<OpLog[]>([])

// ---------- 名字映射(商品/机器/供应商,模块级缓存一次) ----------
const productMap = ref<Record<number, string>>({})
const machineMap = ref<Record<number, string>>({})
const supplierMap = ref<Record<number, string>>({})
let mapsLoaded = false
async function loadMaps() {
  if (mapsLoaded) return
  const [p, m, s] = await Promise.all([
    pageProducts({ current: 1, size: 500 }),
    pageMachines({ current: 1, size: 200 }),
    pageSuppliers({ current: 1, size: 200 }),
  ])
  productMap.value = Object.fromEntries(p.records.map((r) => [r.id!, r.productName]))
  machineMap.value = Object.fromEntries(m.records.map((r) => [r.id!, r.machineName]))
  supplierMap.value = Object.fromEntries(s.records.map((r) => [r.id!, r.supplierName]))
  mapsLoaded = true
}

async function load(id: number) {
  loading.value = true
  try {
    const [d, logs] = await Promise.all([
      getDocDetail(id),
      pageOpLogs({ targetType: 'doc_head', targetId: id, size: 10 }),
      loadMaps(),
    ])
    detail.value = d
    docLogs.value = logs.records
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.docId] as const,
  ([show, id]) => {
    if (show && id) load(id)
  },
  { immediate: true },
)

/** 红冲指向:抽屉内原地跳到原单(还是这张抽屉,不跳页,七律#6) */
function openDoc(id: number) {
  load(id)
}

const head = computed(() => detail.value?.head ?? null)

const n = (v: number | string | null | undefined) =>
  v == null ? '—' : Number(v).toLocaleString()
const money = (v: number | string | null | undefined) =>
  v == null ? '—' : `¥${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const dt = (v: string | null | undefined) => (v ? v.replace('T', ' ').slice(0, 16) : '—')

const statusType = (s: string | undefined) =>
  s === '已确认' ? 'success' : s === '已红冲' || s === '已作废' ? 'danger' : s === '草稿' || s === '待确认' ? 'warning' : 'info'
const typeChip = (t: string | undefined) =>
  t === '退库' || t === '红冲' ? 'c-amber' : t === '成本调整' ? 'c-amber' : 'c-blue'
/** 确认留痕(op_log 里的确认/过账动作,带经手人) */
const confirmLogs = computed(() =>
  docLogs.value.filter((l) => l.action.includes('确认') || l.action.includes('过账') || l.action.includes('红冲')),
)
</script>

<template>
  <el-drawer
    :model-value="props.modelValue"
    size="680px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <template #header>
      <span style="font-weight: 700">
        📄 {{ head?.docNo ?? '单据详情' }}
        <el-tag v-if="head" size="small" :type="statusType(head.docStatus)" style="margin-left: 6px">{{ head.docStatus }}</el-tag>
      </span>
    </template>
    <div v-loading="loading">
      <template v-if="head">
        <!-- 单据头 -->
        <div class="doc-kv">
          <span class="mini">类型 / 来源</span>
          <span>
            <span class="chip" :class="typeChip(head.docType)">{{ head.docType }}</span>
            <span class="chip c-gray" style="margin-left: 4px">{{ head.docSource ?? '—' }}</span>
            <el-tag v-if="head.negStockExempt" size="small" type="warning" style="margin-left: 4px">负库存豁免</el-tag>
          </span>
          <span class="mini">业务日期 / 入账月</span>
          <b class="num">{{ head.bizDate }} · {{ head.bookPeriod ?? '—' }}</b>
          <span class="mini">机器</span>
          <span>
            <a v-if="head.machineId" class="doc-link" @click="router.push(`/machines/${head.machineId}`)">
              {{ machineMap[head.machineId] ?? '机器#' + head.machineId }} ↗
            </a>
            <span v-else>—</span>
          </span>
          <span class="mini">供应商</span>
          <span>{{ head.supplierId ? (supplierMap[head.supplierId] ?? '供应商#' + head.supplierId) : '—' }}</span>
          <span class="mini">合计</span>
          <b class="num">{{ n(head.totalQty) }} 件 · {{ money(head.totalAmount) }}</b>
          <template v-if="head.redFlushOf">
            <span class="mini">红冲指向</span>
            <a class="doc-link" @click="openDoc(head.redFlushOf!)">原单 #{{ head.redFlushOf }}(点开看原单)→</a>
          </template>
          <template v-if="head.plLine">
            <span class="mini">利润表行</span>
            <span class="chip c-amber">{{ head.plLine }}</span>
          </template>
          <template v-if="head.remark">
            <span class="mini">备注</span>
            <span>{{ head.remark }}</span>
          </template>
        </div>

        <!-- 明细行 -->
        <h6 class="doc-blk">明细行({{ detail?.items.length ?? 0 }})</h6>
        <el-table :data="detail?.items ?? []" size="small" max-height="320">
          <el-table-column label="商品" min-width="150">
            <template #default="{ row }">
              <a class="doc-link" @click="router.push(`/products/${row.productId}`)">
                {{ productMap[row.productId] ?? 'SKU#' + row.productId }} ↗
              </a>
            </template>
          </el-table-column>
          <el-table-column label="货道" width="60">
            <template #default="{ row }">{{ row.slotNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="数量" align="right" width="70">
            <template #default="{ row }"><b class="num">{{ n(row.qty) }}</b></template>
          </el-table-column>
          <el-table-column label="应收" align="right" width="70">
            <template #default="{ row }">{{ row.expectQty != null ? n(row.expectQty) : '—' }}</template>
          </el-table-column>
          <el-table-column label="单价" align="right" width="80">
            <template #default="{ row }">{{ row.unitPrice != null ? Number(row.unitPrice).toFixed(2) : '—' }}</template>
          </el-table-column>
          <el-table-column label="金额" align="right" width="100">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
          <template #empty><el-empty description="无明细行" :image-size="50" /></template>
        </el-table>

        <!-- 确认留痕 -->
        <h6 class="doc-blk">经手留痕(确认人 · op_log)</h6>
        <div v-if="confirmLogs.length" class="doc-logs">
          <div v-for="l in confirmLogs" :key="l.id" class="doc-log-row">
            <span class="chip c-gray">{{ dt(l.opTime) }}</span>
            <span><b>{{ l.userName }}</b> · {{ l.action }}</span>
          </div>
        </div>
        <p v-else class="mini">
          确认时间:{{ dt(head.confirmAt) }}(导入生成的单据由导入批次统一确认,经手见导入中心)
        </p>
      </template>
      <el-empty v-else-if="!loading" description="单据不存在" :image-size="60" />
    </div>
  </el-drawer>
</template>

<style scoped>
.doc-kv {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 6px 14px;
  font-size: 13px;
  line-height: 1.9;
  margin-top: 0;
}
.doc-link {
  color: var(--green, #2f6e52);
  cursor: pointer;
  font-weight: 600;
}
h6.doc-blk {
  font-size: 12px;
  color: var(--ink2, #6b6455);
  letter-spacing: 2px;
  margin: 16px 0 8px;
  font-weight: 700;
}
.doc-logs {
  font-size: 12.5px;
  line-height: 2.1;
}
.doc-log-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
</style>
