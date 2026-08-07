<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ProductSelect from '@/components/basedata/ProductSelect.vue'
import RedFlushDialog from '@/components/doc/RedFlushDialog.vue'
import CostAdjustDialog from '@/components/doc/CostAdjustDialog.vue'
import DocDetailDrawer from '@/components/doc/DocDetailDrawer.vue'
import { pageSuppliers, type Supplier } from '@/api/basedata'
import {
  cancelPurchaseOrder, closePurchaseOrder, confirmReceipt, createPurchaseOrder,
  createReceipt, createReceiptFromPo, getPurchaseOrderItems, getReceipt,
  listInTransit, listReceipts, pagePurchaseOrders, placePurchaseOrder, priceCheck,
  updateReceipt,
  type InTransitRow, type PoItemRow, type PoVo, type PriceCheckResult,
  type ReceiptDetail, type ReceiptListRow,
} from '@/api/purchase'

/**
 * 采购页(M1-4,对照 mockup p4):
 * 订货单(在途/超期黄灯)→ 收货录入(应收/实收两列差异标注)→ 确认入库(自动过账+回写)。
 * 无订单直接录入为兜底通道;比价提示内嵌在录单行(涨幅>20% 黄灯)。
 */

// ============================== 主数据 ==============================

const supplierMap = ref<Record<number, string>>({})
const suppliers = ref<Supplier[]>([])

async function loadSuppliers() {
  const page = await pageSuppliers({ current: 1, size: 200 })
  suppliers.value = page.records
  supplierMap.value = Object.fromEntries(page.records.map((s) => [s.id!, s.supplierName]))
}

// ============================== 订货单列表 ==============================

const orders = ref<PoVo[]>([])
const orderTotal = ref(0)
const orderQuery = reactive({ current: 1, size: 10, poStatus: '' })
const orderLoading = ref(false)

const ORDER_STATUS = ['', '草稿', '已下单', '部分到货', '已完成', '已取消']

async function loadOrders() {
  orderLoading.value = true
  try {
    const page = await pagePurchaseOrders({
      current: orderQuery.current,
      size: orderQuery.size,
      poStatus: orderQuery.poStatus || undefined,
    })
    orders.value = page.records
    orderTotal.value = page.total
  } finally {
    orderLoading.value = false
  }
}

function orderStatusChip(status?: string) {
  return { 草稿: 'c-gray', 已下单: 'c-blue', 部分到货: 'c-amber', 已完成: 'c-green', 已取消: 'c-gray' }[status || ''] || 'c-gray'
}

// ---------- 通用单据详情抽屉(P2-3,七律#3:入库单号可点) ----------
const docDrawerVisible = ref(false)
const docDrawerId = ref<number | null>(null)
function openDocDrawer(docId: number) {
  docDrawerId.value = docId
  docDrawerVisible.value = true
}

async function doPlace(row: PoVo) {
  await placePurchaseOrder(row.po.id!)
  ElMessage.success(`订货单 ${row.po.poNo} 已下单,开始计入在途`)
  refreshAll()
}

async function doCancel(row: PoVo) {
  await ElMessageBox.confirm(`取消订货单 ${row.po.poNo}?未收过货才可取消。`, '取消订货单', { type: 'warning' })
  await cancelPurchaseOrder(row.po.id!)
  ElMessage.success('已取消')
  refreshAll()
}

async function doClose(row: PoVo) {
  await ElMessageBox.confirm(
    `关闭订货单 ${row.po.poNo} 的剩余在途(${row.inTransitQty} 件)?关闭后不再等这批货,订单转已完成。`,
    '关闭余量', { type: 'warning' },
  )
  await closePurchaseOrder(row.po.id!)
  ElMessage.success('余量已关闭')
  refreshAll()
}

// ---------- 订货单详情 ----------

const poDetailVisible = ref(false)
const poDetailRow = ref<PoVo | null>(null)
const poDetailItems = ref<PoItemRow[]>([])

async function openPoDetail(row: PoVo) {
  poDetailRow.value = row
  poDetailItems.value = await getPurchaseOrderItems(row.po.id!)
  poDetailVisible.value = true
}

// ============================== 新建订货单 ==============================

interface PoFormItem {
  productId: number | null
  qtyOrdered: number | null
  unitPrice: number | null
  hint?: PriceCheckResult | null
}

