<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createManualTransfer, executeTicket, listTickets, listTransfers, ticketDetail, updateItemQty,
  verifyTicket, type PrekitItemRow, type PrekitTicketDetail, type PrekitTicketRow, type TransferRow,
} from '@/api/prekit'
import { pageMachines, pageProducts, type Machine, type Product } from '@/api/basedata'
import DocDetailDrawer from '@/components/doc/DocDetailDrawer.vue'
import { enqueueOffline, isNetworkError, onOfflineReplayed } from '@/utils/offline-queue'
import { isMobile } from '@/utils/viewport'

/**
 * 出库上架页(M2-3 桌面版 + M2-5 手机版,对照 mockup p5):货流两段式的交接点。
 * 配货单闭环:补货建议 → 按机装箱(pre-kit)→ 到机上架 → 次日导入核销(带回率);
 * 转移单唯一生产者 = 后台补货记录导入(P0-4),手工录单仅兜底(预挂单机制)。
 *
 * M2-5 手机版(≤768px):配货单列表变大卡片(核销状态大 chip);详情变装箱清单卡
 * (SKU 大字 + 带出量大字 + 「已装箱」大勾选,勾选进度存本机);「标记已执行」大按钮,
 * 断网点击自动入离线队列恢复重传(utils/offline-queue)。
 */

const loading = ref(false)
const tickets = ref<PrekitTicketRow[]>([])
const transfers = ref<TransferRow[]>([])
const statusFilter = ref<string>('')

async function load() {
  loading.value = true
  try {
    const [t, tr] = await Promise.all([listTickets(statusFilter.value || undefined), listTransfers()])
    tickets.value = t
    transfers.value = tr
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ---------- 机器/商品档案(手工录单选项) ----------
const machines = ref<Machine[]>([])
const products = ref<Product[]>([])
let mapsLoaded = false
async function loadMaps() {
  if (mapsLoaded) return
  const [m, p] = await Promise.all([
    pageMachines({ current: 1, size: 200 }),
    pageProducts({ current: 1, size: 500 }),
  ])
  machines.value = m.records
  products.value = p.records
  mapsLoaded = true
}

// ---------- 配货单状态样式 ----------
const statusChip = (s: string) =>
  s === '已核销' ? 'success' : s === '有差异' ? 'danger' : s === '已执行' ? 'warning' : 'info'
const ratePct = (v: number | string | null | undefined) =>
  v == null ? '—' : `${(Number(v) * 100).toFixed(1)}%`
const n = (v: number | string | null | undefined) =>
  v == null ? '—' : Number(v).toLocaleString(undefined, { maximumFractionDigits: 1 })
const dt = (v: string | null | undefined) => (v ? v.replace('T', ' ').slice(0, 16) : '—')

// ---------- 配货单详情(打印友好) ----------
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<PrekitTicketDetail | null>(null)
const qtyEdit = reactive<Record<number, number>>({})
async function openDetail(row: PrekitTicketRow | { id: number }) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await ticketDetail(row.id)
    for (const item of detail.value.items) qtyEdit[item.id] = Number(item.qtyPlanned)
    loadPacked(row.id)
  } finally {
    detailLoading.value = false
  }
}

// ---------- 手机装箱清单勾选(M2-5,本机 checklist,键=配货单 id) ----------
const PACK_PREFIX = 'vend_pk_check_'
const packed = reactive<Record<number, boolean>>({})

function loadPacked(ticketId: number) {
  Object.keys(packed).forEach((k) => delete packed[Number(k)])
  try {
    const saved = JSON.parse(localStorage.getItem(PACK_PREFIX + ticketId) || '{}')
    Object.entries(saved).forEach(([k, v]) => { packed[Number(k)] = !!v })
  } catch { /* 空 checklist 起步 */ }
}

function togglePacked(item: PrekitItemRow) {
  packed[item.id] = !packed[item.id]
  if (detail.value) localStorage.setItem(PACK_PREFIX + detail.value.ticket.id, JSON.stringify(packed))
}

const packedCount = computed(() => (detail.value?.items || []).filter((i) => packed[i.id]).length)
const detailHead = computed(() => detail.value?.head ?? null)
const editable = computed(() => detail.value?.ticket.ticketStatus === '已生成')

