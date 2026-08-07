<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  confirmBill, confirmPayment, createDeduction, createPayment, listBills, listDeductions,
  listPayments, redFlushPayment, resolvePaymentDiff, statementExportUrl, supplierOverview,
  supplierStatement, voidDeduction, voidPayment, type BillRow, type DeductionRow,
  type PaymentRow, type StatementResp, type SupplierOverviewRow,
} from '@/api/settle'
import { listAccounts, uploadAttachment, type AccountRow } from '@/api/money'

/**
 * 供应商往来页(M3-2,对照 mockup p8):跟谁拿货、什么价、欠多少、结了没。
 *
 * 业财一体样板流程(§9.2):入库确认 → 结算单自动生成(应结−同供应商待抵扣)→
 * 老板复核 → 付款(转账截图凭证硬门禁)→ 自动核销 → 全链闭环;金额对不上 → 差异挂起红灯。
 * 应付余额 = 期初 + Σ拿货 − Σ退货 − Σ抵扣 − Σ付款,系统实时算,不用人记。
 */

const loading = ref(false)
const suppliers = ref<SupplierOverviewRow[]>([])
const selected = ref<SupplierOverviewRow | null>(null)

const bills = ref<BillRow[]>([])
const payments = ref<PaymentRow[]>([])
const deductions = ref<DeductionRow[]>([])
const statement = ref<StatementResp | null>(null)
const detailLoading = ref(false)