const poFormVisible = ref(false)
const poForm = reactive({
  supplierId: null as number | null,
  expectDate: '' as string,
  remark: '',
  items: [] as PoFormItem[],
  placeNow: true,
})

function openPoForm() {
  poForm.supplierId = null
  poForm.expectDate = ''
  poForm.remark = ''
  poForm.items = [{ productId: null, qtyOrdered: null, unitPrice: null, hint: null }]
  poForm.placeNow = true
  poFormVisible.value = true
}

function addPoItem() {
  poForm.items.push({ productId: null, qtyOrdered: null, unitPrice: null, hint: null })
}

async function savePoForm() {
  if (!poForm.supplierId) return ElMessage.warning('请选择供应商')
  const items = poForm.items.filter((i) => i.productId && i.qtyOrdered)
  if (!items.length) return ElMessage.warning('至少填一行商品+数量')
  const id = await createPurchaseOrder({
    supplierId: poForm.supplierId,
    expectDate: poForm.expectDate || null,
    remark: poForm.remark || null,
    items: items.map((i) => ({ productId: i.productId!, qtyOrdered: i.qtyOrdered!, unitPrice: i.unitPrice })),
  })
  if (poForm.placeNow) await placePurchaseOrder(id)
  ElMessage.success(poForm.placeNow ? '订货单已下单(计入在途)' : '订货单已存草稿')
  poFormVisible.value = false
  refreshAll()
}

// ============================== 比价提示(内嵌) ==============================

async function checkPrice(item: { productId: number | null; unitPrice: number | null; hint?: PriceCheckResult | null }, supplierId: number | null) {
  if (!item.productId) return
  item.hint = await priceCheck(item.productId, supplierId ?? undefined, item.unitPrice ?? undefined)
}

function hintText(hint?: PriceCheckResult | null): string {
  if (!hint) return ''
  if (hint.lastPrice == null) return '首次采购,无历史价'
  const base = `最近价 ¥${hint.lastPrice}(${hint.compareScope})`
  if (hint.diffPct == null) return base
  const pct = Number(hint.diffPct)
  return `${base} · ${pct > 0 ? '涨' : pct < 0 ? '降' : '持平'} ${Math.abs(pct)}%`
}

// ============================== 收货录入(应收/实收) ==============================

interface ReceiveRow {
  productId: number | null
  productName?: string
  skuCode?: string
  expectQty: number | null
  qty: number | null
  unitPrice: number | null
  poItemId: number | null
  hint?: PriceCheckResult | null
}

const receiveVisible = ref(false)
const receiveSaving = ref(false)
/** 收货中的草稿单(从订货单生成时非空;直录模式确认时才建单) */
const receiveDocId = ref<number | null>(null)
const receiveDocNo = ref('')
const receiveForm = reactive({
  supplierId: null as number | null,
  purchaseOrderId: null as number | null,
  poNo: '',
  bizDate: new Date().toISOString().slice(0, 10),
  remark: '',
  rows: [] as ReceiveRow[],
})

const receiveMode = computed(() => (receiveForm.purchaseOrderId ? '订单收货' : '直接录入'))

/** 从订货单一键生成收货草稿(应收列带出,实收默认=应收) */
async function startReceive(row: PoVo) {
  const detail = await createReceiptFromPo(row.po.id!)
  fillReceiveFromDetail(detail, row.po.poNo || '')
  receiveVisible.value = true
  refreshReceipts()
}

/** 打开已有草稿继续录 */
async function resumeReceive(receipt: ReceiptListRow) {
  const detail = await getReceipt(receipt.id)
  fillReceiveFromDetail(detail, '')
  receiveVisible.value = true
}

function fillReceiveFromDetail(detail: ReceiptDetail, poNo: string) {
  receiveDocId.value = detail.head.id
  receiveDocNo.value = detail.head.docNo
  receiveForm.supplierId = detail.head.supplierId ?? null
  receiveForm.purchaseOrderId = detail.head.purchaseOrderId ?? null
  receiveForm.poNo = poNo
  receiveForm.bizDate = detail.head.bizDate
  receiveForm.remark = detail.head.remark || ''
  receiveForm.rows = detail.items.map((i) => ({
    productId: i.productId,
    productName: i.productName,
    skuCode: i.skuCode,
    expectQty: i.expectQty == null ? null : Number(i.expectQty),
    qty: Number(i.qty),
    unitPrice: Number(i.unitPrice),
    poItemId: i.poItemId ?? null,
    hint: null,
  }))
}