/** 改带出量(仅「已生成」;change 即提交) */
async function saveQty(item: PrekitItemRow) {
  const v = qtyEdit[item.id]
  if (v == null || v <= 0 || v === Number(item.qtyPlanned)) return
  await updateItemQty(item.id, v)
  ElMessage.success(`「${item.productName}」带出量已改为 ${v}(op_log 留痕)`)
  await openDetail({ id: item.ticketId })
  await load()
}

async function doExecute(id: number, ticketNo: string) {
  await ElMessageBox.confirm(
    `标记「${ticketNo}」已执行?表示仓库已照单装箱出发——执行后带出量冻结,差异走次日核销。`,
    '🚚 装箱出发',
    { confirmButtonText: '已装箱出发', cancelButtonText: '再想想', type: 'warning' },
  )
  try {
    await executeTicket(id)
  } catch (e) {
    // 断网:入离线队列,恢复后自动重传(顶部黄条提示)
    if (isNetworkError(e)) {
      enqueueOffline('prekit-execute', `配货单「${ticketNo}」标记已执行`, { id }, String(id))
      ElMessage.warning('网络不通:「标记已执行」已离线暂存,恢复后自动重传——可先去下一台')
      return
    }
    throw e
  }
  localStorage.removeItem(PACK_PREFIX + id) // 执行完清装箱勾选
  ElMessage.success('已标记执行(执行人/时间已留痕)。次日导入后台《系统补货记录》自动核销。')
  await load()
  if (detailVisible.value) await openDetail({ id })
}

/** 离线队列重放成功 → 刷新列表/详情 */
const offReplay = onOfflineReplayed((task) => {
  if (task.type !== 'prekit-execute') return
  const { id } = task.payload as { id: number }
  localStorage.removeItem(PACK_PREFIX + id)
  load()
  if (detailVisible.value && detail.value?.ticket.id === id) openDetail({ id })
})
onUnmounted(offReplay)

async function doVerify(row: PrekitTicketRow) {
  const r = await verifyTicket(row.id)
  if (r.matched) {
    ElMessage.success('核销完成,带回率已计算')
  } else {
    ElMessage.warning(r.message ?? '±48h 窗口内没有可匹配的导入转移单')
  }
  await load()
}

function printTicket() {
  window.print()
}

// ---------- 转移单卡 ----------
const docDrawerVisible = ref(false)
const docDrawerId = ref<number | null>(null)
function openDoc(id: number) {
  docDrawerId.value = id
  docDrawerVisible.value = true
}
const transferStatus = (row: TransferRow) => {
  if (row.docStatus === '已作废' && row.matchedDocId) return { label: '已冲抵', type: 'info' as const, tip: `预挂单已被导入转移单 #${row.matchedDocId} 冲抵(P0-4 不双扣)` }
  if (row.docStatus === '预挂单') return { label: '预挂单', type: 'warning' as const, tip: '手工单只锁仓库侧,待次日后台补货记录导入自动冲抵;超 48h 自动转正' }
  if (row.docStatus === '已确认') return { label: '已确认', type: 'success' as const, tip: '' }
  return { label: row.docStatus, type: 'info' as const, tip: '' }
}

