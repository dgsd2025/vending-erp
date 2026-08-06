<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import DocDetailDrawer from '@/components/doc/DocDetailDrawer.vue'
import CashAdjustDialog from '@/components/money/CashAdjustDialog.vue'
import {
  cancelCashCheck, currentCashCheck, finishCashCheck, genAdjustFromCheck, getCashCheck,
  listCashAdjusts, listCashChecks, markPayableExit, saveCashCheckActuals, startCashCheck,
  type AdjustRow, type CheckDetail, type CheckItemRow, type CheckListRow,
} from '@/api/cashcheck'

/**
 * 钱盘三核对面板(M3-5,§8.2 月度 SOP 的 D2 点亮):
 * ① 账户核对:真实账户 系统余额 vs 实际余额(手填)→ 差异行"生成资金调整单"(唯一出口);
 * ② 平台到账核对:结算模式 UNSET → 横幅 + 整块跳过;定型后平台待结算虚账留档核对;
 * ③ 应付核对:供应商 系统应付 vs 对方账(手填)→ 不符出口两按钮:补录(跳采购页)/红冲(跳对应单据)。
 * 差异高亮;全部核完 + 差异行走了出口(或留说明)才许"完成钱盘"归档。
 */

const router = useRouter()

const detail = ref<CheckDetail | null>(null)
const history = ref<CheckListRow[]>([])
const adjusts = ref<AdjustRow[]>([])
const loading = ref(false)
const saving = ref(false)

/** 手填实际数 / 说明的本地编辑态(itemId → 值) */
const actualEdit = ref<Record<number, number | null>>({})
const noteEdit = ref<Record<number, string>>({})

const editable = computed(() => detail.value?.checkStatus === '进行中')

async function reload(keepId?: number) {
  loading.value = true
  try {
    const [cur, his, adj] = await Promise.all([
      keepId ? getCashCheck(keepId) : currentCashCheck(),
      listCashChecks(12),
      listCashAdjusts(20),
    ])
    detail.value = cur
    history.value = his
    adjusts.value = adj
    actualEdit.value = {}
    noteEdit.value = {}
    if (cur) {
      allItems(cur).forEach((i) => {
        actualEdit.value[i.id] = i.actualAmount == null ? null : Number(i.actualAmount)
        noteEdit.value[i.id] = i.note || ''
      })
    }
  } finally {
    loading.value = false
  }
}

function allItems(d: CheckDetail): CheckItemRow[] {
  return [...d.accountItems, ...d.platformItems, ...d.payableItems]
}

async function doStart() {
  const id = await startCashCheck()
  ElMessage.success('钱盘已开始:系统数已快照(账户余额/平台待结算/供应商应付),照实数填')
  await reload(id)
}

/** 差异实时预览(未保存时按本地输入算) */
function diffOf(row: CheckItemRow): number | null {
  const actual = actualEdit.value[row.id]
  if (actual == null) return null
  return Number((actual - Number(row.systemAmount)).toFixed(2))
}

async function doSave(silent = false) {
  if (!detail.value) return
  const rows = allItems(detail.value)
    .filter((i) => actualEdit.value[i.id] != null || (noteEdit.value[i.id] || '').trim())
    .map((i) => ({
      itemId: i.id,
      actualAmount: actualEdit.value[i.id],
      note: (noteEdit.value[i.id] || '').trim() || null,
    }))
  if (!rows.length) {
    if (!silent) ElMessage.warning('还没填任何实际数')
    return
  }
  saving.value = true
  try {
    await saveCashCheckActuals(detail.value.id, rows)
    if (!silent) ElMessage.success(`已保存 ${rows.length} 行核对结果(差异=实际−系统 已落库)`)
    await reload(detail.value.id)
  } finally {
    saving.value = false
  }
}

// ---------- 出口①:账户差异 → 资金调整单(唯一出口) ----------

const adjustVisible = ref(false)
const adjustPreset = ref<{ accountId: number; amount: number; itemId: number } | null>(null)
const pendingConfirm = ref<{ docId: number; summary: string } | null>(null)

