<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DocDetailDrawer from '@/components/doc/DocDetailDrawer.vue'
import { pageMachines, pageProducts, type Machine } from '@/api/basedata'
import {
  ACCOUNT_ERROR_REASONS, DIFF_REASONS,
  cancelStocktake, confirmStocktake, createStocktake, getLossStats, getStocktake,
  listStocktakes, precheckStocktake, saveStocktakeItems, submitStocktake,
  type LossStatRow, type PrecheckResp, type StocktakeDetail, type StocktakeListRow,
} from '@/api/stocktake'
import {
  dropOfflineTask, enqueueOffline, isNetworkError, onOfflineReplayed,
} from '@/utils/offline-queue'

/**
 * 盘点页(M2-4 桌面版 + M2-5 手机版,对照 mockup p9):
 * ⚡任务来源+编号流程条 → 新建盘点(选范围,系统快照账面)→ 盘点表格(账面带出,
 * 只填差异,差异行高亮+原因必选)→ 提交 → 五步向导(1 系统查账 / 2 归因汇总 /
 * 3 生成盘盈亏单[>¥50 红标老板确认] / 4-5 灰位标里程碑)→ 历史列表 + 损耗小结。
 *
 * M2-5 手机版(≤768px,铁律10):同一份数据两套 DOM——桌面表格不动,移动端换成
 * 一行一 SKU 大卡片流(账面大字 + 差异数字键盘 + 原因大按钮);顶部条码/编号定位框
 * (扫码枪回车 / BarcodeDetector 摄像头),底部固定大提交按钮;
 * 录入实时写 localStorage 草稿,断网提交自动入离线队列(utils/offline-queue)。
 */

// ============================== 机器主数据 ==============================

const machines = ref<Machine[]>([])
async function loadMachines() {
  const page = await pageMachines({ current: 1, size: 100 })
  machines.value = page.records
}

/** 条码 → productId 映射(手机定位框扫条码用;盘点明细本身只带 skuCode) */
const barcodeMap = ref<Record<string, number>>({})
async function loadBarcodeMap() {
  try {
    const page = await pageProducts({ current: 1, size: 500 })
    const map: Record<string, number> = {}
    page.records.forEach((p) => {
      if (p.barcode && p.id != null) map[String(p.barcode)] = p.id
    })
    barcodeMap.value = map
  } catch {
    /* 定位框退化为编号/名称匹配,不阻塞盘点 */
  }
}

// ============================== 盘点列表 ==============================

const rows = ref<StocktakeListRow[]>([])
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    rows.value = await listStocktakes(50)
  } finally {
    loading.value = false
  }
}

const activeRow = computed(() =>
  rows.value.find((r) => r.stStatus === '进行中' || r.stStatus === '待确认') || null)

const statusChip = (s: string) =>
  ({ 进行中: 'c-amber', 待确认: 'c-blue', 已完成: 'c-green', 已作废: 'c-gray' }[s] || 'c-gray')

// ============================== 新建盘点 ==============================

const createForm = ref({ scopeType: '仓库', machineId: null as number | null, sourceTask: '手动' })
const creating = ref(false)

async function doCreate() {
  if (createForm.value.scopeType === '机器' && !createForm.value.machineId) {
    ElMessage.warning('机器盘点请先选择机器')
    return
  }
  creating.value = true
  try {
    const id = await createStocktake({
      scopeType: createForm.value.scopeType,
      machineId: createForm.value.scopeType === '机器' ? createForm.value.machineId : null,
      sourceTask: createForm.value.sourceTask,
    })
    ElMessage.success('盘点单已创建,账面数已快照带出——只填对不上的')
    await loadList()
    await openDetail(id)
  } finally {
    creating.value = false
  }
}

// ============================== 盘点工作区(录实盘) ==============================

interface EditRow {
  productId: number
  productName?: string
  skuCode?: string
  slotNo?: string | null
  bookQty: number
  actual: number
  diffReason: string | null
  offlineExempt: boolean
  diffAmount?: number | null
}

const detail = ref<StocktakeDetail | null>(null)
const editRows = ref<EditRow[]>([])
const saving = ref(false)

async function openDetail(id: number) {
  detail.value = await getStocktake(id)
  editRows.value = detail.value.items.map((i) => ({
    productId: i.productId,
    productName: i.productName,
    skuCode: i.skuCode,
    slotNo: i.slotNo,
    bookQty: Number(i.bookQty),
    actual: Number(i.actualQty),
    diffReason: i.diffReason || null,
    offlineExempt: !!i.offlineExempt,
    diffAmount: i.diffAmount == null ? null : Number(i.diffAmount),
  }))
  restoreDraft()
  precheck.value = null
  if (detail.value.stStatus === '待确认') await loadPrecheck()
}

const diffOf = (r: EditRow) => Number((r.actual - r.bookQty).toFixed(3))
const rowClass = ({ row }: { row: EditRow }) => (diffOf(row) !== 0 ? 'diff-row' : '')
const diffRows = computed(() => editRows.value.filter((r) => diffOf(r) !== 0))
const editable = computed(() => detail.value?.stStatus === '进行中')

/** 一键确认一致:全部复位为 实盘=账面 */
function resetAllMatch() {
  editRows.value.forEach((r) => {
    r.actual = r.bookQty
    r.diffReason = null
    r.offlineExempt = false
  })
}

function validateDiffs(): boolean {
  const bad = diffRows.value.find((r) => !r.diffReason)
  if (bad) {
    ElMessage.warning(`差异行必选原因:${bad.productName || bad.productId}`)
    return false
  }
  return true
}

function rowsPayload() {
  return diffRows.value.map((r) => ({
    productId: r.productId,
    actualQty: r.actual,
    diffReason: r.diffReason,
    offlineExempt: r.offlineExempt,
  }))
}