// ---------- 手工转移单录入(兜底) ----------
const manualVisible = ref(false)
const manualSaving = ref(false)
const manualForm = reactive({
  docType: '出库上架',
  machineId: null as number | null,
  bizDate: new Date().toISOString().slice(0, 10),
  remark: '',
  items: [{ productId: null as number | null, qty: 1, slotNo: '' }],
})
async function openManual() {
  await loadMaps()
  manualForm.items = [{ productId: null, qty: 1, slotNo: '' }]
  manualForm.remark = ''
  manualVisible.value = true
}
function addManualRow() {
  manualForm.items.push({ productId: null, qty: 1, slotNo: '' })
}
async function submitManual() {
  if (!manualForm.machineId) {
    ElMessage.warning('请选择机器')
    return
  }
  const items = manualForm.items.filter((i) => i.productId && i.qty > 0)
  if (items.length === 0) {
    ElMessage.warning('至少录一行商品+数量')
    return
  }
  manualSaving.value = true
  try {
    const r = await createManualTransfer({
      docType: manualForm.docType,
      machineId: manualForm.machineId,
      bizDate: manualForm.bizDate,
      remark: manualForm.remark || undefined,
      items: items.map((i) => ({ productId: i.productId!, qty: i.qty, slotNo: i.slotNo || undefined })),
    })
    manualVisible.value = false
    ElMessage.success(
      r.docStatus === '预挂单'
        ? `${r.docNo} 已确认为「预挂单」:仓库侧已锁定,将待次日后台补货记录导入自动冲抵`
        : `${r.docNo} 已确认过账(${r.docStatus})`,
    )
    await load()
  } finally {
    manualSaving.value = false
  }
}
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 日常台账 / 出库上架</div>
    <div class="ledger-title">
      <h2>出库上架</h2>
      <span class="sub">货流两段式:大仓是蓄水池(公式算该蓄多少),机器是水杯(每天倒满不空)</span>
    </div>
    <p class="ledger-note">
      两段的交接点 = <b>出库上架转移单</b>(唯一生产者 = 后台补货记录导入);配货单(pre-kit)= 补货员照单从仓库装箱,到机器直接开箱补,不现场数货。
    </p>

    <!-- 🗺 货流全景(mockup p5:两个账本,两次转运的心智图) -->
    <el-card shadow="never" class="mb-12px flow-map-card print-hide">
      <h3 style="font-family: var(--serif); margin: 0 0 4px">
        🗺 货流全景 · 两个账本,两次转运
        <span class="hint">仓库账自己记(账本①),机内账以厂家后台为权威(账本②)</span>
      </h3>
      <div class="flow-map">
        <div class="fm-node soft-blue"><b>🚛 批量进货</b><div class="mini">按采购周期<br />(R,S) 公式算量</div></div>
        <b class="fm-arrow">─收货点数─►</b>
        <div class="fm-node deep"><b>🏬 大仓库(蓄水池)</b><div class="fm-sub">账本①自己记:入库+ 上架− 盘点修正<br />现存量见「库存管理」</div></div>
        <b class="fm-arrow">─每天 pre-kit 上架─►</b>
        <div class="fm-node green"><b>🥤 机器(水杯 · 每天倒满)</b><div class="fm-sub">账本②厂家后台权威:补货+ 出货−<br />系统只做核对与告警</div></div>
        <b class="fm-arrow">─消费者─►</b>
        <div class="fm-node soft-amber"><b>💰 销售</b><div class="mini">出货明细<br />每日导入回流</div></div>
      </div>
      <p class="mini" style="margin: 10px 0 0">
        两段的交接点 = <b>出库上架单</b>(从后台补货记录自动生成,仓库账自动扣);两个账本每天导入时自动互相核对,对不上亮灯。
      </p>
    </el-card>

    <!-- ⚡任务来源 + 编号流程条(七律#2:领着人干活) -->
    <el-alert type="info" :closable="false" class="mb-12px">
      <template #title>
        <span>⚡ 任务:补货闭环四步,带回率是补货 PDCA 的核心指标(核销自动算)</span>
        <div class="flow-bar">
          <span>① 补货建议(AI 补货页勾选)</span>
          <span>② 配货单装箱(标记已执行)</span>
          <span>③ 到机上架(后台自动记录)</span>
          <span>④ 次日导入核销(±48h 窗口 · 差量=带回)</span>
        </div>
      </template>
    </el-alert>

    <!-- ============ 配货单列表卡 ============ -->
    <el-card shadow="never" class="mb-12px print-hide">
      <div class="flex items-center gap-12px mb-8px flex-wrap">
        <b>🧺 Pre-kit 配货单</b>
        <el-radio-group v-model="statusFilter" size="small" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="已生成">已生成</el-radio-button>
          <el-radio-button value="已执行">已执行</el-radio-button>
          <el-radio-button value="已核销">已核销</el-radio-button>
          <el-radio-button value="有差异">有差异</el-radio-button>
        </el-radio-group>
        <span class="mini" style="margin-left: auto">来源:AI 补货页 → 机器侧勾选行 →「生成配货单」</span>
      </div>
      <!-- 手机版配货单大卡(M2-5):整卡可点进详情,核销状态大 chip -->
      <div class="m-only pk-cards">
        <div v-for="row in tickets" :key="row.id" class="pk-card" @click="openDetail(row)">
          <div class="pk-card-head">
            <b class="pk-machine">📦 {{ row.machineName ?? '机器#' + row.machineId }}</b>
            <span
              class="chip pk-status"
              :class="row.ticketStatus === '已核销' ? 'c-green' : row.ticketStatus === '有差异' ? 'c-red' : row.ticketStatus === '已执行' ? 'c-amber' : 'c-blue'"
            >{{ row.ticketStatus }}</span>
          </div>
          <div class="mini num">{{ row.ticketNo }} · {{ row.planDate }}</div>
          <div class="pk-card-nums">
            <span class="num">{{ row.itemCount }} 项 · {{ n(row.totalPlanned) }} 件</span>
            <span v-if="row.takebackRate != null" class="num" :style="{ color: Number(row.takebackRate) > 0 ? 'var(--amber)' : 'var(--green)' }">
              带回率 {{ ratePct(row.takebackRate) }}
            </span>
            <span v-if="row.overdue" class="chip c-amber">⚠️ 超窗未核销</span>
          </div>
          <button
            v-if="row.ticketStatus === '已生成'"
            class="pk-exec-btn"
            @click.stop="doExecute(row.id, row.ticketNo)"
          >🚚 标记已执行(装箱出发)</button>
          <button v-if="row.overdue" class="pk-exec-btn verify" @click.stop="doVerify(row)">🔁 手动核销</button>
        </div>
        <el-empty v-if="!tickets.length" description="暂无配货单:去「AI 补货提示 → 机器侧」生成" :image-size="60" />
      </div>

      <div class="pk-desktop">
      <el-table :data="tickets" size="small">
        <el-table-column label="配货单号" width="150">
          <template #default="{ row }">
            <a class="name-link" @click="openDetail(row)"><span class="num">{{ row.ticketNo }}</span></a>
          </template>
        </el-table-column>
        <el-table-column label="机器" min-width="120">
          <!-- M2-10 P1-2:机器名下钻(七律#3) -->
          <template #default="{ row }">
            <a class="name-link" @click="$router.push(`/machines/${row.machineId}`)">{{ row.machineName ?? '机器#' + row.machineId }} ↗</a>
          </template>
        </el-table-column>
        <el-table-column prop="planDate" label="配货日" width="100" />
        <el-table-column label="SKU 数 / 计划带出" align="right" width="130">
          <template #default="{ row }">
            <span class="num">{{ row.itemCount }} 项 · {{ n(row.totalPlanned) }} 件</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="160">
          <template #default="{ row }">
            <el-tag :type="statusChip(row.ticketStatus)" size="small">{{ row.ticketStatus }}</el-tag>
            <el-tooltip v-if="row.overdue" content="配货日已过 48h 核销窗口仍未等到后台补货记录——去导入中心导《系统补货记录》,或点「手动核销」重扫" placement="top">
              <el-tag type="warning" size="small" class="ml-4px">⚠️ 超窗未核销</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="带回率" align="right" width="90">
          <template #default="{ row }">
            <b class="num" :style="{ color: Number(row.takebackRate) > 0 ? 'var(--amber)' : 'var(--green)' }">
              {{ ratePct(row.takebackRate) }}
            </b>
          </template>
        </el-table-column>
        <el-table-column label="执行 / 核销时间" width="150">
          <template #default="{ row }">
            <div class="mini">执 {{ dt(row.execAt) }}</div>
            <div class="mini">核 {{ dt(row.verifyAt) }}</div>
          </template>
        </el-table-column>
        <!-- 操作列 fixed=right:窄视口不再被水平裁切(M2-3 坑7) -->
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情/打印</el-button>
            <el-button v-if="row.ticketStatus === '已生成'" link type="warning" size="small" @click="doExecute(row.id, row.ticketNo)">
              标记已执行
            </el-button>
            <el-button v-if="row.overdue" link type="danger" size="small" @click="doVerify(row)">手动核销</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无配货单:去「AI 补货提示 → 机器侧」勾选建议行,点「生成配货单」" :image-size="60" />
        </template>
      </el-table>
      </div><!-- /.pk-desktop -->
      <p class="mini mt-8px">
        💡 带回率 = Σ带回 ÷ Σ带出(带出没上架的比例)。核销 = 次日(±48h 窗口)导入的转移单按 <b>机器×SKU</b> 自动匹配,差量记带回——这是补货 PDCA 的唯一数据生产者。
      </p>
    </el-card>

    <!-- ============ 转移单列表卡 ============ -->
    <el-card shadow="never" class="mb-12px print-hide">
      <div class="flex items-center gap-12px mb-8px">
        <b>📄 转移单(仓库 → 机器)</b>
        <span class="mini">唯一生产者 = 导入中心通道②《系统补货记录》;负数补货 = 退库取回</span>
        <span style="margin-left: auto; display: flex; gap: 8px">
          <el-button size="small" type="primary" plain @click="$router.push('/import')">📂 去导入中心导《系统补货记录》</el-button>
          <el-button size="small" @click="openManual">✍️ 手工录入(兜底)</el-button>
        </span>
      </div>
      <el-table :data="transfers" size="small" max-height="420">
        <el-table-column label="单号" width="160">
          <template #default="{ row }">
            <a class="name-link" @click="openDoc(row.id)"><span class="num mini">{{ row.docNo }}</span></a>
          </template>
        </el-table-column>
        <el-table-column prop="bizDate" label="业务日期" width="100" />
        <el-table-column label="机器" min-width="110">
          <!-- M2-10 P1-2:机器名下钻(七律#3;无 machineId 的行保持纯文本) -->
          <template #default="{ row }">
            <a v-if="row.machineId" class="name-link" @click="$router.push(`/machines/${row.machineId}`)">
              {{ row.machineName ?? '机器#' + row.machineId }} ↗
            </a>
            <template v-else>—</template>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.docType === '退库' ? 'warning' : 'primary'" size="small" effect="plain">{{ row.docType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="品项 / 件数" align="right" width="110">
          <template #default="{ row }">
            <span class="num">{{ row.itemCount }} · {{ n(row.totalQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">
            <el-tag :type="row.docSource === '导入' ? 'success' : 'info'" size="small" effect="plain">
              {{ row.docSource === '导入' ? '后台导入' : '手工录入' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip :content="transferStatus(row).tip" :disabled="!transferStatus(row).tip" placement="top">
              <el-tag :type="transferStatus(row).type" size="small">{{ transferStatus(row).label }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
        <template #empty>
          <el-empty description="暂无转移单:去导入中心导后台《系统补货记录》自动生成" :image-size="60" />
        </template>
      </el-table>
      <p class="mini mt-8px">
        💡 「手工录入」仅兜底(后台漏记/机器故障线下补货等):出库上架确认后进<b>「预挂单」</b>——只锁仓库侧,将待次日后台补货记录导入自动冲抵(不双扣);超 48h 无后台记录才转正补机器账。
      </p>
    </el-card>

    <p class="ledger-foot-note print-hide">— 两级库存:仓库账在这里,机内账以厂家后台为准,系统只做核对与告警 —</p>

    <!-- ============ 配货单详情(按机打印友好视图) ============ -->
    <el-dialog v-model="detailVisible" :width="isMobile ? '96%' : '760px'" top="4vh" class="print-area">
      <template #header>
        <span style="font-weight: 700">
          🧺 {{ detail?.ticket.ticketNo ?? '配货单' }}
          <el-tag v-if="detail" :type="statusChip(detail.ticket.ticketStatus)" size="small" class="ml-4px">
            {{ detail.ticket.ticketStatus }}
          </el-tag>
        </span>
      </template>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="ticket-head">
            <div>
              <div class="mini">目标机器(一机一箱)</div>
              <!-- M2-10 P1-2:详情头机器名下钻(七律#3) -->
              <b style="font-size: 16px">
                📦
                <a class="name-link" @click="$router.push(`/machines/${detail.ticket.machineId}`)">
                  {{ detailHead?.machineName ?? '机器#' + detail.ticket.machineId }} ↗
                </a>
              </b>
            </div>
            <div>
              <div class="mini">配货日期</div>
              <b class="num">{{ detail.ticket.planDate }}</b>
            </div>
            <div>
              <div class="mini">执行 / 核销</div>
              <span class="mini">执 {{ dt(detail.ticket.execAt) }} · 核 {{ dt(detail.ticket.verifyAt) }}</span>
            </div>
            <div v-if="detail.ticket.takebackRate != null">
              <div class="mini">带回率</div>
              <b class="num" :style="{ color: Number(detail.ticket.takebackRate) > 0 ? 'var(--amber)' : 'var(--green)' }">
                {{ ratePct(detail.ticket.takebackRate) }}
              </b>
            </div>
          </div>
          <!-- 手机装箱清单卡(M2-5):SKU 大字 + 带出量大字 + 已装箱大勾选(本机存进度) -->
          <div class="m-only pk-pack-list">
            <div class="pk-pack-progress">
              🧺 装箱进度 <b class="num">{{ packedCount }}/{{ detail.items.length }}</b>
              <span class="mini">勾完再点「标记已执行」;进度存本机,断网不丢</span>
            </div>
            <div
              v-for="row in detail.items"
              :key="row.id"
              class="pk-pack-card"
              :class="{ done: packed[row.id] }"
              @click="togglePacked(row)"
            >
              <span class="pk-check" :class="{ on: packed[row.id] }">{{ packed[row.id] ? '✓' : '' }}</span>
              <div class="pk-pack-info">
                <b class="pk-pack-name">{{ row.productName }}</b>
                <div class="mini">{{ row.skuCode }}{{ row.fefoEarliestInDate ? ' · FEFO 拿 ' + String(row.fefoEarliestInDate).slice(5) + ' 起旧货' : '' }}</div>
                <div v-if="row.qtyTakeback != null && Number(row.qtyTakeback) > 0" class="mini" style="color: var(--amber)">
                  带回 {{ n(row.qtyTakeback) }}
                </div>
              </div>
              <b class="pk-pack-qty num">{{ n(qtyEdit[row.id] ?? row.qtyPlanned) }}<span class="pk-unit">{{ row.unit }}</span></b>
            </div>
          </div>

          <div class="pk-desktop">
          <el-table :data="detail.items" size="small" class="mt-8px">
            <el-table-column label="商品(照单装箱)" min-width="170">
              <template #default="{ row }">
                <b>{{ row.productName }}</b>
                <div class="mini">{{ row.skuCode }}{{ row.barcode ? ' · ' + row.barcode : '' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="带出量" align="right" width="130">
              <template #default="{ row }">
                <el-input-number
                  v-if="editable"
                  v-model="qtyEdit[row.id]"
                  :min="0.001"
                  :precision="1"
                  size="small"
                  style="width: 110px"
                  @change="saveQty(row)"
                />
                <b v-else class="num">{{ n(row.qtyPlanned) }} {{ row.unit }}</b>
              </template>
            </el-table-column>
            <el-table-column label="仓库最早入库" width="120">
              <template #default="{ row }">
                <el-tooltip content="FEFO 先出旧货:装箱时优先拿最早入库那批(提示级,完整批次管理后续里程碑)" placement="top">
                  <el-tag v-if="row.fefoEarliestInDate" size="small" type="info" effect="plain">
                    🗓 {{ String(row.fefoEarliestInDate).slice(5) }} 起
                  </el-tag>
                  <span v-else class="mini">—</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="实上架" align="right" width="80">
              <template #default="{ row }">
                <span class="num">{{ row.qtyLoaded == null ? '—' : n(row.qtyLoaded) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="带回" align="right" width="80">
              <template #default="{ row }">
                <b v-if="row.qtyTakeback != null && Number(row.qtyTakeback) > 0" class="num" style="color: var(--amber)">
                  {{ n(row.qtyTakeback) }}
                </b>
                <span v-else class="num">{{ row.qtyTakeback == null ? '—' : 0 }}</span>
              </template>
            </el-table-column>
          </el-table>
          </div><!-- /.pk-desktop -->
          <p class="mini mt-8px pk-desktop">
            🧺 装箱提示:一机一箱按单拣货;<b>先出旧货(FEFO)</b>;带出量在装箱前可改,执行后冻结。实上架/带回两列由次日导入自动核销回填。
          </p>

          <!-- 手机版大按钮区(M2-5):执行是大按钮;已执行后到机场景露核销状态大 chip -->
          <div class="m-only pk-m-actions">
            <button
              v-if="detail.ticket.ticketStatus === '已生成'"
              class="pk-exec-btn big"
              @click="doExecute(detail.ticket.id, detail.ticket.ticketNo)"
            >🚚 标记已执行(装箱出发)</button>
            <div v-else class="pk-big-chip-wrap">
              <span
                class="pk-big-chip"
                :class="detail.ticket.ticketStatus === '已核销' ? 'c-green' : detail.ticket.ticketStatus === '有差异' ? 'c-red' : 'c-amber'"
              >
                {{ detail.ticket.ticketStatus === '已执行' ? '🚚 已执行 · 待次日导入核销' : detail.ticket.ticketStatus === '已核销' ? '✅ 已核销 · 带回率 ' + ratePct(detail.ticket.takebackRate) : '⚠️ ' + detail.ticket.ticketStatus }}
              </span>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <span class="print-hide">
          <!-- 打印/执行是桌面动作;手机版执行走正文大按钮(pk-m-actions),避免双按钮 -->
          <el-button class="pk-desktop" @click="printTicket">🖨 打印拣货单</el-button>
          <el-button
            v-if="detail?.ticket.ticketStatus === '已生成'"
            class="pk-desktop"
            type="warning"
            @click="doExecute(detail!.ticket.id, detail!.ticket.ticketNo)"
          >
            🚚 标记已执行(装箱出发)
          </el-button>
          <el-button @click="detailVisible = false">关闭</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- ============ 手工转移单录入(兜底) ============ -->
    <el-dialog v-model="manualVisible" width="640px" title="✍️ 手工转移单(兜底)">
      <el-alert type="warning" :closable="false" class="mb-12px">
        <template #title>
          转移单唯一生产者 = 后台补货记录导入。手工录入仅兜底:<b>出库上架确认后进「预挂单」</b>,
          只锁仓库侧,将待次日后台补货记录导入自动冲抵(不双扣);超 48h 无后台记录自动转正。
        </template>
      </el-alert>
      <el-form label-width="90px" size="small">
        <el-form-item label="类型">
          <el-radio-group v-model="manualForm.docType">
            <el-radio-button value="出库上架">出库上架(仓库→机器)</el-radio-button>
            <el-radio-button value="退库">退库(机器→仓库)</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="机器">
          <el-select v-model="manualForm.machineId" filterable placeholder="选择机器" style="width: 260px">
            <el-option v-for="m in machines" :key="m.id" :label="m.machineName" :value="m.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务日期">
          <el-date-picker v-model="manualForm.bizDate" type="date" value-format="YYYY-MM-DD" style="width: 160px" />
        </el-form-item>
        <el-form-item label="明细">
          <div style="width: 100%">
            <div v-for="(it, i) in manualForm.items" :key="i" class="flex items-center gap-8px mb-6px">
              <el-select v-model="it.productId" filterable placeholder="商品" style="flex: 1">
                <el-option v-for="p in products" :key="p.id" :label="p.productName" :value="p.id!" />
              </el-select>
              <el-input-number v-model="it.qty" :min="0.001" :precision="1" style="width: 120px" />
              <el-input v-model="it.slotNo" placeholder="货道(选填)" style="width: 100px" />
              <el-button link type="danger" :disabled="manualForm.items.length === 1" @click="manualForm.items.splice(i, 1)">✕</el-button>
            </div>
            <el-button size="small" @click="addManualRow">+ 加一行</el-button>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="manualForm.remark" placeholder="为什么手工录?(如:后台漏记 / 机器故障线下补货)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button type="primary" :loading="manualSaving" @click="submitManual">确认录入(自动过账)</el-button>
      </template>
    </el-dialog>

    <DocDetailDrawer v-model="docDrawerVisible" :doc-id="docDrawerId" />
  </div>
</template>

<style scoped>
.flow-bar {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
  font-size: 12px;
}
.flow-bar span {
  padding: 2px 10px;
  border-radius: 12px;
  background: #eee9dd;
  color: #7c745f;
}
/* 🗺 货流全景(mockup p5) */
.flow-map-card {
  background: linear-gradient(135deg, #fffdf8, #f2efe4);
}
.flow-map {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
  font-size: 12.5px;
}
.fm-node {
  padding: 10px 14px;
  border-radius: 9px;
  text-align: center;
}
.fm-node.soft-blue { background: var(--blue-soft); }
.fm-node.soft-amber { background: var(--amber-soft); }
.fm-node.deep { background: var(--green-deep); color: #fff; }
.fm-node.deep .fm-sub { font-size: 11px; color: #9db8a8; }
.fm-node.green { background: var(--green); color: #fff; }
.fm-node.green .fm-sub { font-size: 11px; color: #d5e6db; }
.fm-arrow { color: var(--ink2); font-size: 12px; }
.ticket-head {
  display: flex;
  gap: 28px;
  flex-wrap: wrap;
  align-items: flex-end;
  padding: 10px 14px;
  background: #f8f5ec;
  border-radius: 8px;
}
.name-link {
  color: var(--green, #2f6e52);
  cursor: pointer;
  font-weight: 600;
}
@media print {
  .print-hide {
    display: none !important;
  }
}

/* ============ M2-5 手机版(≤768px):配货单大卡 + 装箱清单 + 大按钮 ============ */
.m-only { display: none; }

@media (max-width: 768px) {
  .m-only { display: block; }
  .pk-desktop { display: none !important; } /* 桌面表格换卡片,不退化桌面布局 */
  .ledger-title .sub { display: none; }
  .flow-map-card { display: none; } /* 心智图是桌面讲解内容,手机版聚焦干活 */

  /* 配货单列表大卡 */
  .pk-card {
    border: 1.5px solid var(--line);
    border-radius: 12px;
    padding: 12px 14px;
    margin-bottom: 10px;
    background: #fff;
  }
  .pk-card-head { display: flex; align-items: center; gap: 8px; }
  .pk-machine { font-size: 16px; }
  .pk-status { margin-left: auto; font-size: 13px; padding: 4px 12px; }
  .pk-card-nums { display: flex; align-items: center; gap: 12px; margin-top: 6px; flex-wrap: wrap; font-size: 13px; }

  .pk-exec-btn {
    width: 100%;
    margin-top: 10px;
    font-size: 16px;
    font-weight: 700;
    padding: 13px 8px;
    border: none;
    border-radius: 12px;
    background: var(--amber);
    color: #fff;
  }
  .pk-exec-btn.verify { background: var(--blue); }
  .pk-exec-btn.big { padding: 15px 8px; }

  /* 装箱清单卡(详情) */
  .pk-pack-progress { font-size: 14px; margin: 6px 0 10px; display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
  .pk-pack-card {
    display: flex;
    align-items: center;
    gap: 12px;
    border: 1.5px solid var(--line);
    border-radius: 12px;
    padding: 12px 14px;
    margin-bottom: 8px;
    background: #fff;
  }
  .pk-pack-card.done { background: var(--green-soft); border-color: var(--green); }
  .pk-check {
    flex-shrink: 0;
    width: 34px;
    height: 34px;
    border: 2px solid var(--line2);
    border-radius: 10px;
    background: #fff;
    color: #fff;
    font-size: 20px;
    font-weight: 900;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .pk-check.on { background: var(--green); border-color: var(--green); }
  .pk-pack-info { flex: 1; min-width: 0; }
  .pk-pack-name { font-size: 16px; }
  .pk-pack-qty { font-size: 26px; color: var(--ink); }
  .pk-unit { font-size: 12px; color: var(--ink2); margin-left: 2px; }

  /* 到机场景:核销状态大 chip */
  .pk-m-actions { margin-top: 12px; }
  .pk-big-chip-wrap { text-align: center; }
  .pk-big-chip {
    display: inline-block;
    font-size: 16px;
    font-weight: 700;
    padding: 12px 22px;
    border-radius: 14px;
  }
}
</style>