/** 无订单直接录入(兜底) */
function quickReceive() {
  receiveDocId.value = null
  receiveDocNo.value = ''
  receiveForm.supplierId = null
  receiveForm.purchaseOrderId = null
  receiveForm.poNo = ''
  receiveForm.bizDate = new Date().toISOString().slice(0, 10)
  receiveForm.remark = ''
  receiveForm.rows = [{ productId: null, expectQty: null, qty: null, unitPrice: null, poItemId: null, hint: null }]
  receiveVisible.value = true
}

function addReceiveRow() {
  receiveForm.rows.push({ productId: null, expectQty: null, qty: null, unitPrice: null, poItemId: null, hint: null })
}

function diffOf(row: ReceiveRow): number | null {
  if (row.expectQty == null || row.qty == null) return null
  return Number(row.qty) - Number(row.expectQty)
}

const receiveTotal = computed(() =>
  receiveForm.rows.reduce((sum, r) => sum + (Number(r.qty) || 0) * (Number(r.unitPrice) || 0), 0))

function buildReceiptPayload() {
  const rows = receiveForm.rows.filter((r) => r.productId && r.qty && r.unitPrice)
  return {
    supplierId: receiveForm.supplierId!,
    bizDate: receiveForm.bizDate,
    purchaseOrderId: receiveForm.purchaseOrderId,
    remark: receiveForm.remark || null,
    items: rows.map((r) => ({
      productId: r.productId!,
      qty: Number(r.qty),
      expectQty: r.expectQty,
      unitPrice: Number(r.unitPrice),
      poItemId: r.poItemId,
    })),
  }
}

/** 确认入库:草稿保存 → 确认(自动过账库存 + 回写订货单已收量) */
async function saveAndConfirm() {
  if (!receiveForm.supplierId) return ElMessage.warning('请选择供应商')
  const payload = buildReceiptPayload()
  if (!payload.items.length) return ElMessage.warning('至少一行完整的 商品+实收数+单价')
  const diffRows = receiveForm.rows.filter((r) => diffOf(r) != null && diffOf(r) !== 0)
  if (diffRows.length) {
    await ElMessageBox.confirm(
      `有 ${diffRows.length} 行实收≠应收(按实收入账,差异自动标注在单据上),确认继续?`,
      '收货差异确认', { type: 'warning' },
    )
  }
  receiveSaving.value = true
  try {
    let docId = receiveDocId.value
    if (docId) {
      await updateReceipt(docId, payload)
    } else {
      const detail = await createReceipt(payload)
      docId = detail.head.id
    }
    const confirmed = await confirmReceipt(docId!)
    ElMessage.success(`已确认入库 ${confirmed.head.docNo}:库存已过账${receiveForm.purchaseOrderId ? ',订货单已收量已回写' : ''}`)
    receiveVisible.value = false
    refreshAll()
  } finally {
    receiveSaving.value = false
  }
}

/** 只存草稿不确认 */
async function saveDraftOnly() {
  if (!receiveForm.supplierId) return ElMessage.warning('请选择供应商')
  const payload = buildReceiptPayload()
  if (!payload.items.length) return ElMessage.warning('至少一行完整的 商品+实收数+单价')
  if (receiveDocId.value) {
    await updateReceipt(receiveDocId.value, payload)
  } else {
    const detail = await createReceipt(payload)
    receiveDocId.value = detail.head.id
    receiveDocNo.value = detail.head.docNo
  }
  ElMessage.success('草稿已保存,可稍后确认入库')
  receiveVisible.value = false
  refreshReceipts()
}

// ============================== 入库单历史 ==============================

const receipts = ref<ReceiptListRow[]>([])
const receiptDetailVisible = ref(false)
const receiptDetail = ref<ReceiptDetail | null>(null)

async function refreshReceipts() {
  receipts.value = await listReceipts()
}

async function openReceiptDetail(row: ReceiptListRow) {
  receiptDetail.value = await getReceipt(row.id)
  receiptDetailVisible.value = true
}