async function load() {
  loading.value = true
  try {
    suppliers.value = await supplierOverview()
    if (selected.value) {
      const again = suppliers.value.find((s) => s.supplierId === selected.value!.supplierId)
      selected.value = again || null
      if (again) await loadDetail(again)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function select(row: SupplierOverviewRow) {
  selected.value = row
  await loadDetail(row)
}

async function loadDetail(row: SupplierOverviewRow) {
  detailLoading.value = true
  try {
    const [b, p, d, st] = await Promise.all([
      listBills({ supplierId: row.supplierId }),
      listPayments(row.supplierId),
      listDeductions({ supplierId: row.supplierId }),
      supplierStatement(row.supplierId),
    ])
    bills.value = b
    payments.value = p
    deductions.value = d
    statement.value = st
  } finally {
    detailLoading.value = false
  }
}

// ---------- 展示辅助 ----------
const fmt = (v: number | string | null | undefined) =>
  v == null ? '—' : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const dt = (v: string | null | undefined) => (v ? String(v).replace('T', ' ').slice(0, 16) : '—')
const day = (v: string | null | undefined) => (v ? String(v).slice(0, 10) : '—')

const billChip = (s: string) =>
  s === '已完成' || s === '已冲抵' ? 'success'
    : s === '差异挂起' ? 'danger'
      : s === '待付款' || s === '待冲抵' ? 'warning'
        : s === '已作废' ? 'info' : ''
const payChip = (s: string) =>
  s === '结算完成' ? 'success'
    : s === '差异挂起' ? 'danger'
      : s === '待付款' ? 'warning'
        : s === '已作废' || s === '已红冲' || s === '红冲' ? 'info' : ''

const pendingDeductions = computed(() => deductions.value.filter((d) => d.dedStatus === '待抵扣'))
const payableBills = computed(() => bills.value.filter((b) => b.direction === '正常' && b.billStatus === '待付款'))

// ---------- 结算单老板复核(确认点②:勾选带入同供应商待抵扣) ----------
const confirmVisible = ref(false)
const confirmTarget = ref<BillRow | null>(null)
const checkedDeds = ref<number[]>([])
function openConfirm(bill: BillRow) {
  confirmTarget.value = bill
  checkedDeds.value = pendingDeductions.value.map((d) => d.id) // 默认全勾(自动带入,可取消)
  confirmVisible.value = true
}
const confirmPreview = computed(() => {
  if (!confirmTarget.value) return null
  const due = Number(confirmTarget.value.amountDue)
  const ded = pendingDeductions.value
    .filter((d) => checkedDeds.value.includes(d.id))
    .reduce((a, d) => a + Number(d.amount), 0)
  return { due, ded, actual: due - ded }
})
async function doConfirmBill() {
  if (!confirmTarget.value) return
  try {
    const r = await confirmBill(confirmTarget.value.id, checkedDeds.value)
    ElMessage.success(
      `复核通过:应结 ${fmt(r.amountDue)} − 抵扣 ${fmt(r.deductionAmount)} − 红字 ${fmt(r.redOffsetAmount)} = 实结 ${fmt(r.amountActual)},进入待付款`,
    )
    confirmVisible.value = false
    await load()
  } catch {
    /* 报错文案已由请求拦截器弹出(如串户/角色门禁) */
  }
}

// ---------- 付款录入(三要素 + 凭证必传:选好截图才允许提交) ----------
const payVisible = ref(false)
const accounts = ref<AccountRow[]>([])
const payForm = ref<{ accountId: number | null; amount: number | null; settleBillId: number | null; remark: string }>(
  { accountId: null, amount: null, settleBillId: null, remark: '' },
)
const payFile = ref<File | null>(null)
const paySubmitting = ref(false)
async function openPay(bill?: BillRow) {
  if (!accounts.value.length) accounts.value = (await listAccounts()).filter((a) => !a.isVirtual && a.status !== 0)
  payForm.value = {
    accountId: null,
    amount: bill ? Number(bill.amountActual) : null,
    settleBillId: bill ? bill.id : null,
    remark: '',
  }
  payFile.value = null
  payVisible.value = true
}
function onPayFile(e: Event) {
  const files = (e.target as HTMLInputElement).files
  payFile.value = files && files.length ? files[0] : null
}
function onBillPick(billId: number | null) {
  const bill = payableBills.value.find((b) => b.id === billId)
  if (bill) payForm.value.amount = Number(bill.amountActual)
}
async function submitPay() {
  if (!selected.value) return
  if (!payForm.value.accountId || !payForm.value.amount) {
    ElMessage.warning('账户和金额必填(三要素:付给谁/从哪个账户/多少钱)')
    return
  }
  if (!payFile.value) {
    ElMessage.warning('转账截图必传:无凭证不能进入已付款(§9.1 凭证强制)')
    return
  }
  paySubmitting.value = true
  try {
    // 录单 → 传凭证 → 确认付款(后端 countOf 硬门禁再验一遍,不信任前端)
    const payId = await createPayment({
      supplierId: selected.value.supplierId,
      accountId: payForm.value.accountId,
      amount: payForm.value.amount,
      settleBillId: payForm.value.settleBillId,
      remark: payForm.value.remark || undefined,
    })
    await uploadAttachment('payment', payId, '转账截图', payFile.value)
    const paid = await confirmPayment(payId)
    if (paid.payStatus === '差异挂起') {
      ElMessage.warning('付款已落账,但金额≠结算单实结 → 差异挂起(红灯),请在付款记录里补说明或改单')
    } else {
      ElMessage.success('付款完成:流水已落账' + (payForm.value.settleBillId ? ',结算单已核销,全链闭环' : '(未核销,冲余额/预付)'))
    }
    payVisible.value = false
    await load()
  } catch {
    /* 报错文案已由请求拦截器弹出;半途失败(如凭证被拒)付款单留在待付款,可在列表续传凭证 */
  } finally {
    paySubmitting.value = false
  }
}

// ---------- 待付款付款单:补传凭证 + 确认;差异挂起:补说明 ----------
async function rowUpload(pay: PaymentRow, e: Event) {
  const files = (e.target as HTMLInputElement).files
  if (!files || !files.length) return
  try {
    await uploadAttachment('payment', pay.id, '转账截图', files[0])
    ElMessage.success('凭证已上传,可确认付款')
  } catch {
    /* 拦截器已弹错 */
  }
}
async function rowConfirm(pay: PaymentRow) {
  try {
    const paid = await confirmPayment(pay.id)
    if (paid.payStatus === '差异挂起') ElMessage.warning('金额≠结算单实结 → 差异挂起,请补说明或改单')
    else ElMessage.success('付款确认完成,流水已落账')
    await load()
  } catch {
    /* 无凭证等被拒:硬门禁文案已由拦截器弹出 */
  }
}
// ---------- 逆向出口(M3-9 七律修复):待付款作废 / 钱已动红冲 ----------
async function rowVoid(pay: PaymentRow) {
  try {
    const { value } = await ElMessageBox.prompt(
      `作废付款单「${pay.payNo}」?仅待付款可作废(钱没动);原因备注必填留痕。`,
      '作废付款单', { confirmButtonText: '作废', cancelButtonText: '取消', inputPlaceholder: '如:录错供应商,重录' },
    )
    if (!value) {
      ElMessage.warning('作废必须填写原因备注(留痕)')
      return
    }
    await voidPayment(pay.id, value)
    ElMessage.success('付款单已作废(留痕不删)')
    await load()
  } catch {
    /* 取消/拦截器已弹错 */
  }
}
async function rowRedFlush(pay: PaymentRow) {
  try {
    const { value } = await ElMessageBox.prompt(
      `红冲付款单「${pay.payNo}」(¥${fmt(pay.amount)})?钱已动的唯一逆向:生成负额红冲行 + 退款流水回账户` +
        `${pay.settleBillId ? ' + 结算单回待付款(可重新付款核销)' : ''};历史不改写。原因备注必填。`,
      '红冲付款单', { confirmButtonText: '确认红冲', cancelButtonText: '取消', inputPlaceholder: '如:付错对象/重复付款' },
    )
    if (!value) {
      ElMessage.warning('红冲必须填写原因备注(留痕)')
      return
    }
    await redFlushPayment(pay.id, value)
    ElMessage.success('已红冲:退款流水已落账,应付余额自动恢复' + (pay.settleBillId ? ',结算单回待付款' : ''))
    await load()
  } catch {
    /* 取消/拦截器已弹错 */
  }
}

async function rowResolve(pay: PaymentRow) {
  try {
    const { value } = await ElMessageBox.prompt(
      '凭证金额≠结算单金额(§9.2 差异处理):补说明后闭环;若是单错了请走红冲/成本调整改单。',
      '差异挂起 · 补说明', { confirmButtonText: '补说明闭环', cancelButtonText: '取消', inputPlaceholder: '如:供应商抹零 10 元' },
    )
    if (!value) return
    await resolvePaymentDiff(pay.id, value)
    ElMessage.success('差异已说明,全链闭环')
    await load()
  } catch {
    /* 取消弹窗或拦截器已弹错 */
  }
}

// ---------- 抵扣确认单 ----------
const dedVisible = ref(false)
const dedForm = ref<{ dedSource: '兑换' | '厂家补贴'; amount: number | null; periodDesc: string }>(
  { dedSource: '兑换', amount: null, periodDesc: '' },
)
const dedFile = ref<File | null>(null)
function onDedFile(e: Event) {
  const files = (e.target as HTMLInputElement).files
  dedFile.value = files && files.length ? files[0] : null
}
async function submitDeduction() {
  if (!selected.value || !dedForm.value.amount) {
    ElMessage.warning('抵扣金额必填')
    return
  }
  try {
    const id = await createDeduction({
      supplierId: selected.value.supplierId,
      dedSource: dedForm.value.dedSource,
      amount: dedForm.value.amount,
      periodDesc: dedForm.value.periodDesc || undefined,
    })
    if (dedFile.value) await uploadAttachment('deduction', id, '照片', dedFile.value)
    ElMessage.success('抵扣确认单已录入(待抵扣):下一张结算单老板复核时自动带入勾选')
    dedVisible.value = false
    dedForm.value = { dedSource: '兑换', amount: null, periodDesc: '' }
    dedFile.value = null
    await load()
  } catch {
    /* 拦截器已弹错 */
  }
}
async function doVoidDeduction(row: DeductionRow) {
  try {
    await ElMessageBox.confirm(`作废待抵扣 ${row.dedNo}(¥${fmt(row.amount)})?`, '作废抵扣', { type: 'warning' })
    await voidDeduction(row.id)
    ElMessage.success('已作废')
    await load()
  } catch {
    /* 取消或拦截器已弹错 */
  }
}

function exportStatement() {
  if (!selected.value) return
  window.open(statementExportUrl(selected.value.supplierId), '_blank')
}
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 钱账 / 供应商往来</div>
    <div class="ledger-title">
      <h2>供应商往来</h2>
      <span class="sub">跟谁拿货、什么价、欠多少、结了没 —— 每家一张卡</span>
    </div>
    <p class="ledger-note">
      应付余额 = 期初 + 拿货 − 退货 − 兑换抵扣 − 已付,<b>系统实时算,不用人记</b>;对账单一键导出发微信给供应商核对。
    </p>

    <!-- ⚡任务来源 + 编号流程条(七律#2:领着人干活) -->
    <el-alert type="info" :closable="false" class="mb-12">
      <template #title>
        <span>⚡ 任务:采购付款全链(§9.2 业财一体)——钱只能被单据改,链走完钱账自动对上</span>
        <div class="flow-bar">
          <span>① 采购入库确认(经手人)→ 结算单自动生成</span>
          <span>② 老板复核:自动带入同供应商待抵扣</span>
          <span>③ 付款:转账截图凭证必传(硬门禁)</span>
          <span>④ 金额核对通过 → 自动核销 · 全链闭环</span>
        </div>
      </template>
    </el-alert>

    <!-- 供应商卡列表(mockup p8 三张卡) -->
    <div class="cards">
      <div
        v-for="s in suppliers"
        :key="s.supplierId"
        class="mcard"
        :class="{ warn: s.overdue, idle: s.coopStatus === '停用', on: selected?.supplierId === s.supplierId }"
        @click="select(s)"
      >
        <h4>
          {{ s.supplierName }}
          <span class="chip" :class="s.coopStatus === '合作中' ? 'c-green' : 'c-gray'">{{ s.coopStatus }}</span>
          <span v-if="s.overdue" class="chip c-amber">⏰ 逾期{{ s.overdueDays }}天</span>
        </h4>
        <div class="id">{{ s.contact || '—' }} · {{ s.settleMethod }}<template v-if="s.settleMethod === '月结'">{{ s.accountDays }}天</template></div>
        <div class="mrow"><span>期初</span><b>¥{{ fmt(s.openingPayable) }}</b></div>
        <div class="mrow"><span>累计拿货</span><b>¥{{ fmt(s.purchaseTotal) }}</b></div>
        <div class="mrow"><span>兑换抵扣</span><b class="green">−¥{{ fmt(s.deductionTotal) }}</b></div>
        <div class="mrow"><span>已付</span><b>¥{{ fmt(s.paymentTotal) }}</b></div>
        <div class="mrow balance">
          <span :class="s.prepay ? 'green' : Number(s.balance) > 0 ? 'amber' : 'green'">
            {{ s.prepay ? '预付款' : '当前应付' }}
          </span>
          <b :class="s.prepay ? 'green' : Number(s.balance) > 0 ? 'amber' : 'green'">
            ¥{{ fmt(s.prepay ? -Number(s.balance) : s.balance) }}{{ Number(s.balance) === 0 ? ' ✓' : '' }}
          </b>
        </div>
        <div class="mrow mini-row">
          <span class="mini">
            <template v-if="s.pendingDeductions">🎫 待抵扣 {{ s.pendingDeductions }} 张(下张结算单自动带入)</template>
            <template v-else-if="s.pendingRedBills">🔻 红字 {{ s.pendingRedBills }} 张待冲抵</template>
          </span>
          <el-button size="small" type="primary" @click.stop="select(s).then(() => openPay())">付款</el-button>
        </div>
      </div>
      <p v-if="!loading && !suppliers.length" class="mini">
        还没有供应商档案 —— 到「设置中心 → 供应商」新增;采购入库确认后这里自动长出往来卡。
      </p>
    </div>

    <!-- 选中供应商详情:结算单链 / 付款记录 / 待抵扣 / 对账单 -->
    <template v-if="selected">
      <el-card shadow="never" class="mb-12" v-loading="detailLoading">
        <h3 class="card-h">
          🧾 {{ selected.supplierName }} · 结算单链
          <span class="hint">采购入库确认自动生成 · 老板复核带入抵扣 · 付款核销闭环;红字=红冲连锁,冲抵下一单</span>
        </h3>
        <el-table :data="bills" size="small">
          <el-table-column prop="billNo" label="结算单号" width="150" />
          <el-table-column label="方向" width="70">
            <template #default="{ row }">
              <span class="chip" :class="row.direction === '红字' ? 'c-red' : 'c-blue'">{{ row.direction }}</span>
            </template>
          </el-table-column>
          <el-table-column label="来源单据" width="150">
            <template #default="{ row }">{{ row.sourceDocNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="应结" align="right" width="100">
            <template #default="{ row }"><span class="num">{{ fmt(row.amountDue) }}</span></template>
          </el-table-column>
          <el-table-column label="抵扣−" align="right" width="90">
            <template #default="{ row }"><span class="num green">{{ Number(row.deductionAmount) ? fmt(row.deductionAmount) : '—' }}</span></template>
          </el-table-column>
          <el-table-column label="实结" align="right" width="100">
            <template #default="{ row }"><b class="num">{{ fmt(row.amountActual) }}</b></template>
          </el-table-column>
          <el-table-column label="到期日" width="100">
            <template #default="{ row }">{{ day(row.dueDate) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><el-tag size="small" :type="billChip(row.billStatus) || 'info'">{{ row.billStatus }}</el-tag></template>
          </el-table-column>
          <el-table-column label="差异说明" min-width="140">
            <template #default="{ row }"><span class="mini">{{ row.diffNote || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.direction === '正常' && row.billStatus === '待确认'" size="small" type="warning" @click="openConfirm(row)">
                老板复核确认
              </el-button>
              <el-button v-else-if="row.direction === '正常' && row.billStatus === '待付款'" size="small" type="primary" @click="openPay(row)">
                去付款
              </el-button>
            </template>
          </el-table-column>
          <template #empty><span class="mini">还没有结算单:采购入库确认后自动生成</span></template>
        </el-table>
      </el-card>

      <div class="two-col">
        <el-card shadow="never" v-loading="detailLoading">
          <h3 class="card-h">
            💸 付款记录
            <span class="hint">无转账截图不能确认(凭证硬门禁)</span>
            <el-button size="small" type="primary" style="margin-left: auto" @click="openPay()">+ 新增付款</el-button>
          </h3>
          <el-table :data="payments" size="small">
            <el-table-column prop="payNo" label="付款单号" width="140" />
            <el-table-column label="账户" width="110">
              <template #default="{ row }">{{ row.accountName || row.accountId }}</template>
            </el-table-column>
            <el-table-column label="金额" align="right" width="90">
              <template #default="{ row }"><b class="num">{{ fmt(row.amount) }}</b></template>
            </el-table-column>
            <el-table-column label="核销单" width="130">
              <template #default="{ row }">{{ row.billNo || '(冲余额)' }}</template>
            </el-table-column>
            <el-table-column label="付款时间" width="130">
              <template #default="{ row }">{{ dt(row.payTime) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }"><el-tag size="small" :type="payChip(row.payStatus) || 'info'">{{ row.payStatus }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" min-width="220">
              <template #default="{ row }">
                <template v-if="row.payStatus === '待付款'">
                  <input type="file" accept="image/*,.pdf" class="file-mini" @change="rowUpload(row, $event)" />
                  <el-button size="small" type="primary" @click="rowConfirm(row)">确认付款</el-button>
                  <el-button size="small" @click="rowVoid(row)">作废</el-button>
                </template>
                <template v-else-if="row.payStatus === '差异挂起'">
                  <el-button size="small" type="danger" @click="rowResolve(row)">补说明闭环</el-button>
                  <el-button size="small" @click="rowRedFlush(row)">红冲</el-button>
                </template>
                <el-button
                  v-else-if="(row.payStatus === '已付款' || row.payStatus === '结算完成') && !row.redFlushOf"
                  size="small"
                  @click="rowRedFlush(row)"
                >
                  红冲
                </el-button>
                <span v-else-if="row.payStatus === '已红冲'" class="mini">已被红冲(负额行承接)</span>
                <span v-else-if="row.payStatus === '红冲'" class="mini">红冲行 → 原单 #{{ row.redFlushOf }}</span>
              </template>
            </el-table-column>
            <template #empty><span class="mini">暂无付款记录</span></template>
          </el-table>
        </el-card>

        <el-card shadow="never" v-loading="detailLoading">
          <h3 class="card-h">
            🎫 抵扣确认单
            <span class="hint">兑换/厂家补贴 → 待抵扣 → 进下张结算单(同供应商防串户)</span>
            <el-button size="small" style="margin-left: auto" @click="dedVisible = true">+ 录入抵扣</el-button>
          </h3>
          <el-table :data="deductions" size="small">
            <el-table-column prop="dedNo" label="单号" width="140" />
            <el-table-column prop="dedSource" label="来源" width="90" />
            <el-table-column label="金额" align="right" width="90">
              <template #default="{ row }"><b class="num green">{{ fmt(row.amount) }}</b></template>
            </el-table-column>
            <el-table-column label="状态" min-width="150">
              <template #default="{ row }">
                <el-tag size="small" :type="row.dedStatus === '待抵扣' ? 'warning' : row.dedStatus === '已抵扣' ? 'success' : 'info'">
                  {{ row.dedStatus === '已抵扣' ? `已用于结算单#${row.usedSettleBillId}` : row.dedStatus }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="120">
              <template #default="{ row }"><span class="mini">{{ row.periodDesc || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button v-if="row.dedStatus === '待抵扣'" size="small" text type="danger" @click="doVoidDeduction(row)">作废</el-button>
              </template>
            </el-table-column>
            <template #empty><span class="mini">暂无抵扣:厂家兑换结账后在这里录入,别再靠脑子记</span></template>
          </el-table>
        </el-card>
      </div>

      <el-card shadow="never" class="mb-12" v-loading="detailLoading">
        <h3 class="card-h">
          📄 {{ selected.supplierName }} · 往来对账单
          <span class="hint">期初 + 拿货 − 抵扣 − 付款 = 期末 · 一键导出发微信</span>
          <el-button size="small" style="margin-left: auto" @click="exportStatement">🖨 导出对账单</el-button>
        </h3>
        <el-table v-if="statement" :data="statement.lines" size="small">
          <el-table-column label="日期" width="110">
            <template #default="{ row }">{{ day(row.bizDate) }}</template>
          </el-table-column>
          <el-table-column label="事项" min-width="220">
            <template #default="{ row }">
              {{ row.lineType }} {{ row.refNo }}<span v-if="row.remark" class="mini"> · {{ row.remark }}</span>
              <span v-if="row.accountName" class="mini">({{ row.accountName }})</span>
            </template>
          </el-table-column>
          <el-table-column label="拿货 +" align="right" width="110">
            <template #default="{ row }"><span class="num">{{ row.lineType.startsWith('拿货') ? fmt(row.amount) : '' }}</span></template>
          </el-table-column>
          <el-table-column label="抵扣 −" align="right" width="110">
            <template #default="{ row }"><span class="num green">{{ row.lineType === '抵扣' ? fmt(row.amount) : '' }}</span></template>
          </el-table-column>
          <el-table-column label="退货/付款 −" align="right" width="110">
            <template #default="{ row }"><span class="num">{{ row.lineType !== '抵扣' && !row.lineType.startsWith('拿货') ? fmt(row.amount) : '' }}</span></template>
          </el-table-column>
          <el-table-column label="余额" align="right" width="120">
            <template #default="{ row }"><b class="num" :class="Number(row.balance) > 0 ? 'amber' : 'green'">{{ fmt(row.balance) }}</b></template>
          </el-table-column>
        </el-table>
        <p v-if="statement" class="mini stmt-foot">
          期初 ¥{{ fmt(statement.opening) }} → 期末 <b :class="Number(statement.closing) > 0 ? 'amber' : 'green'">¥{{ fmt(statement.closing) }}</b>
          <template v-if="Number(statement.closing) < 0">(负数=预付款)</template>
        </p>
      </el-card>
    </template>
    <p v-else class="ledger-foot-note">— 点一张供应商卡,查看结算单链 / 付款记录 / 待抵扣 / 对账单 —</p>

    <!-- 结算单老板复核弹窗(确认点②) -->
    <el-dialog v-model="confirmVisible" title="老板复核确认结算单(确认点② · 与入库经手人不同人)" width="560px">
      <template v-if="confirmTarget">
        <p class="mini">
          结算单 {{ confirmTarget.billNo }}(来源 {{ confirmTarget.sourceDocNo }}):
          应结 = 实收入库金额 ¥{{ fmt(confirmTarget.amountDue) }};勾选要带入的<b>同供应商</b>待抵扣(自动带入,可取消)。
        </p>
        <el-checkbox-group v-model="checkedDeds" class="ded-list">
          <el-checkbox v-for="d in pendingDeductions" :key="d.id" :value="d.id">
            {{ d.dedNo }} · {{ d.dedSource }} ¥{{ fmt(d.amount) }}<span v-if="d.periodDesc" class="mini">({{ d.periodDesc }})</span>
          </el-checkbox>
        </el-checkbox-group>
        <p v-if="!pendingDeductions.length" class="mini">该供应商暂无待抵扣。</p>
        <el-alert v-if="confirmPreview" type="success" :closable="false">
          ¥{{ fmt(confirmPreview.due) }} − 抵扣 ¥{{ fmt(confirmPreview.ded) }} = 实结 <b>¥{{ fmt(confirmPreview.actual) }}</b>
          <span class="mini">(如有在挂红字将再自动冲抵,以确认结果为准 · §9.2 样板:2254 − 351.63)</span>
        </el-alert>
      </template>
      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="warning" @click="doConfirmBill">✓ 老板确认,进入待付款</el-button>
      </template>
    </el-dialog>

    <!-- 付款录入弹窗(三要素 + 凭证必传) -->
    <el-dialog v-model="payVisible" title="付款(转账截图必传 · 无凭证不能进已付款)" width="520px">
      <el-form label-width="120px">
        <el-form-item label="付给谁">
          <b>{{ selected?.supplierName }}</b>
        </el-form-item>
        <el-form-item label="从哪个账户" required>
          <el-select v-model="payForm.accountId" placeholder="选真实账户(虚拟账户不可付款)" style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :value="a.id" :label="`${a.accountName}(¥${fmt(a.balance)})`" />
          </el-select>
        </el-form-item>
        <el-form-item label="核销结算单">
          <el-select v-model="payForm.settleBillId" clearable placeholder="可选:不选=预付/冲余额" style="width: 100%" @change="onBillPick">
            <el-option v-for="b in payableBills" :key="b.id" :value="b.id" :label="`${b.billNo} 实结 ¥${fmt(b.amountActual)}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="多少钱" required>
          <el-input-number v-model="payForm.amount" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="转账截图" required>
          <input type="file" accept="image/*,.pdf" @change="onPayFile" />
          <span class="mini">凭证强制(§9.1):微信转账后截图传这里</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="payForm.remark" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="payVisible = false">取消</el-button>
        <el-button type="primary" :loading="paySubmitting" @click="submitPay">💸 付款并确认(自动核销)</el-button>
      </template>
    </el-dialog>

    <!-- 抵扣录入弹窗 -->
    <el-dialog v-model="dedVisible" title="录入抵扣确认单(厂家结账凭证)" width="480px">
      <el-form label-width="110px">
        <el-form-item label="供应商">
          <b>{{ selected?.supplierName }}</b>
          <span class="mini">(必填防串户:只进本供应商的结算单)</span>
        </el-form-item>
        <el-form-item label="来源" required>
          <el-radio-group v-model="dedForm.dedSource">
            <el-radio value="兑换">兑换</el-radio>
            <el-radio value="厂家补贴">厂家补贴</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="抵扣金额" required>
          <el-input-number v-model="dedForm.amount" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="活动说明">
          <el-input v-model="dedForm.periodDesc" placeholder="如:7月兑换活动 厂家已结账 351.63" />
        </el-form-item>
        <el-form-item label="厂家凭证">
          <input type="file" accept="image/*,.pdf" @change="onDedFile" />
          <span class="mini">厂家结账确认单截图/照片</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dedVisible = false">取消</el-button>
        <el-button type="primary" @click="submitDeduction">保存(状态:待抵扣)</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}
.flow-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 6px;
  font-size: 12px;
}
.flow-bar span {
  background: #eef3ec;
  border-radius: 6px;
  padding: 3px 10px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.mcard {
  background: #fffdf8;
  border: 1px solid #e8e2d2;
  border-radius: 12px;
  padding: 14px 16px;
  cursor: pointer;
  transition: box-shadow 0.15s;
}
.mcard:hover {
  box-shadow: 0 3px 12px rgba(60, 50, 20, 0.1);
}
.mcard.on {
  border-color: var(--green, #4a7c59);
  box-shadow: 0 0 0 2px rgba(74, 124, 89, 0.25);
}
.mcard.warn {
  border-color: var(--amber, #c98a2b);
}
.mcard.idle {
  opacity: 0.72;
}
.mcard h4 {
  margin: 0 0 2px;
  font-size: 15px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.mcard .id {
  font-size: 12px;
  color: #8b8370;
  margin-bottom: 8px;
}
.mrow {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  padding: 2px 0;
}
.mrow.balance {
  border-top: 1px dashed #e0d9c6;
  margin-top: 4px;
  padding-top: 6px;
  font-weight: 700;
}
.mrow.mini-row {
  align-items: center;
  margin-top: 6px;
}
.green {
  color: var(--green, #4a7c59);
}
.amber {
  color: var(--amber, #c98a2b);
}
.card-h {
  font-family: var(--serif);
  margin: 0 0 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.card-h .hint {
  font-size: 12px;
  color: #9a927d;
  font-weight: 400;
}
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 12px;
}
@media (max-width: 1100px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}
.ded-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 8px 0;
}
.file-mini {
  max-width: 150px;
  font-size: 11px;
}
.stmt-foot {
  margin: 10px 2px 0;
  font-size: 13px;
}
</style>