async function openGenAdjust(row: CheckItemRow) {
  if (!detail.value) return
  await doSave(true) // 先落差异,后端按落库差异生成
  const fresh = (await getCashCheck(detail.value.id)).accountItems.find((i) => i.id === row.id)
  if (!fresh || fresh.diffAmount == null || Number(fresh.diffAmount) === 0) {
    ElMessage.warning('该行无差异(或实际数没填),不需要调整单')
    return
  }
  const docId = await genAdjustFromCheck(detail.value.id, row.id)
  ElMessage.success(`资金调整单 #${docId} 已生成(${Number(fresh.diffAmount) > 0 ? '盘盈+' : '盘亏'}${fresh.diffAmount})——老板确认后才落流水`)
  pendingConfirm.value = {
    docId,
    summary: `账户「${row.refName}」系统 ¥${money(fresh.systemAmount)} vs 实际 ¥${money(fresh.actualAmount)},差异 ${fresh.diffAmount}`,
  }
  adjustPreset.value = null
  adjustVisible.value = true
  await reload(detail.value.id)
}

/** 已生成待确认的调整单 → 打开确认步 */
function openConfirmAdjust(row: CheckItemRow) {
  if (!row.adjustDocId) return
  pendingConfirm.value = {
    docId: row.adjustDocId,
    summary: `账户「${row.refName}」差异 ${row.diffAmount}(调整单 #${row.adjustDocId} 待老板确认)`,
  }
  adjustVisible.value = true
}

async function onAdjustDone() {
  ElMessage.success('余额已修正——核对行出口闭环')
  await reload(detail.value?.id)
}

// ---------- 出口②③:应付不符 → 补录 / 红冲(只跳转不重造) ----------

const docDrawerVisible = ref(false)
const docDrawerId = ref<number | null>(null)

async function payableExit(row: CheckItemRow, action: '补录' | '红冲') {
  if (!detail.value) return
  await doSave(true)
  const resp = await markPayableExit(detail.value.id, row.id, action)
  if (resp.message) ElMessage.info(resp.message)
  if (action === '补录' && resp.route) {
    router.push(resp.route) // 采购页:入库/付款/抵扣录入,不重造
    return
  }
  if (action === '红冲') {
    if (resp.sourceDocId) {
      docDrawerId.value = resp.sourceDocId
      docDrawerVisible.value = true // 单据抽屉自带红冲入口(影响清单确认)
    }
  }
  await reload(detail.value.id)
}

// ---------- 收口 ----------

async function doFinish() {
  if (!detail.value) return
  await doSave(true)
  try {
    await finishCashCheck(detail.value.id)
    ElMessage.success(`钱盘 ${detail.value.checkNo} 已完成归档 ✓`)
  } finally {
    await reload(detail.value.id)
  }
}

async function doCancel() {
  if (!detail.value) return
  await ElMessageBox.confirm(
    `作废钱盘 ${detail.value.checkNo}?记录保留为"已作废",可重新开一轮。`,
    '作废钱盘',
    { type: 'warning' },
  )
  await cancelCashCheck(detail.value.id)
  ElMessage.success('已作废')
  await reload()
}

async function openHistory(id: number) {
  await reload(id)
}