async function doSave(silent = false) {
  if (!detail.value || !validateDiffs()) return false
  saving.value = true
  try {
    await saveStocktakeItems(detail.value.id, rowsPayload())
    if (!silent) ElMessage.success(`已保存 ${diffRows.value.length} 行差异(其余视同相符)`)
    return true
  } catch (e) {
    // 断网/超时:入离线队列,恢复后自动重传(黄条见 App.vue)
    if (isNetworkError(e)) {
      enqueueOffline(
        'stocktake-save',
        `盘点差异保存 ${detail.value.stNo}(${diffRows.value.length} 行)`,
        { id: detail.value.id, rows: rowsPayload() },
        String(detail.value.id),
      )
      ElMessage.warning('网络不通:差异已离线暂存,恢复后自动重传(本机草稿也在)')
      return false
    }
    throw e
  } finally {
    saving.value = false
  }
}

async function doSubmit() {
  if (!detail.value || !validateDiffs()) return
  const id = detail.value.id
  saving.value = true
  try {
    await saveStocktakeItems(id, rowsPayload())
    await submitStocktake(id)
  } catch (e) {
    if (isNetworkError(e)) {
      // 提交任务自带最后一版差异,同单的暂存"保存"任务可清掉(不重复打)
      dropOfflineTask('stocktake-save', String(id))
      enqueueOffline(
        'stocktake-submit',
        `盘点提交 ${detail.value.stNo}(${diffRows.value.length} 行差异)`,
        { id, rows: rowsPayload() },
        String(id),
      )
      ElMessage.warning('网络不通:提交已离线暂存,恢复后自动重传')
      return
    }
    throw e
  } finally {
    saving.value = false
  }
  clearDraft(id)
  ElMessage.success('已提交,进入五步向导——先看第 1 步系统查账')
  await loadList()
  await openDetail(id)
}

async function doCancel() {
  if (!detail.value) return
  await ElMessageBox.confirm(`作废盘点单 ${detail.value.stNo}?未过账,可放心作废。`, '作废盘点', { type: 'warning' })
  await cancelStocktake(detail.value.id)
  clearDraft(detail.value.id)
  ElMessage.success('已作废')
  detail.value = null
  await loadList()
}

// ============================== 手机版:草稿 + 定位 + 扫码(M2-5) ==============================

/** 实盘录入实时写本机草稿(键=盘点单 id),刷新/闪退/断网都不丢 */
const DRAFT_PREFIX = 'vend_st_draft_'
const draftKey = (id: number) => DRAFT_PREFIX + id
let restoringDraft = false

function saveDraft() {
  if (!detail.value || !editable.value || restoringDraft) return
  const rows = editRows.value
    .filter((r) => diffOf(r) !== 0 || r.diffReason || r.offlineExempt)
    .map((r) => ({
      productId: r.productId,
      actual: r.actual,
      diffReason: r.diffReason,
      offlineExempt: r.offlineExempt,
    }))
  if (!rows.length) {
    localStorage.removeItem(draftKey(detail.value.id))
    return
  }
  localStorage.setItem(draftKey(detail.value.id), JSON.stringify({ savedAt: new Date().toISOString(), rows }))
}

function restoreDraft() {
  if (!detail.value || detail.value.stStatus !== '进行中') return
  const raw = localStorage.getItem(draftKey(detail.value.id))
  if (!raw) return
  try {
    const draft = JSON.parse(raw) as { rows: { productId: number; actual: number; diffReason: string | null; offlineExempt: boolean }[] }
    let hit = 0
    restoringDraft = true
    draft.rows.forEach((d) => {
      const row = editRows.value.find((r) => r.productId === d.productId)
      if (!row) return
      row.actual = Number(d.actual)
      row.diffReason = d.diffReason
      row.offlineExempt = !!d.offlineExempt
      hit++
    })
    restoringDraft = false
    if (hit) ElMessage.info(`已恢复本机离线草稿 ${hit} 行(上次没提交完的接着盘)`)
  } catch {
    restoringDraft = false
  }
}

function clearDraft(id: number) {
  localStorage.removeItem(draftKey(id))
}

watch(editRows, () => saveDraft(), { deep: true })

/** 定位框:扫码枪回车 / 手输编号·货道·名称 → 滚动定位到该 SKU 卡并聚焦实盘输入 */
const locateQuery = ref('')
const locatedId = ref<number | null>(null)

function locateSku() {
  const q = locateQuery.value.trim()
  if (!q) return
  const ql = q.toLowerCase()
  const byBarcode = barcodeMap.value[q]
  const row = editRows.value.find((r) =>
    (byBarcode != null && r.productId === byBarcode)
    || (r.skuCode || '').toLowerCase() === ql
    || (r.slotNo || '').toLowerCase() === ql
    || (r.productName || '').includes(q))
  if (!row) {
    ElMessage.warning(`本单 ${editRows.value.length} 个 SKU 里没找到「${q}」`)
    return
  }
  locateQuery.value = '' // 清空,扫码枪连续扫下一件
  locatedId.value = row.productId
  nextTick(() => {
    const el = document.getElementById('st-card-' + row.productId)
    // 直接跳(不用 smooth):扫码枪连续扫时快;动画也会干扰自动化点击命中
    el?.scrollIntoView({ block: 'center' })
    const input = el?.querySelector<HTMLInputElement>('input.big-num-input')
    input?.focus()
    input?.select()
  })
}

/** 摄像头扫码:原生 BarcodeDetector 可用才出 📷;不可用就藏(降级=定位框手输/扫码枪) */
const scanSupported = typeof window !== 'undefined' && 'BarcodeDetector' in window
const scanning = ref(false)
const scanVideo = ref<HTMLVideoElement | null>(null)
let scanStream: MediaStream | null = null
let scanTimer: number | null = null