async function confirmFromList(row: ReceiptListRow) {
  await ElMessageBox.confirm(`确认入库 ${row.docNo}?确认后库存自动过账,单据不可再改(只能红冲)。`, '确认入库', { type: 'warning' })
  await confirmReceipt(row.id)
  ElMessage.success('已确认入库')
  refreshAll()
}

function docStatusChip(status: string) {
  return { 草稿: 'c-gray', 待确认: 'c-blue', 已确认: 'c-green', 待结算: 'c-amber', 已结算: 'c-green', 已完成: 'c-green', 已红冲: 'c-red', 已作废: 'c-gray' }[status] || 'c-gray'
}

// ============================== 红冲 / 成本调整(M1-7) ==============================

const redFlushVisible = ref(false)
const costAdjustVisible = ref(false)
const reverseDocId = ref<number | null>(null)
/** 成本调整对话框的商品名映射(从入库单详情行带出) */
const reverseProductNames = computed<Record<number, string>>(() =>
  Object.fromEntries((receiptDetail.value?.items || [])
    .map((i: any) => [i.productId, `${i.skuCode ?? ''} · ${i.productName ?? i.productId}`])))

function openRedFlush() {
  if (!receiptDetail.value) return
  reverseDocId.value = receiptDetail.value.head.id
  redFlushVisible.value = true
}

function openCostAdjust() {
  if (!receiptDetail.value) return
  reverseDocId.value = receiptDetail.value.head.id
  costAdjustVisible.value = true
}

function afterReverse() {
  receiptDetailVisible.value = false
  refreshAll()
}

// ============================== 在途汇总 ==============================

const inTransitRows = ref<InTransitRow[]>([])

async function refreshInTransit() {
  inTransitRows.value = await listInTransit()
}

function refreshAll() {
  loadOrders()
  refreshReceipts()
  refreshInTransit()
}

