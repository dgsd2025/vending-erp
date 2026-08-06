<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  confirmSettlementBill,
  createSettlementBill,
  exchangeRoi,
  listSettlementBills,
  resolveSettlementDiff,
  settlementOverview,
  voidSettlementBill,
  type BillRow,
  type ExchangeRoi,
  type SettlementOverview,
} from '@/api/settlement'
import { listAccounts, uploadAttachment, type AccountRow } from '@/api/money'

/**
 * 平台结算面板(M3-3,附录D 双模式,模式感知三态 UI):
 * - UNSET:待核实横幅 + 两模式假设对比预览(标非正式)+ 定型入口跳设置中心;
 * - PLATFORM:待结算余额 → 录平台结算单 → 传平台账单凭证(必传)→ 确认(两差红绿灯);
 * - DIRECT:无虚账说明 → 商户账单核对单(只对差不动钱)。
 * 底部:兑换活动 ROI(兑换出货成本 vs 厂家补贴确认额,Scenario04 对冲闭环)。
 */

const router = useRouter()

const overview = ref<SettlementOverview | null>(null)
const bills = ref<BillRow[]>([])
const accounts = ref<AccountRow[]>([])
const roi = ref<ExchangeRoi | null>(null)
const loading = ref(false)

const mode = computed(() => overview.value?.mode ?? 'UNSET')
const isPlatform = computed(() => mode.value === 'PLATFORM')
const isDirect = computed(() => mode.value === 'DIRECT')
const isUnset = computed(() => mode.value === 'UNSET')