async function startScan() {
  try {
    scanStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
  } catch {
    ElMessage.warning('打不开摄像头(未授权?)——直接在定位框输编号也一样')
    return
  }
  scanning.value = true
  await nextTick()
  if (scanVideo.value) scanVideo.value.srcObject = scanStream
  const Detector = (window as any).BarcodeDetector
  const detector = new Detector({ formats: ['ean_13', 'ean_8', 'code_128', 'upc_a', 'qr_code'] })
  scanTimer = window.setInterval(async () => {
    if (!scanVideo.value || scanVideo.value.readyState < 2) return
    try {
      const codes = await detector.detect(scanVideo.value)
      if (codes.length) {
        locateQuery.value = codes[0].rawValue
        stopScan()
        locateSku()
      }
    } catch {
      /* 单帧识别失败继续轮询 */
    }
  }, 300)
}

function stopScan() {
  if (scanTimer != null) {
    clearInterval(scanTimer)
    scanTimer = null
  }
  scanStream?.getTracks().forEach((t) => t.stop())
  scanStream = null
  scanning.value = false
}

/** 手机大数字输入:inputmode=numeric 拉起数字键盘,过滤非数字字符 */
function onActualInput(row: EditRow, e: Event) {
  const el = e.target as HTMLInputElement
  const clean = el.value.replace(/[^\d.]/g, '')
  if (clean !== el.value) el.value = clean
  row.actual = clean === '' ? 0 : Number(clean)
}

// ============================== 五步向导 ==============================

const precheck = ref<PrecheckResp | null>(null)
async function loadPrecheck() {
  if (!detail.value) return
  precheck.value = await precheckStocktake(detail.value.id)
}

/** 第 2 步归因汇总:原因 → {行数, 数量} */
const reasonSummary = computed(() => {
  const map: Record<string, { count: number; qty: number }> = {}
  diffRows.value.forEach((r) => {
    const key = r.diffReason || '未归因'
    map[key] = map[key] || { count: 0, qty: 0 }
    map[key].count++
    map[key].qty += Math.abs(diffOf(r))
  })
  return Object.entries(map).map(([reason, v]) => ({ reason, ...v }))
})

/** >¥50 红标行(按后端已算 diffAmount;未算时按无) */
const bigDiffRows = computed(() =>
  diffRows.value.filter((r) => r.diffAmount != null && Math.abs(r.diffAmount) > 50))
/** 前端预估红标(提交前 diffAmount 还没算,用差异件数×无价兜底不了——只在待确认后端算完后显示) */

const confirming = ref(false)
const asBoss = ref(false)

async function doConfirm() {
  if (!detail.value) return
  confirming.value = true
  try {
    const resp = await confirmStocktake(detail.value.id, { asBoss: asBoss.value })
    const parts: string[] = []
    if (resp.gainDocId) parts.push(`盘盈入库单 #${resp.gainDocId}`)
    if (resp.returnDocId) parts.push(`配对退库单 #${resp.returnDocId}`)
    if (resp.lossDocId) parts.push(`盘亏出库单 #${resp.lossDocId}`)
    if (resp.anchorCount) parts.push(`机器锚点 ${resp.anchorCount} 条(推算账已校准)`)
    ElMessage.success(parts.length ? `盘点完成:${parts.join(' / ')}` : '盘点完成:账实全符,无需生成单据')
    await loadList()
    await Promise.all([openDetail(detail.value.id), loadLossStats()])
  } finally {
    confirming.value = false
  }
}

// ============================== 单据抽屉(七律#3:单号可点) ==============================

const docDrawerVisible = ref(false)
const docDrawerId = ref<number | null>(null)
function openDoc(docId?: number | null) {
  if (!docId) return
  docDrawerId.value = docId
  docDrawerVisible.value = true
}

// ============================== 月度财务盘点 SOP(mockup p9,静态 SOP 说明;M3/M4 环节灰位) ==============================

const sopRows = [
  { step: 'P 准备', when: '前一天', who: '系统', what: '自动生成任务包:仓库盘点 ×1 + 资金核对 + 应付核对', support: '账面数自动快照(任务日历派单)', milestone: '' },
  { step: 'D1 货盘', when: '上午', who: '补货员', what: '仓库大盘(机器不重复盘——已由每周轮盘覆盖,只汇总本月轮盘结果)', support: '手机录入,只填差异行', milestone: '' },
  { step: 'D2 钱盘', when: '上午', who: '老板', what: '核微信/现金实际余额 · 核平台上月到账 · 发对账单给供应商确认', support: '三个核对页,填实际数', milestone: '里程碑 3' },
  { step: 'C 检查', when: '下午', who: '系统+老板', what: '自动出《月度盘点报告》:货差/钱差/资产快照/环比', support: '差异超阈值红灯 · 报告归档', milestone: '里程碑 3' },
  { step: 'A 改进', when: '下午', who: '老板', what: '对着报告定改进任务(淘汰/调参/索赔),AI 起草建议', support: '任务带验证指标,下月自动回查', milestone: '里程碑 4' },
]

// ============================== 损耗小结 ==============================

const lossStats = ref<LossStatRow[]>([])
async function loadLossStats() {
  lossStats.value = await getLossStats(6)
}
const thisMonth = new Date().toISOString().slice(0, 7)
const monthLoss = computed(() => lossStats.value.filter((r) => r.month === thisMonth))
const lossCard = (reasons: string[]) => {
  const hit = monthLoss.value.filter((r) => reasons.includes(r.reason))
  return {
    amount: hit.reduce((s, r) => s + Number(r.amount || 0), 0),
    qty: hit.reduce((s, r) => s + Number(r.qty || 0), 0),
  }
}

/** 离线队列重放成功 → 刷新列表/当前单(黄条清零由 App.vue 全局条负责) */
const offReplay = onOfflineReplayed((task) => {
  if (!task.type.startsWith('stocktake')) return
  const payload = task.payload as { id: number }
  if (task.type === 'stocktake-submit') clearDraft(payload.id)
  loadList()
  if (detail.value?.id === payload.id) openDetail(payload.id)
})