onMounted(() => {
  loadSuppliers()
  refreshAll()
})
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / 采购</div>
    <div class="ledger-title">
      <h2>采购 · 订货与入库</h2>
      <span class="sub">在途=Σ(订购−已收);确认入库自动过账仓库账 + 回写订货单,结算付款链 M3 接上</span>
    </div>
    <p class="ledger-note">
      ⚡ <b>任务来源</b>:拿货日(周一)由「AI 补货 → 采购建议」生成订货任务(M2 接入);现在也可手工下订货单,或用<b>无订单直接录入</b>兜底(现状小生意常态)。
    </p>

    <!-- 编号流程条(设计七律#2) -->
    <div class="flow-strip">
      <div class="flow-step on">① 下订货单<span class="mini">已下单=计入在途</span></div>
      <div class="flow-arrow">→</div>
      <div class="flow-step on">② 到货点数<span class="mini">应收/实收两列,差异标注</span></div>
      <div class="flow-arrow">→</div>
      <div class="flow-step on">③ 确认入库<span class="mini">自动过账库存+回写订单</span></div>
      <div class="flow-arrow">→</div>
      <div class="flow-step off">④ 结算付款<span class="mini">M3 开通(应付/凭证/核销)</span></div>
    </div>

    <!-- 订货单列表 -->
    <div class="ledger-card">
      <h3>
        📋 订货单 <span class="hint">超期未到黄灯;在途数是 M2 补货公式的输入</span>
        <span style="margin-left: auto; display: flex; gap: 8px">
          <el-button size="small" @click="quickReceive">⚡ 无订单直接录入库</el-button>
          <el-button type="primary" size="small" @click="openPoForm">＋ 新建订货单</el-button>
        </span>
      </h3>
      <div style="margin-bottom: 10px; display: flex; gap: 6px">
        <span
          v-for="s in ORDER_STATUS" :key="s"
          class="chip filter-chip" :class="[orderQuery.poStatus === s ? 'active' : '', s === '' ? 'c-gray' : orderStatusChip(s)]"
          @click="orderQuery.poStatus = s; orderQuery.current = 1; loadOrders()"
        >{{ s === '' ? '全部' : s }}</span>
      </div>
      <el-table v-loading="orderLoading" :data="orders" size="small">
        <el-table-column label="单号" width="150">
          <template #default="{ row }"><span class="num">{{ row.po.poNo }}</span></template>
        </el-table-column>
        <el-table-column label="供应商" min-width="110">
          <template #default="{ row }">{{ supplierMap[row.po.supplierId] || row.po.supplierId }}</template>
        </el-table-column>
        <el-table-column label="预计到货" width="130">
          <template #default="{ row }">
            <span class="num">{{ row.po.expectDate || '—' }}</span>
            <span v-if="row.overdue" class="chip c-amber" style="margin-left: 4px">⏰ 超期</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="chip" :class="orderStatusChip(row.po.poStatus)">{{ row.po.poStatus }}</span>
          </template>
        </el-table-column>
        <el-table-column label="品项" width="60" align="right">
          <template #default="{ row }"><span class="num">{{ row.itemCount }}</span></template>
        </el-table-column>
        <el-table-column label="订购" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ Number(row.qtyOrdered) }}</span></template>
        </el-table-column>
        <el-table-column label="已收" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ Number(row.qtyReceived) }}</span></template>
        </el-table-column>
        <el-table-column label="在途" width="80" align="right">
          <template #default="{ row }">
            <b class="num" :style="Number(row.inTransitQty) > 0 ? 'color: var(--blue)' : ''">{{ Number(row.inTransitQty) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="预计金额" width="90" align="right">
          <template #default="{ row }"><span class="num">¥{{ Number(row.po.totalAmount).toFixed(2) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openPoDetail(row)">详情</el-button>
            <el-button v-if="row.po.poStatus === '草稿'" link type="primary" size="small" @click="doPlace(row)">下单</el-button>
            <el-button v-if="['已下单', '部分到货'].includes(row.po.poStatus)" link type="success" size="small" @click="startReceive(row)">📥 收货</el-button>
            <el-button v-if="['草稿', '已下单'].includes(row.po.poStatus)" link type="danger" size="small" @click="doCancel(row)">取消</el-button>
            <el-button v-if="row.po.poStatus === '部分到货'" link type="warning" size="small" @click="doClose(row)">关闭余量</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="orderQuery.current" :page-size="orderQuery.size" :total="orderTotal"
        layout="prev, pager, next, total" style="margin-top: 10px; justify-content: flex-end"
        @current-change="loadOrders"
      />
    </div>

    <!-- 在途汇总 -->
    <div class="ledger-card">
      <h3>🚚 在途库存 <span class="hint">在途 = Σ(订购 − 已收),只算「已下单/部分到货」的订货单;M2 补货公式从这取数</span></h3>
      <template v-if="inTransitRows.length">
        <span v-for="row in inTransitRows" :key="row.productId" class="chip c-blue" style="margin: 0 8px 6px 0; font-size: 12px">
          {{ row.productName || row.productId }} <b class="num">×{{ Number(row.inTransitQty) }}</b>
        </span>
      </template>
      <span v-else class="mini">当前无在途货(没有等着到货的订货单)</span>
    </div>

    <!-- 入库单历史 -->
    <div class="ledger-card">
      <h3>📥 采购入库单 <span class="hint">收货单→入库单 = 一张单两个状态;确认后不可改,只能红冲</span></h3>
      <el-table :data="receipts" size="small">
        <el-table-column label="单号" width="150">
          <template #default="{ row }">
            <a class="num name-link" @click="openDocDrawer(row.id)">{{ row.docNo }} ▸</a>
          </template>
        </el-table-column>
        <el-table-column label="日期" width="100">
          <template #default="{ row }"><span class="num">{{ row.bizDate }}</span></template>
        </el-table-column>
        <el-table-column label="供应商" min-width="100">
          <template #default="{ row }">{{ row.supplierName || '—' }}</template>
        </el-table-column>
        <el-table-column label="品项" width="60" align="right">
          <template #default="{ row }"><span class="num">{{ row.itemCount }}</span></template>
        </el-table-column>
        <el-table-column label="数量" width="80" align="right">
          <template #default="{ row }"><span class="num">{{ Number(row.totalQty) }}</span></template>
        </el-table-column>
        <el-table-column label="金额" width="100" align="right">
          <template #default="{ row }"><span class="num">¥{{ Number(row.totalAmount).toFixed(2) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="180">
          <template #default="{ row }">
            <span class="chip" :class="docStatusChip(row.docStatus)">{{ row.docStatus }}</span>
            <span v-if="row.diffCount > 0" class="chip c-amber" style="margin-left: 4px">实收差异 {{ row.diffCount }} 行</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openReceiptDetail(row)">详情</el-button>
            <el-button v-if="row.docStatus === '草稿'" link type="success" size="small" @click="resumeReceive(row)">继续录入</el-button>
            <el-button v-if="['草稿', '待确认'].includes(row.docStatus)" link type="success" size="small" @click="confirmFromList(row)">确认入库</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <p class="ledger-foot-note">— 采购价历史自动从入库单聚合成「价格本」,录单时同品比价、涨幅超 20% 亮黄灯 —</p>

    <!-- 新建订货单 -->
    <el-dialog v-model="poFormVisible" title="➕ 新建订货单(草稿级不进账,下单后开始计在途)" width="760px" :close-on-click-modal="false">
      <div style="display: flex; gap: 14px; margin-bottom: 12px">
        <div style="flex: 1">
          <div class="mini" style="margin-bottom: 4px">供应商</div>
          <el-select v-model="poForm.supplierId" placeholder="选供应商" style="width: 100%">
            <el-option v-for="s in suppliers" :key="s.id" :value="s.id!" :label="s.supplierName" />
          </el-select>
        </div>
        <div>
          <div class="mini" style="margin-bottom: 4px">预计到货日(超期未到会亮黄灯)</div>
          <el-date-picker v-model="poForm.expectDate" type="date" value-format="YYYY-MM-DD" placeholder="选日期" />
        </div>
      </div>
      <table class="entry-table">
        <thead><tr><th style="width: 44%">商品</th><th>订购数量</th><th>预计单价 ¥</th><th style="width: 30%">比价提示</th><th /></tr></thead>
        <tbody>
          <tr v-for="(item, idx) in poForm.items" :key="idx">
            <td><ProductSelect v-model="item.productId" @update:model-value="checkPrice(item, poForm.supplierId)" /></td>
            <td><el-input-number v-model="item.qtyOrdered" :min="1" :controls="false" style="width: 90px" /></td>
            <td><el-input-number v-model="item.unitPrice" :min="0" :precision="2" :controls="false" style="width: 90px" @change="checkPrice(item, poForm.supplierId)" /></td>
            <td>
              <span v-if="item.hint" class="mini" :class="{ 'price-warn': item.hint.warn }">
                {{ item.hint.warn ? '⚠️ ' : '' }}{{ hintText(item.hint) }}
              </span>
            </td>
            <td><el-button link type="danger" @click="poForm.items.splice(idx, 1)">✕</el-button></td>
          </tr>
        </tbody>
      </table>
      <el-button link type="primary" @click="addPoItem">＋ 添加一行</el-button>
      <div style="margin-top: 10px; display: flex; align-items: center; gap: 16px">
        <el-input v-model="poForm.remark" placeholder="备注(可空)" style="flex: 1" />
        <el-checkbox v-model="poForm.placeNow">保存后立即下单(计入在途)</el-checkbox>
      </div>
      <template #footer>
        <el-button @click="poFormVisible = false">取消</el-button>
        <el-button type="primary" @click="savePoForm">保存订货单</el-button>
      </template>
    </el-dialog>

    <!-- 收货录入(应收/实收两列) -->
    <el-dialog v-model="receiveVisible" width="860px" :close-on-click-modal="false">
      <template #header>
        <b>📥 收货录入 · {{ receiveMode }}</b>
        <span class="mini" style="margin-left: 10px">
          {{ receiveDocNo ? `单号 ${receiveDocNo}` : '确认时自动生成单号' }}{{ receiveForm.poNo ? ` · 来自订货单 ${receiveForm.poNo}` : '' }}
        </span>
      </template>
      <div style="display: flex; gap: 14px; margin-bottom: 12px">
        <div style="flex: 1">
          <div class="mini" style="margin-bottom: 4px">供应商</div>
          <el-select v-model="receiveForm.supplierId" :disabled="!!receiveForm.purchaseOrderId" placeholder="选供应商" style="width: 100%">
            <el-option v-for="s in suppliers" :key="s.id" :value="s.id!" :label="s.supplierName" />
          </el-select>
        </div>
        <div>
          <div class="mini" style="margin-bottom: 4px">入库日期</div>
          <el-date-picker v-model="receiveForm.bizDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        </div>
      </div>
      <table class="entry-table">
        <thead>
          <tr>
            <th style="width: 34%">商品</th>
            <th>应收</th><th>实收</th><th>差异</th><th>单价 ¥</th><th>金额 ¥</th>
            <th style="width: 22%">比价提示</th><th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, idx) in receiveForm.rows" :key="idx">
            <td>
              <span v-if="row.poItemId">{{ row.skuCode }} · {{ row.productName }}</span>
              <ProductSelect v-else v-model="row.productId" @update:model-value="checkPrice(row, receiveForm.supplierId)" />
            </td>
            <td><span class="num">{{ row.expectQty ?? '—' }}</span></td>
            <td><el-input-number v-model="row.qty" :min="0.001" :controls="false" style="width: 80px" /></td>
            <td>
              <span v-if="diffOf(row) === null" class="mini">—</span>
              <span v-else-if="diffOf(row) === 0" class="chip c-green">✓ 一致</span>
              <span v-else class="chip c-amber">{{ diffOf(row)! > 0 ? '+' : '' }}{{ diffOf(row) }} 按实收</span>
            </td>
            <td><el-input-number v-model="row.unitPrice" :min="0.0001" :precision="2" :controls="false" style="width: 80px" @change="checkPrice(row, receiveForm.supplierId)" /></td>
            <td><span class="num">{{ ((Number(row.qty) || 0) * (Number(row.unitPrice) || 0)).toFixed(2) }}</span></td>
            <td>
              <span v-if="row.hint" class="mini" :class="{ 'price-warn': row.hint.warn }">
                {{ row.hint.warn ? '⚠️ ' : '' }}{{ hintText(row.hint) }}
              </span>
            </td>
            <td><el-button v-if="!row.poItemId" link type="danger" @click="receiveForm.rows.splice(idx, 1)">✕</el-button></td>
          </tr>
        </tbody>
      </table>
      <el-button v-if="!receiveForm.purchaseOrderId" link type="primary" @click="addReceiveRow">＋ 添加一行</el-button>
      <div style="margin-top: 10px; display: flex; align-items: center; gap: 16px">
        <el-input v-model="receiveForm.remark" placeholder="备注(如:少2瓶已电话供应商)" style="flex: 1" />
        <b class="num" style="color: var(--green)">合计 ¥{{ receiveTotal.toFixed(2) }}</b>
      </div>
      <p class="mini" style="margin-top: 8px">
        💡 实收≠应收会自动标注差异并<b>按实收入账</b>;确认入库后:仓库账 +、订货单已收量回写(全收=已完成/部分=部分到货)、进价变动自动写价格本。
      </p>
      <template #footer>
        <el-button @click="receiveVisible = false">关闭</el-button>
        <el-button @click="saveDraftOnly">仅存草稿</el-button>
        <el-button type="primary" :loading="receiveSaving" @click="saveAndConfirm">✓ 确认入库</el-button>
      </template>
    </el-dialog>

    <!-- 订货单详情 -->
    <el-dialog v-model="poDetailVisible" :title="`订货单 ${poDetailRow?.po.poNo || ''} · ${poDetailRow?.po.poStatus || ''}`" width="640px">
      <p class="mini" style="margin-top: 0">
        供应商:{{ supplierMap[poDetailRow?.po.supplierId || 0] || '—' }} ·
        预计到货:{{ poDetailRow?.po.expectDate || '—' }}
        <span v-if="poDetailRow?.overdue" class="chip c-amber" style="margin-left: 4px">⏰ 超期</span>
        · 在途 <b class="num">{{ Number(poDetailRow?.inTransitQty || 0) }}</b> 件
      </p>
      <el-table :data="poDetailItems" size="small">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">{{ row.skuCode }} · {{ row.productName }}</template>
        </el-table-column>
        <el-table-column label="订购" width="80" align="right">
          <template #default="{ row }"><span class="num">{{ Number(row.qtyOrdered) }}</span></template>
        </el-table-column>
        <el-table-column label="已收" width="80" align="right">
          <template #default="{ row }"><span class="num">{{ Number(row.qtyReceived) }}</span></template>
        </el-table-column>
        <el-table-column label="在途" width="80" align="right">
          <template #default="{ row }"><b class="num">{{ Number(row.qtyOrdered) - Number(row.qtyReceived) }}</b></template>
        </el-table-column>
        <el-table-column label="预计单价" width="90" align="right">
          <template #default="{ row }"><span class="num">¥{{ Number(row.unitPrice).toFixed(2) }}</span></template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 入库单详情(应收/实收) -->
    <el-dialog v-model="receiptDetailVisible" :title="`📄 ${receiptDetail?.head.docNo || ''} · ${receiptDetail?.head.docStatus || ''}`" width="680px">
      <p class="mini" style="margin-top: 0">
        日期:{{ receiptDetail?.head.bizDate }} · 供应商:{{ supplierMap[receiptDetail?.head.supplierId || 0] || '—' }}
        <span v-if="receiptDetail?.head.purchaseOrderId"> · 来自订货单 #{{ receiptDetail?.head.purchaseOrderId }}</span>
        · 合计 <b class="num">¥{{ Number(receiptDetail?.head.totalAmount || 0).toFixed(2) }}</b>
      </p>
      <el-table :data="receiptDetail?.items || []" size="small">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">{{ row.skuCode }} · {{ row.productName }}</template>
        </el-table-column>
        <el-table-column label="应收" width="80" align="right">
          <template #default="{ row }"><span class="num">{{ row.expectQty == null ? '—' : Number(row.expectQty) }}</span></template>
        </el-table-column>
        <el-table-column label="实收" width="90" align="right">
          <template #default="{ row }">
            <span class="num" :style="row.expectQty != null && Number(row.qty) !== Number(row.expectQty) ? 'color: var(--red); font-weight: 700' : ''">
              {{ Number(row.qty) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="单价" width="90" align="right">
          <template #default="{ row }"><span class="num">¥{{ Number(row.unitPrice).toFixed(2) }}</span></template>
        </el-table-column>
        <el-table-column label="金额" width="100" align="right">
          <template #default="{ row }"><span class="num">¥{{ Number(row.amount).toFixed(2) }}</span></template>
        </el-table-column>
      </el-table>
      <p class="mini" style="margin-top: 10px">
        确认后的单据不可改不可删:<b>数量录错→红冲</b>(整单反向,必过影响清单);<b>单价录错→成本调整</b>(不动数量只调金额)。
        结算付款链(应付/凭证/核销)M3 开通。
      </p>
      <template #footer>
        <el-button @click="receiptDetailVisible = false">关闭</el-button>
        <template v-if="receiptDetail?.head.docStatus === '已确认'">
          <el-button type="warning" plain @click="openCostAdjust">🧮 成本调整(单价错)</el-button>
          <el-button type="danger" plain @click="openRedFlush">🔄 红冲(数量错)</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 红冲影响清单确认对话框 / 成本调整对话框(M1-7,components/doc) -->
    <RedFlushDialog v-model="redFlushVisible" :doc-id="reverseDocId" @done="afterReverse" />
    <CostAdjustDialog
      v-model="costAdjustVisible" :doc-id="reverseDocId"
      :product-names="reverseProductNames" @done="afterReverse"
    />

    <!-- 通用单据详情抽屉(P2-3:入库单号点开即达) -->
    <DocDetailDrawer v-model="docDrawerVisible" :doc-id="docDrawerId" />
  </div>
</template>

<style scoped>
.flow-strip {
  display: flex;
  align-items: stretch;
  gap: 8px;
  margin-bottom: 16px;
}

.flow-step {
  flex: 1;
  padding: 9px 13px;
  border-radius: 9px;
  font-size: 12.5px;
  font-weight: 700;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.flow-step .mini {
  font-weight: 400;
}

.flow-step.on {
  background: var(--green);
  color: #fff;
}

.flow-step.on .mini {
  color: #d5e6db;
}

.flow-step.off {
  background: #ece6d8;
  color: var(--ink2);
}

.flow-arrow {
  align-self: center;
  color: var(--ink2);
  font-weight: 700;
}

.entry-table {
  width: 100%;
  border-collapse: collapse;
}

.entry-table th {
  text-align: left;
  font-size: 11.5px;
  color: var(--ink2);
  font-weight: 400;
  padding: 4px 6px;
  border-bottom: 1px solid var(--line);
}

.entry-table td {
  padding: 6px;
  border-bottom: 1px dashed var(--line);
  font-size: 13px;
}

.price-warn {
  color: var(--amber);
  font-weight: 700;
}
</style>