function fmt(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '—'
  const n = Number(v)
  return Number.isNaN(n) ? String(v) : n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function load() {
  loading.value = true
  try {
    const [ov, rows, r] = await Promise.all([settlementOverview(), listSettlementBills(), exchangeRoi()])
    overview.value = ov
    bills.value = rows
    roi.value = r
    if (ov.mode === 'PLATFORM') {
      accounts.value = (await listAccounts()).filter((a) => !a.isVirtual && a.status === 1)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

function gotoSettings() {
  router.push({ path: '/settings', query: { tab: 'account' } })
}

// ============ 录入(模式感知) ============

const createVisible = ref(false)
const creating = ref(false)
const form = reactive({
  periodStart: '',
  periodEnd: '',
  platformAmount: null as number | null,
  feeAmount: null as number | null,
  actualAmount: null as number | null,
  accountId: null as number | null,
})

function openCreate() {
  createVisible.value = true
}

const expectedArrival = computed(() => {
  if (form.platformAmount == null || form.feeAmount == null) return null
  return form.platformAmount - form.feeAmount
})

async function doCreate() {
  if (!form.periodStart || !form.periodEnd) {
    ElMessage.warning('请选结算区间')
    return
  }
  if (form.platformAmount == null) {
    ElMessage.warning(isPlatform.value ? '请填平台账单销售额' : '请填商户账单销售额')
    return
  }
  if (isPlatform.value) {
    if (form.feeAmount == null) {
      ElMessage.warning('平台手续费必填(可为 0)——报表要单列「平台吃掉多少钱」')
      return
    }
    if (form.actualAmount == null) {
      ElMessage.warning('实际到账必填:以银行/微信账单为准')
      return
    }
    if (!form.accountId) {
      ElMessage.warning('请选入账账户(钱到了哪个真实账户)')
      return
    }
  }
  creating.value = true
  try {
    const id = await createSettlementBill({
      periodStart: form.periodStart,
      periodEnd: form.periodEnd,
      platformAmount: form.platformAmount,
      feeAmount: isPlatform.value ? form.feeAmount : undefined,
      actualAmount: isPlatform.value ? form.actualAmount : undefined,
      accountId: isPlatform.value ? form.accountId : undefined,
    })
    ElMessage.success(
      isPlatform.value
        ? `平台结算单已录入 #${id}(待核对)——传平台账单凭证后点「确认核销」`
        : `商户账单核对单已录入 #${id}(待核对)——点「确认对差」出差异报告`,
    )
    createVisible.value = false
    form.platformAmount = null
    form.feeAmount = null
    form.actualAmount = null
    load()
  } finally {
    creating.value = false
  }
}

// ============ 凭证 + 确认 ============

const uploadingId = ref<number | null>(null)
const confirmingId = ref<number | null>(null)

async function onVoucherPick(row: BillRow, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploadingId.value = row.id
  try {
    await uploadAttachment('settlement', row.id, '平台账单', file)
    ElMessage.success('平台账单凭证已上传(可确认核销)')
    load()
  } finally {
    uploadingId.value = null
  }
}

async function doConfirm(row: BillRow) {
  if (row.modeSnap === 'PLATFORM' && !row.attachmentCount) {
    ElMessage.warning(`「${row.stmtNo}」还没传平台账单凭证——必传(硬门禁,后端也会拒)`)
    return
  }
  confirmingId.value = row.id
  try {
    const r = await confirmSettlementBill(row.id)
    if (r.mode === 'PLATFORM') {
      ElMessage.success(
        `已核销:系统 ¥${fmt(r.systemAmount)} vs 账单,漏单差 ${fmt(r.diffSales)}${r.salesDiffOk ? '🟢' : '🔴'} · ` +
          `扣款差 ${fmt(r.diffArrival)}${r.arrivalDiffOk ? '🟢' : '🔴'};回填 ${r.backfillCount} 笔销售,落流水 ${r.flowCount} 条` +
          (r.stlStatus === '差异挂起' ? '——差异超阈值,请复核' : ''),
      )
    } else {
      ElMessage.success(
        `已核对:系统 ¥${fmt(r.systemAmount)} vs 商户账单,差 ${fmt(r.diffSales)}${r.salesDiffOk ? '🟢' : '🔴'}` +
          '(直连模式只对差不动钱,到账核对走月度钱盘)',
      )
    }
    load()
  } finally {
    confirmingId.value = null
  }
}

async function doResolve(row: BillRow) {
  try {
    const { value } = await ElMessageBox.prompt(
      `「${row.stmtNo}」漏单差 ¥${fmt(row.diffSales)} / 扣款差 ¥${fmt(row.diffArrival)}:核实成什么原因了?(费率变化/漏单/吞货/额外扣款…必填留痕)`,
      '差异复核收口',
      { confirmButtonText: '收口', cancelButtonText: '取消', inputPlaceholder: '例:平台活动补贴扣款 5 元' },
    )
    await resolveSettlementDiff(row.id, value || '')
    ElMessage.success('差异已复核收口')
    load()
  } catch {
    /* 取消/后端已弹错 */
  }
}

async function doVoid(row: BillRow) {
  try {
    await ElMessageBox.confirm(`作废「${row.stmtNo}」?仅待核对可作废(改模式后的旧单走这里重录)`, '作废', {
      type: 'warning',
    })
    await voidSettlementBill(row.id)
    ElMessage.success('已作废')
    load()
  } catch {
    /* 取消/后端已弹错 */
  }
}

function statusTag(s: string): string {
  if (s === '已核销' || s === '已核对') return 'success'
  if (s === '差异挂起') return 'danger'
  if (s === '已作废') return 'info'
  return 'warning'
}
</script>

<template>
  <div v-loading="loading">
    <!-- ⚡ 任务来源 + 编号流程条(七律#2) -->
    <el-alert type="info" :closable="false" class="mb-3">
      <template #title>
        ⚡ 卖出去的钱收到了没有?(§7.3 问3)——
        <template v-if="isPlatform">①每月导出货明细 → ②平台打款后录结算单 → ③传平台账单凭证 → ④确认:两差对账+回填在途货款</template>
        <template v-else-if="isDirect">①每月导出货明细 → ②录商户账单核对单 → ③确认对差(钱已直连入账,只查漏单)</template>
        <template v-else>先核实结算模式(追一笔真实交易),再回来记账</template>
      </template>
    </el-alert>

    <!-- ========== UNSET:横幅 + 假设对比预览 ========== -->
    <template v-if="isUnset">
      <el-alert type="warning" :closable="false" class="mb-3" data-test="unset-banner">
        <template #title>{{ overview?.banner }}</template>
      </el-alert>
      <div class="hyp-grid">
        <el-card v-for="h in overview?.hypothesis || []" :key="h.mode" shadow="never">
          <div class="hyp-title">
            {{ h.title }}
            <el-tag v-if="h.informal" type="warning" size="small">非正式 · 假设对比</el-tag>
          </div>
          <div class="hyp-amount">¥{{ fmt(h.amount) }}</div>
          <div class="hyp-explain">{{ h.explain }}</div>
        </el-card>
      </div>
      <el-button type="primary" class="mt-3" @click="gotoSettings">去设置中心定型结算模式 →</el-button>
    </template>

    <!-- ========== PLATFORM / DIRECT ========== -->
    <template v-else>
      <!-- 顶部数字 -->
      <div class="stat-row">
        <el-card v-if="isPlatform" shadow="never" class="stat-card">
          <div class="stat-label">平台待结算(在途货款)</div>
          <div class="stat-value">¥{{ fmt(overview?.pendingBalance) }}</div>
          <div class="stat-sub">
            {{ overview?.pendingCount ?? 0 }} 笔未回填
            <template v-if="overview?.pendingOldest">· 最早 {{ overview?.pendingOldest }}</template>
          </div>
        </el-card>
        <el-card v-else shadow="never" class="stat-card">
          <div class="stat-label">微信/支付宝直连</div>
          <div class="stat-sub" data-test="direct-note">{{ overview?.directNote }}</div>
        </el-card>
        <el-button type="primary" @click="openCreate">
          {{ isPlatform ? '＋ 录平台结算单' : '＋ 录商户账单核对单' }}
        </el-button>
      </div>

      <!-- 单据表(两差红绿灯) -->
      <el-table :data="bills" size="small" class="mt-3">
        <el-table-column prop="stmtNo" label="单号" width="150" />
        <el-table-column label="区间" width="180">
          <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
        </el-table-column>
        <el-table-column label="账单额" align="right" width="100">
          <template #default="{ row }">¥{{ fmt(row.platformAmount) }}</template>
        </el-table-column>
        <el-table-column v-if="isPlatform" label="手续费" align="right" width="90">
          <template #default="{ row }">¥{{ fmt(row.feeAmount) }}</template>
        </el-table-column>
        <el-table-column v-if="isPlatform" label="实际到账" align="right" width="100">
          <template #default="{ row }">¥{{ fmt(row.actualAmount) }}</template>
        </el-table-column>
        <el-table-column label="系统额" align="right" width="100">
          <template #default="{ row }">{{ row.confirmAt ? '¥' + fmt(row.systemAmount) : '—' }}</template>
        </el-table-column>
        <el-table-column label="漏单差①" align="right" width="110">
          <template #default="{ row }">
            <template v-if="row.salesDiffOk != null">
              <span :class="row.salesDiffOk ? 'light-ok' : 'light-bad'">{{ row.salesDiffOk ? '🟢' : '🔴' }} {{ fmt(row.diffSales) }}</span>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="isPlatform" label="扣款差②" align="right" width="110">
          <template #default="{ row }">
            <template v-if="row.arrivalDiffOk != null">
              <span :class="row.arrivalDiffOk ? 'light-ok' : 'light-bad'">{{ row.arrivalDiffOk ? '🟢' : '🔴' }} {{ fmt(row.diffArrival) }}</span>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.stlStatus)" size="small">{{ row.stlStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="230">
          <template #default="{ row }">
            <template v-if="row.stlStatus === '待核对'">
              <label v-if="row.modeSnap === 'PLATFORM'" class="voucher-btn">
                <input type="file" accept=".jpg,.jpeg,.png,.webp,.pdf,.xlsx,.csv" hidden @change="onVoucherPick(row, $event)" />
                <el-button size="small" :loading="uploadingId === row.id" tag="span">
                  📎 传账单凭证{{ row.attachmentCount ? `(${row.attachmentCount})` : '(必传)' }}
                </el-button>
              </label>
              <el-button size="small" type="primary" :loading="confirmingId === row.id" @click="doConfirm(row)">
                {{ row.modeSnap === 'PLATFORM' ? '确认核销' : '确认对差' }}
              </el-button>
              <el-button size="small" @click="doVoid(row)">作废</el-button>
            </template>
            <el-button v-else-if="row.stlStatus === '差异挂起'" size="small" type="danger" @click="doResolve(row)">
              复核收口
            </el-button>
            <span v-else-if="row.diffNote" class="diff-note" :title="row.diffNote">📝 {{ row.diffNote }}</span>
            <span v-else class="diff-note">{{ row.confirmBy ? `经手:${row.confirmBy}` : '' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- ========== 兑换活动 ROI(三态都展示) ========== -->
    <el-card shadow="never" class="mt-3" data-test="roi-card">
      <div class="hyp-title">🎁 兑换活动 ROI——兑换出货成本由厂家补贴对冲(P0-3)</div>
      <div class="roi-row" v-if="roi">
        <span>兑换出货成本 <b>¥{{ fmt(roi.exchangeCost) }}</b>({{ fmt(roi.exchangeQty) }} 件)</span>
        <span>厂家补贴确认额 <b>¥{{ fmt(roi.subsidyConfirmed) }}</b>(已抵扣 ¥{{ fmt(roi.subsidyUsed) }} / 待抵扣 ¥{{ fmt(roi.subsidyPending) }})</span>
        <span :class="Number(roi.net) >= 0 ? 'light-ok' : 'light-bad'">
          净对冲 {{ Number(roi.net) >= 0 ? '🟢' : '🔴' }} ¥{{ fmt(roi.net) }}
        </span>
        <el-tag v-if="roi.costMissingCount > 0" type="warning" size="small">
          {{ roi.costMissingCount }} 行成本待补(无采购史,不按 0 硬算)
        </el-tag>
      </div>
    </el-card>

    <!-- ========== 录入弹窗(模式感知) ========== -->
    <el-dialog v-model="createVisible" :title="isPlatform ? '录平台结算单' : '录商户账单核对单'" width="520px">
      <el-form label-width="110px">
        <el-form-item label="结算区间" required>
          <el-date-picker v-model="form.periodStart" type="date" value-format="YYYY-MM-DD" placeholder="起" style="width: 46%" />
          <span style="margin: 0 4px">~</span>
          <el-date-picker v-model="form.periodEnd" type="date" value-format="YYYY-MM-DD" placeholder="止" style="width: 46%" />
        </el-form-item>
        <el-form-item :label="isPlatform ? '平台账单额' : '商户账单额'" required>
          <el-input-number v-model="form.platformAmount" :min="0" :precision="2" :controls="false" style="width: 100%" placeholder="平台/商户后台账单上的销售额" />
        </el-form-item>
        <template v-if="isPlatform">
          <el-form-item label="平台手续费" required>
            <el-input-number v-model="form.feeAmount" :min="0" :precision="2" :controls="false" style="width: 100%" placeholder="可为 0;报表单列「平台吃掉多少钱」" />
          </el-form-item>
          <el-form-item label="实际到账" required>
            <el-input-number v-model="form.actualAmount" :min="0" :precision="2" :controls="false" style="width: 100%" placeholder="以银行/微信账单为准" />
            <div v-if="expectedArrival != null" class="stat-sub">预计到账 = 账单 − 手续费 = ¥{{ fmt(expectedArrival) }}</div>
          </el-form-item>
          <el-form-item label="入账账户" required>
            <el-select v-model="form.accountId" placeholder="钱到了哪个真实账户" style="width: 100%">
              <el-option v-for="a in accounts" :key="a.id" :label="`${a.accountName}(${a.accountType})`" :value="a.id" />
            </el-select>
          </el-form-item>
          <el-alert type="info" :closable="false">
            <template #title>录入后请在列表行传「平台账单凭证」——无凭证不能核销(§9.1 硬门禁)</template>
          </el-alert>
        </template>
        <el-alert v-else type="info" :closable="false">
          <template #title>直连模式只对差不动钱:钱已直接进商户号,真实到账在「月度钱盘」核对,避免双记</template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">录入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mb-3 { margin-bottom: 12px; }
.mt-3 { margin-top: 12px; }
.hyp-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; }
.hyp-title { font-weight: 600; margin-bottom: 8px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.hyp-amount { font-size: 26px; font-weight: 700; margin: 4px 0; }
.hyp-explain { color: var(--el-text-color-secondary); font-size: 13px; }
.stat-row { display: flex; gap: 12px; align-items: flex-start; flex-wrap: wrap; }
.stat-card { min-width: 280px; flex: 1; }
.stat-label { color: var(--el-text-color-secondary); font-size: 13px; }
.stat-value { font-size: 26px; font-weight: 700; margin: 4px 0; }
.stat-sub { color: var(--el-text-color-secondary); font-size: 12px; }
.light-ok { color: var(--el-color-success); }
.light-bad { color: var(--el-color-danger); font-weight: 600; }
.voucher-btn { display: inline-block; margin-right: 8px; }
.diff-note { color: var(--el-text-color-secondary); font-size: 12px; }
.roi-row { display: flex; gap: 20px; flex-wrap: wrap; align-items: center; font-size: 13px; }
</style>