onMounted(() => {
  loadMachines()
  loadBarcodeMap()
  loadList().then(() => {
    if (activeRow.value) openDetail(activeRow.value.id)
  })
  loadLossStats()
})

onUnmounted(() => {
  stopScan()
  offReplay()
})
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / 日常台账 / 盘点</div>
    <div class="ledger-title">
      <h2>盘点</h2>
      <span class="sub">任务系统自动生成 · 流程一步步领着走 · 少货按五步查到底</span>
    </div>

    <!-- ⚡ 任务来源 + 编号流程条(设计思维律②:领着人干活) -->
    <el-alert type="info" :closable="false" class="mb-12px">
      <template #title>
        ⚡ 任务来源:每周轮盘(补货顺手盘)+ 每月 1 日仓库大盘(月度 SOP 任务包,任务日历 M2-6/里程碑4 自动派)·
        流程:<b>① 建单快照账面 → ② 现场实盘只录差异 → ③ 提交 → ④ 五步向导归因处理 → ⑤ 改进+下轮验证</b>
      </template>
    </el-alert>

    <!-- 编号流程条(当前步高亮) -->
    <div class="flow-bar" data-block="flow-bar">
      <div :class="['flow-step', !detail ? 'cur' : 'done']">① 系统生成盘点单<span class="mini">选范围·账面数快照</span></div>
      <div :class="['flow-step', detail && editable ? 'cur' : detail ? 'done' : '']">② 现场录实盘<span class="mini">账面带出,只填对不上的</span></div>
      <div :class="['flow-step', detail?.stStatus === '待确认' ? 'cur' : detail?.stStatus === '已完成' ? 'done' : '']">③ 提交差异<span class="mini">差异行必选原因</span></div>
      <div :class="['flow-step', detail?.stStatus === '待确认' ? 'cur' : detail?.stStatus === '已完成' ? 'done' : '']">④ 五步向导<span class="mini">查账→归因→生成盘盈亏单</span></div>
      <div class="flow-step dim">⑤ 改进+下轮验证<span class="mini">PDCA 自动回查(里程碑4)</span></div>
    </div>

    <!-- 新建盘点 -->
    <div class="ledger-card" data-block="create">
      <h3>🆕 新建盘点 <span class="hint">选范围 → 系统自动快照账面数(仓库=Σ流水,机器=推算值)</span></h3>
      <div class="flex items-center gap-12px flex-wrap">
        <el-radio-group v-model="createForm.scopeType">
          <el-radio-button value="仓库">🏬 仓库盘点</el-radio-button>
          <el-radio-button value="机器">🥤 机器盘点</el-radio-button>
        </el-radio-group>
        <el-select
          v-if="createForm.scopeType === '机器'"
          v-model="createForm.machineId"
          placeholder="选机器"
          style="width: 180px"
        >
          <el-option v-for="m in machines" :key="m.id" :label="m.machineName" :value="m.id!" />
        </el-select>
        <el-select v-model="createForm.sourceTask" style="width: 160px">
          <el-option label="手动" value="手动" />
          <el-option label="补货顺手盘" value="补货顺手盘" />
          <el-option label="月度SOP任务包" value="月度SOP任务包" />
        </el-select>
        <el-button type="primary" :loading="creating" @click="doCreate">建单并快照账面</el-button>
        <span class="mini">同范围只允许一张进行中盘点;机器账面为负=缺锚点,盘完自动校准(M1-9 红灯自愈)</span>
      </div>
    </div>

    <!-- 进行中盘点工作区 -->
    <div v-if="detail" class="ledger-card" data-block="worksheet">
      <h3>
        📱 {{ detail.stStatus === '进行中' ? '进行中' : detail.stStatus }}:{{ detail.scopeType }}盘点
        <template v-if="detail.machineName">· {{ detail.machineName }}</template>
        <span class="chip" :class="statusChip(detail.stStatus)">{{ detail.stStatus }}</span>
        <span class="hint">{{ detail.stNo }} · 快照 {{ detail.snapshotTime }} · {{ detail.sourceTask }}</span>
      </h3>

      <!-- ============ 手机版卡片流(M2-5,≤768px 显示;桌面表格原样) ============ -->
      <div class="m-only st-mobile" data-block="mobile-worksheet">
        <div v-if="editable" class="locate-bar">
          <input
            v-model="locateQuery"
            class="locate-input"
            type="text"
            enterkeyhint="search"
            placeholder="🔍 扫条码 / 输编号·货道·品名,回车定位"
            @keyup.enter="locateSku"
          />
          <button v-if="scanSupported" class="scan-btn" @click="startScan">📷</button>
          <button class="locate-go" @click="locateSku">定位</button>
        </div>
        <div class="m-tip">💡 账面已带出,<b>只录对不上的</b>——一致的卡不用碰,提交时视同相符</div>
        <div
          v-for="row in editRows"
          :id="'st-card-' + row.productId"
          :key="row.productId"
          class="st-card"
          :class="{ located: locatedId === row.productId, changed: diffOf(row) !== 0 }"
        >
          <div class="st-card-head">
            <b class="st-name">{{ row.productName || row.productId }}</b>
            <span v-if="row.slotNo" class="chip c-blue">{{ row.slotNo }} 道</span>
            <span class="chip st-diff-chip" :class="diffOf(row) === 0 ? 'c-green' : diffOf(row) < 0 ? 'c-red' : 'c-amber'">
              {{ diffOf(row) === 0 ? '一致 ✓' : '差 ' + (diffOf(row) > 0 ? '+' : '') + diffOf(row) }}
            </span>
          </div>
          <div class="st-card-nums">
            <div class="st-book">
              <span class="lab">账面</span>
              <b class="num book-big" :class="row.bookQty < 0 ? 'neg-book' : ''">{{ row.bookQty }}</b>
              <span v-if="row.bookQty < 0" class="mini">缺锚点</span>
            </div>
            <div class="st-actual">
              <span class="lab">实盘(不对才改)</span>
              <input
                class="big-num-input"
                type="text"
                inputmode="numeric"
                :disabled="!editable"
                :value="row.actual"
                @input="onActualInput(row, $event)"
              />
            </div>
          </div>
          <div v-if="diffOf(row) !== 0" class="st-reasons">
            <button
              v-for="r in DIFF_REASONS"
              :key="r"
              class="reason-btn"
              :class="{ sel: row.diffReason === r }"
              :disabled="!editable"
              @click="row.diffReason = row.diffReason === r ? null : r"
            >{{ r }}</button>
            <button
              v-if="detail.scopeType === '机器'"
              class="reason-btn exempt"
              :class="{ sel: row.offlineExempt }"
              :disabled="!editable"
              @click="row.offlineExempt = !row.offlineExempt"
            >🏷 线下豁免</button>
          </div>
          <div v-if="diffOf(row) !== 0 && !row.diffReason" class="mini need-reason">⚠ 差异行必选原因才能提交</div>
        </div>

        <!-- 摄像头扫码浮层(BarcodeDetector 不可用时根本没有 📷 入口) -->
        <div v-if="scanning" class="scan-overlay" @click.self="stopScan">
          <video ref="scanVideo" autoplay playsinline class="scan-video"></video>
          <div class="scan-hint">对准商品条码…识别后自动定位</div>
          <button class="reason-btn scan-close" @click="stopScan">✕ 关闭</button>
        </div>
      </div>

      <div class="st-desktop">
      <el-table :data="editRows" size="small" :row-class-name="rowClass">
        <el-table-column label="商品" min-width="170">
          <template #default="{ row }">
            <span>{{ row.productName || row.productId }}</span>
            <span v-if="row.slotNo" class="mini"> · {{ row.slotNo }} 道</span>
          </template>
        </el-table-column>
        <el-table-column label="账面" width="90" align="right">
          <template #default="{ row }">
            <span class="num" :class="row.bookQty < 0 ? 'neg-book' : ''">{{ row.bookQty }}</span>
            <span v-if="row.bookQty < 0" class="mini"> 缺锚点</span>
          </template>
        </el-table-column>
        <el-table-column label="实盘(只填对不上的)" width="150" align="center">
          <template #default="{ row }">
            <el-input-number
              v-model="row.actual"
              :min="0"
              :disabled="!editable"
              size="small"
              controls-position="right"
              style="width: 110px"
            />
          </template>
        </el-table-column>
        <el-table-column label="差异" width="80" align="right">
          <template #default="{ row }">
            <b class="num" :class="diffOf(row) < 0 ? 'diff-neg' : diffOf(row) > 0 ? 'diff-pos' : 'diff-zero'">
              {{ diffOf(row) > 0 ? '+' : '' }}{{ diffOf(row) }}
            </b>
          </template>
        </el-table-column>
        <el-table-column label="原因(差异必选)" width="160">
          <template #default="{ row }">
            <el-select
              v-if="diffOf(row) !== 0"
              v-model="row.diffReason"
              :disabled="!editable"
              placeholder="必选"
              size="small"
            >
              <el-option v-for="r in DIFF_REASONS" :key="r" :value="r" :label="r + (r === '原因不明' ? '(挂起观察)' : '')" />
            </el-select>
            <span v-else class="mini">—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="detail.scopeType === '机器'" label="线下豁免" width="90" align="center">
          <template #default="{ row }">
            <el-checkbox v-if="diffOf(row) !== 0" v-model="row.offlineExempt" :disabled="!editable" />
            <span v-else class="mini">—</span>
          </template>
        </el-table-column>
        <el-table-column label="差异金额" width="100" align="right">
          <template #default="{ row }">
            <span
              v-if="row.diffAmount != null"
              class="num"
              :class="Math.abs(row.diffAmount) > 50 ? 'big-amount' : ''"
            >{{ row.diffAmount > 0 ? '+' : '' }}¥{{ row.diffAmount }}</span>
            <span v-else class="mini">确认时按加权成本算</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="flex items-center gap-10px mt-12px flex-wrap" v-if="editable">
        <el-button @click="resetAllMatch">✓ 一键确认一致(全部实盘=账面)</el-button>
        <el-button :loading="saving" @click="doSave()">保存差异</el-button>
        <el-button type="primary" :loading="saving" @click="doSubmit">
          提交盘点({{ diffRows.length }} 行差异,其余视同相符)
        </el-button>
        <el-button type="danger" plain @click="doCancel">作废</el-button>
        <span class="mini">提交后进入五步向导:先由系统查账,再归因,再生成盘盈亏单</span>
      </div>
      </div><!-- /.st-desktop -->

      <!-- 手机版底部固定大提交条(M2-5) -->
      <div v-if="editable" class="m-only st-submit-bar">
        <button class="bar-btn ghost" @click="resetAllMatch">✓ 全部一致</button>
        <button class="bar-btn primary" :disabled="saving" @click="doSubmit">
          提交盘点({{ diffRows.length }} 行差异)
        </button>
      </div>

      <!-- 五步向导(mockup p9 五连横条;M2 开放 1-3 步,4/5 灰位) -->
      <div v-if="detail.stStatus !== '进行中'" class="wizard" data-block="wizard">
        <h3 style="margin-top: 18px">🧭 盘亏处理五步向导
          <span class="hint">先查账,再定责,后改进——不是一句"报损"就完了(调研报告 §8.1)</span>
        </h3>
        <div class="wizard-bar">
          <!-- 第1步 · 先查账(系统自动) -->
          <div class="wz-step wz-active">
            <div class="wz-no">第 1 步 · 先查账 {{ precheck ? '✓' : '' }}</div>
            <div class="wz-title">是不是账记错了?</div>
            <div class="wz-body">
              <template v-if="precheck">
                <div v-for="(h, i) in precheck.hints" :key="i" class="hint-line">{{ h }}</div>
                <div class="mini">
                  近7天:导入 {{ precheck.imports7d.length }} 批 · 相关单据 {{ precheck.docs7d.length }} 张
                  <template v-if="precheck.offlineSales.length">· 线下补录 {{ precheck.offlineSales.length }} 个SKU</template>
                </div>
              </template>
              <el-button v-else size="small" @click="loadPrecheck">查一下(导入/单据/线下)</el-button>
            </div>
          </div>
          <!-- 第2步 · 归因 -->
          <div class="wz-step wz-active">
            <div class="wz-no">第 2 步 · 归因</div>
            <div class="wz-title">为什么少?</div>
            <div class="wz-body">
              <div v-for="s in reasonSummary" :key="s.reason" class="mini">
                <span class="chip" :class="ACCOUNT_ERROR_REASONS.includes(s.reason) ? 'c-blue' : 'c-amber'">{{ s.reason }}</span>
                {{ s.count }} 行 / {{ s.qty }} 件
              </div>
              <div v-if="!reasonSummary.length" class="mini">无差异行,账实全符 ✓</div>
              <div class="mini" style="margin-top: 4px">吞货可联查该货道出货失败记录:里程碑3开放</div>
            </div>
          </div>
          <!-- 第3步 · 处理 -->
          <div class="wz-step wz-now">
            <div class="wz-no">第 3 步 · 处理 ← 现在</div>
            <div class="wz-title">生成盘盈亏单</div>
            <div class="wz-body">
              <template v-if="detail.stStatus === '待确认'">
                <div v-if="bigDiffRows.length" class="mini big-amount">
                  ⚠ {{ bigDiffRows.length }} 行单笔差异成本额>¥50,需老板确认
                </div>
                <div class="mini">
                  仓库差异→盘盈入库/盘亏出库;机器真损耗→退库+盘亏配对(仓库净不变);
                  机器确认必落快照锚点(推算账校准)
                </div>
                <el-checkbox v-model="asBoss" size="small">我是老板(>¥50 差异确认)</el-checkbox>
                <el-button type="primary" size="small" :loading="confirming" @click="doConfirm">
                  ✓ 确认盘点(自动生成单据并过账)
                </el-button>
              </template>
              <template v-else-if="detail.stStatus === '已完成'">
                <div class="mini">
                  <template v-if="detail.gainDocId">盘盈入库单 <a class="name-link" @click="openDoc(detail.gainDocId)">#{{ detail.gainDocId }}</a> · </template>
                  <template v-if="detail.lossDocId">盘亏出库单 <a class="name-link" @click="openDoc(detail.lossDocId)">#{{ detail.lossDocId }}</a></template>
                  <template v-if="!detail.gainDocId && !detail.lossDocId">账实全符,未生成单据 ✓</template>
                </div>
                <div class="mini">已过账,库存已修正 ✓</div>
              </template>
            </div>
          </div>
          <!-- 第4步 · 改进(灰位) -->
          <div class="wz-step wz-dim">
            <div class="wz-no">第 4 步 · 改进</div>
            <div class="wz-title">登记改进任务</div>
            <div class="wz-body mini">吞货多→报修货道 · 过期多→降机内上限 · AI 起草一键采纳<br /><span class="chip c-gray">里程碑 4 开放</span></div>
          </div>
          <!-- 第5步 · 下轮验证(灰位) -->
          <div class="wz-step wz-dim">
            <div class="wz-no">第 5 步 · 下轮验证</div>
            <div class="wz-title">下次盘点自动回查</div>
            <div class="wz-body mini">该原因损耗环比↓?达标关闭·不达标升级<br /><span class="chip c-gray">里程碑 4 开放</span>·索赔挂应收:<span class="chip c-gray">里程碑 3</span></div>
          </div>
        </div>
      </div>
    </div>

    <!-- 历史盘点 + 损耗小结 -->
    <div class="grid grid-cols-2 gap-16px" data-block="history-loss">
      <div class="ledger-card">
        <h3>盘点历史 <span class="hint">点行查看明细</span></h3>
        <el-table :data="rows" size="small" v-loading="loading" @row-click="(r: StocktakeListRow) => openDetail(r.id)">
          <el-table-column prop="stNo" label="单号" width="150">
            <template #default="{ row }"><span class="num name-link">{{ row.stNo }}</span></template>
          </el-table-column>
          <el-table-column label="范围" min-width="110">
            <template #default="{ row }">{{ row.scopeType }}{{ row.machineName ? ' · ' + row.machineName : '' }}</template>
          </el-table-column>
          <el-table-column label="差异行" width="70" align="right">
            <template #default="{ row }"><span class="num">{{ row.diffCount }}</span></template>
          </el-table-column>
          <el-table-column label="差异金额" width="90" align="right">
            <template #default="{ row }">
              <span class="num" :class="Number(row.diffAmount) < 0 ? 'diff-neg' : ''">
                {{ row.diffAmount == null ? '—' : '¥' + row.diffAmount }}
              </span>
            </template>
          </el-table-column>
          <!-- fixed=right:半宽卡里窄视口不裁切状态 chip(M2-3 坑7 同尺) -->
          <el-table-column label="状态" width="120" fixed="right">
            <template #default="{ row }">
              <span class="chip" :class="statusChip(row.stStatus)">{{ row.stStatus }}{{ row.stStatus === '已完成' && !row.diffCount ? ' ✓ 账实全符' : '' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="ledger-card" data-block="loss-summary">
        <h3>本月损耗账 <span class="hint">钱去哪了 · 反哺补货参数(豁免行不算损耗)</span></h3>
        <div class="loss-cards">
          <div class="loss-card amber">
            <div class="num loss-amount">¥{{ lossCard(['吞货掉货']).amount.toFixed(2) }}</div>
            <div class="mini">吞货/掉货({{ lossCard(['吞货掉货']).qty }} 件)</div>
          </div>
          <div class="loss-card red">
            <div class="num loss-amount">¥{{ lossCard(['过期报损']).amount.toFixed(2) }}</div>
            <div class="mini">过期报损({{ lossCard(['过期报损']).qty }} 件)</div>
          </div>
          <div class="loss-card green">
            <div class="num loss-amount">¥{{ lossCard(['被盗']).amount.toFixed(2) }}</div>
            <div class="mini">被盗/损坏({{ lossCard(['被盗']).qty }} 件)</div>
          </div>
        </div>
        <h4 class="mini" style="margin: 12px 0 6px">近 6 个月损耗明细(原因 × 月份)</h4>
        <el-table :data="lossStats" size="small">
          <el-table-column prop="month" label="月份" width="90" />
          <el-table-column prop="reason" label="原因" min-width="100" />
          <el-table-column label="件数" width="70" align="right">
            <template #default="{ row }"><span class="num">{{ row.qty }}</span></template>
          </el-table-column>
          <el-table-column label="成本额" width="90" align="right">
            <template #default="{ row }"><span class="num diff-neg">¥{{ Number(row.amount).toFixed(2) }}</span></template>
          </el-table-column>
          <template #empty>
            <el-empty description="本期无损耗——账实全符 ✓" :image-size="50" />
          </template>
        </el-table>
        <p class="mini" style="margin-top: 8px">
          🤖 损耗按原因反哺补货参数(过期多→降机内上限)· AI 起草改进建议:里程碑 4 开放
        </p>
      </div>
    </div>

    <!-- 📅 月度财务盘点 SOP(mockup p9:货和钱一起盘;钱盘/报告/改进属里程碑3/4,画灰位) -->
    <div class="ledger-card" data-block="monthly-sop">
      <h3>📅 月度财务盘点 SOP <span class="hint">每月 1 日 · 半天 · 货和钱一起盘(任务包由任务日历自动生成)</span></h3>
      <el-table :data="sopRows" size="small">
        <el-table-column label="环节" width="110">
          <template #default="{ row }">
            <b>{{ row.step }}</b><div class="mini">{{ row.when }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="who" label="谁" width="90" />
        <el-table-column prop="what" label="干什么" min-width="230" />
        <el-table-column label="系统支持" min-width="150">
          <template #default="{ row }"><span class="mini">{{ row.support }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="130" fixed="right">
          <template #default="{ row }">
            <span class="chip" :class="row.milestone ? 'c-gray' : 'c-green'">
              {{ row.milestone ? row.milestone + ' 开放' : '本期已可用' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <p class="mini" style="margin-top: 8px">
        💡 机器不重复盘——已由每周轮盘覆盖,月盘只做<b>仓库大盘</b> + 汇总本月轮盘结果;钱盘(资金/应付核对)与《月度盘点报告》随钱账、PDCA 里程碑逐月点亮。
      </p>
    </div>

    <p class="ledger-foot-note">— 老台账的《月末盘点表》从"建好了没填过"变成固定节奏:补货顺手盘 + 每月 1 日半天大盘 —</p>

    <DocDetailDrawer v-model="docDrawerVisible" :doc-id="docDrawerId" />
  </div>
</template>

<style scoped>
/* 编号流程条(mockup p9 五连横条) */
.flow-bar {
  display: flex;
  gap: 0;
  margin-bottom: 14px;
  font-size: 12px;
  text-align: center;
}
.flow-step {
  flex: 1;
  padding: 9px 8px;
  background: #ece6d8;
  color: var(--ink2);
  line-height: 1.5;
}
.flow-step:first-child { border-radius: 8px 0 0 8px; }
.flow-step:last-child { border-radius: 0 8px 8px 0; }
.flow-step + .flow-step { border-left: 2px dashed rgba(255, 255, 255, 0.6); }
.flow-step .mini { display: block; opacity: 0.85; color: inherit; }
.flow-step.done { background: var(--green); color: #fff; }
.flow-step.cur { background: var(--amber); color: #fff; font-weight: 700; }
.flow-step.dim { opacity: 0.75; }

/* 差异行高亮 */
:deep(.diff-row) { background: #fdf6ec; }
.diff-neg { color: var(--red); font-weight: 700; }
.diff-pos { color: var(--green); font-weight: 700; }
.diff-zero { color: var(--green); }
.neg-book { color: var(--red); font-weight: 700; }
.big-amount { color: var(--red); font-weight: 700; }

/* 五步向导横条 */
.wizard-bar {
  display: flex;
  gap: 0;
  align-items: stretch;
  margin-top: 6px;
}
.wz-step {
  flex: 1;
  padding: 12px 14px;
  background: #ece6d8;
  color: var(--ink2);
  min-width: 0;
}
.wz-step:first-child { border-radius: 10px 0 0 10px; }
.wz-step:last-child { border-radius: 0 10px 10px 0; }
.wz-step + .wz-step { border-left: 2px dashed rgba(255, 255, 255, 0.5); }
.wz-active { background: var(--green); color: #fff; }
.wz-now { background: var(--amber); color: #fff; }
.wz-dim { opacity: 0.85; }
.wz-no { font-size: 11px; opacity: 0.85; }
.wz-title { font-size: 13px; font-weight: 700; margin-top: 4px; }
.wz-body { font-size: 11.5px; margin-top: 6px; display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
.wz-active .mini, .wz-now .mini, .wz-active .hint-line, .wz-now .hint-line { color: rgba(255, 255, 255, 0.92); }
.hint-line { font-size: 11.5px; }

/* ============ M2-5 手机版(≤768px):卡片流 + 大按钮 + 定位框 ============ */
.m-only { display: none; }

@media (max-width: 768px) {
  .m-only { display: block; }
  .st-desktop { display: none; } /* 桌面表格换成卡片流 */
  .ledger-page { padding-bottom: 84px; } /* 给底部固定提交条让位 */
  .ledger-title .sub { display: none; }
  .flow-bar { flex-wrap: wrap; }
  .flow-step { flex: 1 1 33%; border-radius: 0 !important; }
  .grid-cols-2 { grid-template-columns: 1fr !important; } /* 历史+损耗竖排 */
  /* grid 子项 min-width 默认 auto:内嵌 el-table 会把整页撑出横向滚动,压回去让表格自己滚 */
  .grid-cols-2 > * { min-width: 0; }
  .wizard-bar { flex-direction: column; }
  .wz-step { border-radius: 0 !important; }
  .wz-step + .wz-step { border-left: none; border-top: 2px dashed rgba(255, 255, 255, 0.5); }

  /* 定位框:扫码枪回车即定位;摄像头可用才有 📷 */
  .locate-bar {
    position: sticky;
    top: 52px; /* App.vue 手机顶栏高度 */
    z-index: 6;
    display: flex;
    gap: 8px;
    padding: 8px 0;
    background: var(--card);
  }
  .locate-input {
    flex: 1;
    min-width: 0;
    font-size: 16px; /* ≥16px 防 iOS 聚焦自动放大 */
    padding: 12px 12px;
    border: 2px solid var(--line2);
    border-radius: 10px;
    background: #fff;
    color: var(--ink);
  }
  .locate-input:focus { outline: none; border-color: var(--green); }
  .scan-btn, .locate-go {
    flex-shrink: 0;
    font-size: 16px;
    padding: 0 16px;
    border: none;
    border-radius: 10px;
    background: var(--green);
    color: #fff;
    font-weight: 700;
  }
  .scan-btn { background: var(--blue); font-size: 20px; }

  .m-tip {
    font-size: 12.5px;
    color: var(--amber);
    background: var(--amber-soft);
    border-radius: 8px;
    padding: 8px 12px;
    margin: 8px 0 10px;
  }

  /* 一行一 SKU 大卡 */
  .st-card {
    border: 1.5px solid var(--line);
    border-radius: 12px;
    padding: 12px 14px;
    margin-bottom: 10px;
    background: #fff;
  }
  .st-card.changed { border-color: var(--amber); background: #fffaf2; }
  .st-card.located { border-color: var(--green); box-shadow: 0 0 0 3px var(--green-soft); }
  .st-card-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .st-name { font-size: 16px; }
  .st-diff-chip { margin-left: auto; font-size: 12.5px; }
  .st-card-nums { display: flex; gap: 14px; margin-top: 10px; align-items: stretch; }
  .st-book, .st-actual { flex: 1; display: flex; flex-direction: column; gap: 4px; }
  .st-book .lab, .st-actual .lab { font-size: 11.5px; color: var(--ink2); }
  .book-big { font-size: 28px; line-height: 1.2; }
  .big-num-input {
    width: 100%;
    box-sizing: border-box;
    font-family: var(--num);
    font-size: 26px;
    font-weight: 700;
    text-align: center;
    padding: 8px 6px;
    border: 2px solid var(--line2);
    border-radius: 10px;
    color: var(--ink);
    background: #fff;
  }
  .big-num-input:focus { outline: none; border-color: var(--green); }
  .big-num-input:disabled { background: #f3f0e8; color: var(--ink2); }

  /* 原因大按钮组(差异行才出现) */
  .st-reasons { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
  .reason-btn {
    font-size: 14px;
    padding: 10px 14px;
    border: 1.5px solid var(--line2);
    border-radius: 10px;
    background: #fff;
    color: var(--ink);
  }
  .reason-btn.sel { background: var(--green); border-color: var(--green); color: #fff; font-weight: 700; }
  .reason-btn.exempt.sel { background: var(--blue); border-color: var(--blue); }
  .reason-btn:disabled { opacity: 0.5; }
  .need-reason { color: var(--red); margin-top: 6px; }

  /* 底部固定大提交条 */
  .st-submit-bar {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 20;
    display: flex;
    gap: 10px;
    padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
    background: var(--card);
    border-top: 1px solid var(--line);
    box-shadow: 0 -2px 10px rgba(60, 50, 30, 0.08);
  }
  .bar-btn {
    flex: 1;
    font-size: 16px;
    font-weight: 700;
    padding: 14px 8px;
    border-radius: 12px;
    border: none;
  }
  .bar-btn.primary { background: var(--green); color: #fff; flex: 2; }
  .bar-btn.primary:disabled { opacity: 0.6; }
  .bar-btn.ghost { background: #fff; border: 1.5px solid var(--line2); color: var(--ink); }

  /* 摄像头扫码浮层 */
  .scan-overlay {
    position: fixed;
    inset: 0;
    z-index: 50;
    background: rgba(0, 0, 0, 0.82);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 14px;
  }
  .scan-video { width: 88vw; max-height: 50vh; border-radius: 12px; }
  .scan-hint { color: #fff; font-size: 14px; }
  .scan-close { background: #fff; }
}

/* 损耗小结卡(mockup p9) */
.loss-cards { display: flex; gap: 10px; margin: 12px 0; }
.loss-card { flex: 1; text-align: center; padding: 12px; border-radius: 8px; }
.loss-card.amber { background: var(--amber-soft); }
.loss-card.amber .loss-amount { color: var(--amber); }
.loss-card.red { background: var(--red-soft); }
.loss-card.red .loss-amount { color: var(--red); }
.loss-card.green { background: var(--green-soft); }
.loss-card.green .loss-amount { color: var(--green); }
.loss-amount { font-size: 20px; font-weight: 700; }
</style>