function money(v: number | string | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

const diffClass = (v: number | string | null | undefined) =>
  v == null ? '' : Number(v) === 0 ? 'diff-zero' : Number(v) < 0 ? 'diff-neg' : 'diff-pos'

/** 差异行高亮(账户/应付表通用) */
function diffRowClass(scope: { row: CheckItemRow }) {
  const row = scope.row
  const d = diffOf(row) ?? Number(row.diffAmount ?? 0)
  const touched = actualEdit.value[row.id] != null || row.diffAmount != null
  return d !== 0 && touched ? 'diff-row' : ''
}

const adjustStatusChip = (s: string) =>
  ({ 待确认: 'c-amber', 已完成: 'c-green', 已确认: 'c-blue', 已红冲: 'c-red' }[s] || 'c-gray')

onMounted(() => reload())
</script>

<template>
  <div v-loading="loading">
    <!-- ⚡任务来源 + 编号流程条(设计思维律②) -->
    <el-alert type="info" :closable="false" class="mb-12px">
      <template #title>
        ⚡ 任务来源:每月 1 日月度 SOP 任务包(D2 钱盘 · 老板/记账员)·
        流程:<b>① 开始钱盘(系统数快照)→ ② 三核对照实数填 → ③ 差异走出口(调整单/补录/红冲)→ ④ 完成归档</b>
      </template>
    </el-alert>

    <!-- 未开盘:开始按钮 + 历史 -->
    <div v-if="!detail" class="flex items-center gap-12px flex-wrap">
      <el-button type="primary" @click="doStart">▶ 开始本月钱盘(快照系统数)</el-button>
      <span class="mini">三核对:真实账户余额 · 平台上月到账 · 供应商欠款;差异每一分都要有出口</span>
    </div>

    <template v-else>
      <div class="flex items-center gap-10px flex-wrap mb-12px">
        <b>{{ detail.checkNo }}</b>
        <span class="chip" :class="detail.checkStatus === '进行中' ? 'c-amber' : detail.checkStatus === '已完成' ? 'c-green' : 'c-gray'">{{ detail.checkStatus }}</span>
        <span class="mini">{{ detail.checkPeriod }} · 结算模式快照:{{ detail.settleModeSnap }}</span>
        <template v-if="editable">
          <el-button size="small" :loading="saving" @click="doSave()">保存核对</el-button>
          <el-button size="small" type="primary" @click="doFinish">✓ 完成钱盘(归档)</el-button>
          <el-button size="small" type="danger" plain @click="doCancel">作废</el-button>
        </template>
        <el-button v-else size="small" @click="reload()">回到本月(开新一轮)</el-button>
      </div>

      <!-- ① 账户核对 -->
      <h4 class="sec-title">① 账户核对 <span class="hint">系统余额=期初+Σ流水;差异唯一出口=资金调整单(P1-7)</span></h4>
      <el-table :data="detail.accountItems" size="small" :row-class-name="diffRowClass">
        <el-table-column label="账户" min-width="140">
          <template #default="{ row }"><b>{{ row.refName }}</b></template>
        </el-table-column>
        <el-table-column label="系统余额" width="110" align="right">
          <template #default="{ row }"><span class="num">¥{{ money(row.systemAmount) }}</span></template>
        </el-table-column>
        <el-table-column label="实际余额(数出来填)" width="170" align="center">
          <template #default="{ row }">
            <el-input-number v-model="actualEdit[row.id]" :precision="2" :disabled="!editable" size="small" controls-position="right" style="width: 140px" />
          </template>
        </el-table-column>
        <el-table-column label="差异" width="100" align="right">
          <template #default="{ row }">
            <b class="num" :class="diffClass(diffOf(row) ?? row.diffAmount)">
              {{ diffOf(row) == null && row.diffAmount == null ? '未核对' : money(diffOf(row) ?? row.diffAmount) }}
            </b>
          </template>
        </el-table-column>
        <el-table-column label="差异出口" min-width="190">
          <template #default="{ row }">
            <template v-if="row.adjustDocId">
              <span class="chip c-blue">调整单 #{{ row.adjustDocId }}</span>
              <el-button link type="warning" size="small" @click="openConfirmAdjust(row)">去老板确认</el-button>
            </template>
            <el-button
              v-else-if="editable && (diffOf(row) ?? 0) !== 0 && actualEdit[row.id] != null"
              link type="primary" size="small" @click="openGenAdjust(row)"
            >⚡ 生成资金调整单</el-button>
            <span v-else class="mini">{{ (diffOf(row) ?? 0) === 0 && actualEdit[row.id] != null ? '相符 ✓' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="140">
          <template #default="{ row }">
            <el-input v-model="noteEdit[row.id]" :disabled="!editable" size="small" placeholder="差异说明(留痕)" />
          </template>
        </el-table-column>
      </el-table>

      <!-- ② 平台到账核对 -->
      <h4 class="sec-title">② 平台到账核对 <span class="hint">上月结算到账对得上吗</span></h4>
      <el-alert
        v-if="detail.platformSkipped"
        :title="detail.platformNote || '结算模式待核实:本块跳过'"
        type="warning" :closable="false" show-icon class="mb-12px"
      />
      <el-table v-else-if="detail.platformItems.length" :data="detail.platformItems" size="small">
        <el-table-column label="虚拟账户" min-width="140">
          <template #default="{ row }"><b>{{ row.refName }}</b> <span class="chip c-gray">虚拟</span></template>
        </el-table-column>
        <el-table-column label="系统待结算" width="110" align="right">
          <template #default="{ row }"><span class="num">¥{{ money(row.systemAmount) }}</span></template>
        </el-table-column>
        <el-table-column label="平台后台余额(手填)" width="170" align="center">
          <template #default="{ row }">
            <el-input-number v-model="actualEdit[row.id]" :precision="2" :disabled="!editable" size="small" controls-position="right" style="width: 140px" />
          </template>
        </el-table-column>
        <el-table-column label="差异" width="100" align="right">
          <template #default="{ row }">
            <b class="num" :class="diffClass(diffOf(row) ?? row.diffAmount)">{{ diffOf(row) == null && row.diffAmount == null ? '未核对' : money(diffOf(row) ?? row.diffAmount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="出口" min-width="180">
          <template #default>
            <span class="mini">虚账不可手工调:差异走平台结算单核销(里程碑3 · M3-4)</span>
          </template>
        </el-table-column>
      </el-table>
      <p v-else class="mini">结算模式已定型,但还没建「平台待结算」虚拟账户(设置中心 → 资金账户)。</p>

      <!-- ③ 应付核对 -->
      <h4 class="sec-title">③ 应付核对 <span class="hint">发对账单给供应商确认;不符出口=补录或红冲,不许直接改数</span></h4>
      <el-table :data="detail.payableItems" size="small" :row-class-name="diffRowClass">
        <el-table-column label="供应商" min-width="130">
          <template #default="{ row }"><b>{{ row.refName }}</b></template>
        </el-table-column>
        <el-table-column label="系统应付" width="110" align="right">
          <template #default="{ row }"><span class="num">¥{{ money(row.systemAmount) }}</span></template>
        </el-table-column>
        <el-table-column label="对方账(手填)" width="170" align="center">
          <template #default="{ row }">
            <el-input-number v-model="actualEdit[row.id]" :precision="2" :disabled="!editable" size="small" controls-position="right" style="width: 140px" />
          </template>
        </el-table-column>
        <el-table-column label="差异" width="100" align="right">
          <template #default="{ row }">
            <b class="num" :class="diffClass(diffOf(row) ?? row.diffAmount)">{{ diffOf(row) == null && row.diffAmount == null ? '未核对' : money(diffOf(row) ?? row.diffAmount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="不符出口(两按钮)" min-width="220">
          <template #default="{ row }">
            <template v-if="editable && (diffOf(row) ?? 0) !== 0 && actualEdit[row.id] != null">
              <el-button link type="primary" size="small" @click="payableExit(row, '补录')">📝 补录(付款/抵扣)</el-button>
              <el-button link type="danger" size="small" @click="payableExit(row, '红冲')">↩ 红冲(对应单据)</el-button>
            </template>
            <span v-if="row.exitAction" class="chip" :class="row.exitAction === '补录' ? 'c-blue' : 'c-red'">已选:{{ row.exitAction }}</span>
            <span v-else-if="!editable || (diffOf(row) ?? 0) === 0" class="mini">{{ actualEdit[row.id] != null && (diffOf(row) ?? 0) === 0 ? '相符 ✓' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="130">
          <template #default="{ row }">
            <el-input v-model="noteEdit[row.id]" :disabled="!editable" size="small" placeholder="差异说明(留痕)" />
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有供应商往来(结算单/期初应付都为空)" :image-size="46" />
        </template>
      </el-table>
    </template>

    <!-- 最近资金调整单 + 历史钱盘 -->
    <div class="grid grid-cols-2 gap-16px" style="margin-top: 14px">
      <div>
        <h4 class="sec-title">💱 最近资金调整单 <span class="hint">待确认的点行去老板确认</span></h4>
        <el-table :data="adjusts" size="small" @row-click="(r: AdjustRow) => r.docStatus === '待确认' && (pendingConfirm = { docId: r.docId, summary: `${r.accountName} ${r.direction} ¥${money(r.amount)}(${r.reason})` }, adjustVisible = true)">
          <el-table-column prop="docNo" label="单号" width="150">
            <template #default="{ row }"><span class="num name-link">{{ row.docNo }}</span></template>
          </el-table-column>
          <el-table-column prop="accountName" label="账户" min-width="110" />
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">
              <b class="num" :class="row.direction === '支' ? 'diff-neg' : 'diff-pos'">{{ row.direction === '支' ? '−' : '+' }}¥{{ money(row.amount) }}</b>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" width="90" />
          <el-table-column label="状态" width="90" fixed="right">
            <template #default="{ row }"><span class="chip" :class="adjustStatusChip(row.docStatus)">{{ row.docStatus }}</span></template>
          </el-table-column>
          <template #empty><el-empty description="还没有资金调整单" :image-size="46" /></template>
        </el-table>
        <el-button size="small" style="margin-top: 8px" @click="pendingConfirm = null, adjustPreset = null, adjustVisible = true">＋ 手工新建调整单(期初错等)</el-button>
      </div>
      <div>
        <h4 class="sec-title">🗂 历史钱盘 <span class="hint">点行查看归档</span></h4>
        <el-table :data="history" size="small" @row-click="(r: CheckListRow) => openHistory(r.id)">
          <el-table-column prop="checkNo" label="单号" width="150">
            <template #default="{ row }"><span class="num name-link">{{ row.checkNo }}</span></template>
          </el-table-column>
          <el-table-column prop="checkPeriod" label="月份" width="90" />
          <el-table-column label="差异行" width="70" align="right">
            <template #default="{ row }"><span class="num">{{ row.diffCount }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90" fixed="right">
            <template #default="{ row }">
              <span class="chip" :class="row.checkStatus === '已完成' ? 'c-green' : row.checkStatus === '进行中' ? 'c-amber' : 'c-gray'">{{ row.checkStatus }}</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="还没盘过钱" :image-size="46" /></template>
        </el-table>
      </div>
    </div>

    <!-- 资金调整单弹窗(建单 / 老板确认两步) -->
    <CashAdjustDialog
      v-model="adjustVisible"
      :preset-account-id="adjustPreset?.accountId"
      :preset-amount="adjustPreset?.amount"
      :preset-check-id="detail?.id"
      :pending-doc-id="pendingConfirm?.docId"
      :pending-summary="pendingConfirm?.summary"
      @done="onAdjustDone"
      @created="reload(detail?.id)"
    />
    <!-- 红冲出口:打开对应单据抽屉(自带红冲入口,只跳转不重造) -->
    <DocDetailDrawer v-model="docDrawerVisible" :doc-id="docDrawerId" />
  </div>
</template>

<style scoped>
.sec-title { margin: 14px 0 6px; font-size: 13.5px; }
.sec-title .hint { font-weight: 400; font-size: 11.5px; color: var(--ink2); margin-left: 6px }
:deep(.diff-row) { background: #fdf6ec; }
.diff-neg { color: var(--red); font-weight: 700; }
.diff-pos { color: var(--green); font-weight: 700; }
.diff-zero { color: var(--green); }
.mb-12px { margin-bottom: 12px; }
</style>
