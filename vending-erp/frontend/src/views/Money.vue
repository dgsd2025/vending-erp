<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import SettlementPanel from '@/components/money/SettlementPanel.vue'
import ClaimPanel from '@/components/money/ClaimPanel.vue'
import ExpensePanel from '@/components/money/ExpensePanel.vue'
import OfflineSaleDialog from '@/components/money/OfflineSaleDialog.vue'
import {
  listAccounts,
  pageFlows,
  plSummary,
  type AccountRow,
  type FlowRow,
  type PageResult,
} from '@/api/money'
import type { BillRow as SettlementBillRow, SettlementOverview } from '@/api/settlement'

/**
 * 资金与对账页(M3-7,对照 mockup p7):钱在哪、进了多少、出了多少、平台吃掉多少——一页看清。
 * 装配已有组件,不造新轮子:
 * ① 账户余额卡行(api/money.listAccounts,真实+虚拟)+ 本月平台扣费(pl-summary);
 * ② 平台结算对账(SettlementPanel,M3-3 三态:UNSET 横幅/PLATFORM 核销/DIRECT 对差)+ 五步流程条;
 * ③ 流水账(pageFlows 分页 + 账户/类别/记账期筛选;只读——钱只能被单据改);
 * ④ 索赔(ClaimPanel)/ ⑤ 支出与设备(ExpensePanel)/ ⑥ 线下收入复合单(OfflineSaleDialog 入口);
 * ⑦ 月度钱盘三核对 = 跳盘点页 SOP(CashCheckPanel 的正式家在那);⑧ AI 资金洞察灰位(M4)。
 */

const router = useRouter()

// ============ ① 账户余额卡行 ============

const accounts = ref<AccountRow[]>([])
const accountsLoading = ref(false)
/** 本月平台扣费(pl-summary 的「平台手续费」行,mockup 第 4 张卡) */
const feeThisMonth = ref<number | null>(null)

const curPeriod = new Date().toISOString().slice(0, 7)

/** 卡行顺序:真实启用账户 → 虚拟账户(平台待结算带「在途」chip) */
const cardAccounts = computed(() => {
  const act = accounts.value.filter((a) => a.status === 1)
  return [...act.filter((a) => !a.isVirtual), ...act.filter((a) => a.isVirtual)]
})

/** 每类账户一句人话(七律#1:每个数字配人话翻译) */
function humanOf(a: AccountRow): string {
  if (a.accountType === '平台待结算') return '卖出去、平台还没打给我们的钱(录结算单核销)'
  if (a.accountType === '老板垫付') return '老板私人垫的钱,系统替你记着'
  if (a.accountType === '现金') return '机器故障线下收款等零星收入'
  return '平台结算款到这里;付货款也从这里出'
}

async function loadAccounts() {
  accountsLoading.value = true
  try {
    accounts.value = await listAccounts()
  } finally {
    accountsLoading.value = false
  }
}

async function loadFee() {
  try {
    const rows = await plSummary({ fromPeriod: curPeriod, toPeriod: curPeriod })
    const fee = rows.find((r) => r.plLine === '平台手续费')
    feeThisMonth.value = fee ? Number(fee.outflow) : 0
  } catch {
    feeThisMonth.value = null // 取不到不误报 0
  }
}

// ============ ② 结算流程条(M3-9 七律修复:按真实单据状态点亮,不再写死) ============

const settleOverview = ref<SettlementOverview | null>(null)
const settleBills = ref<SettlementBillRow[]>([])

/** SettlementPanel 每次加载/操作后回传真实数据 → 流程条重算 */
function onSettlementChanged(payload: { overview: SettlementOverview | null; bills: SettlementBillRow[] }) {
  settleOverview.value = payload.overview
  settleBills.value = payload.bills
}

interface FlowStep {
  label: string
  mini: string
  state: '' | 'done' | 'cur'
}

/**
 * 真实流程(实现=手工录入,不是"系统预生成"):
 * ①录结算单 → ②传平台账单凭证 → ③确认核销(两差自动算) → ④差异复核收口 → ⑤完成。
 * 状态推导:取最新一张进行中单(待核对/差异挂起);没有进行中单时,近期有已核销=全部 done。
 */
const flowSteps = computed<FlowStep[]>(() => {
  const mode = settleOverview.value?.mode
  if (mode !== 'PLATFORM' && mode !== 'DIRECT') return []
  const platform = mode === 'PLATFORM'
  const steps: FlowStep[] = platform
    ? [
        { label: '① 录平台结算单', mini: '平台打款后手工录:区间+平台数+到账数', state: '' },
        { label: '② 传平台账单凭证', mini: '必传 · 无凭证不能核销', state: '' },
        { label: '③ 确认核销', mini: '漏单差①/扣款差② 自动算', state: '' },
        { label: '④ 差异复核收口', mini: '超阈值挂起 · 说明必填', state: '' },
        { label: '⑤ 完成', mini: '写流水 · 清待结算', state: '' },
      ]
    : [
        { label: '① 录商户账单核对单', mini: '按月手工录:区间+商户账单额', state: '' },
        { label: '② 确认对差', mini: '只对漏单差 · 不动钱', state: '' },
        { label: '③ 差异收口', mini: '超阈值挂起 · 说明必填', state: '' },
      ]
  const active = settleBills.value.find((b) => b.stlStatus === '待核对' || b.stlStatus === '差异挂起')
  const doneOne = settleBills.value.some((b) => b.stlStatus === '已核销' || b.stlStatus === '已核对')
  /** 点亮到第 n 步 done、第 cur 步 cur */
  const light = (doneCount: number, cur: number | null) => {
    steps.forEach((s, i) => {
      s.state = i < doneCount ? 'done' : i === cur ? 'cur' : ''
    })
  }
  if (!active) {
    if (doneOne) light(steps.length, null) // 最近周期已走完
    else light(0, 0) // 还没录过单 → 从第①步开始
    return steps
  }
  if (active.stlStatus === '差异挂起') {
    light(platform ? 3 : 2, platform ? 3 : 2)
    return steps
  }
  // 待核对
  if (platform) {
    if (!active.attachmentCount) light(1, 1) // 已录单,凭证未传
    else light(2, 2) // 凭证已传,待确认核销
  } else {
    light(1, 1)
  }
  return steps
})

// ============ ③ 流水账(只读 + 分页筛选) ============

/** 类别选项 = 后端 CashFlowCategory 枚举落库中文值(一一对应,不自造) */
const FLOW_CATEGORIES = [
  '销售收款', '销售成本', '平台手续费', '供应商付款', '货款结算',
  '索赔到账', '线下收入', '兑换收款', '电费', '维修', '杂支',
  '设备购置', '损耗', '成本调整', '账户互转', '资金调整',
]

const flowPage = ref<PageResult<FlowRow> | null>(null)
const flowLoading = ref(false)
const filter = reactive({
  accountId: null as number | null,
  category: '' as string,
  fromPeriod: '' as string,
  toPeriod: '' as string,
})
const current = ref(1)
const pageSize = ref(20)

async function loadFlows() {
  flowLoading.value = true
  try {
    flowPage.value = await pageFlows({
      current: current.value,
      size: pageSize.value,
      accountId: filter.accountId ?? undefined,
      category: filter.category || undefined,
      fromPeriod: filter.fromPeriod || undefined,
      toPeriod: filter.toPeriod || undefined,
    })
  } finally {
    flowLoading.value = false
  }
}

function resetAndLoad() {
  current.value = 1
  loadFlows()
}

function categoryChipClass(c: string): string {
  if (['销售收款', '索赔到账', '线下收入', '兑换收款'].includes(c)) return 'c-green'
  if (['供应商付款', '平台手续费', '电费', '维修', '杂支', '设备购置', '损耗'].includes(c)) return 'c-red'
  if (['货款结算', '账户互转', '资金调整'].includes(c)) return 'c-blue'
  return 'c-gray'
}

// ============ ⑥ 线下收入复合单入口 ============

const offlineVisible = ref(false)

/** 复合单落一条「线下收入」流水 → 刷流水与账户余额 */
function onOfflineCreated() {
  loadFlows()
  loadAccounts()
}

function money(v: number | string | null | undefined): string {
  if (v == null) return '—'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

onMounted(() => {
  loadAccounts()
  loadFee()
  loadFlows()
})
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / 钱账 / 资金与对账</div>
    <div class="ledger-title">
      <h2>资金与对账</h2>
      <span class="sub">钱在哪、进了多少、出了多少、平台吃了多少 —— 一页看清</span>
    </div>
    <p class="ledger-note">
      规矩只有一条:<b>每一笔钱的进出都有一行流水,每行流水都能追到单据</b>。应付、待收都是系统算的,不用人记。
    </p>

    <!-- ① 账户余额卡行(mockup g4:真实账户 + 平台待结算「在途」 + 本月平台扣费) -->
    <div class="acct-grid" v-loading="accountsLoading" data-block="account-cards">
      <div v-for="a in cardAccounts" :key="a.id" class="ledger-card stat">
        <div class="lb">
          {{ a.accountName }}
          <span v-if="a.accountType === '平台待结算'" class="chip c-amber">在途</span>
          <span v-else-if="a.isVirtual" class="chip c-gray">虚拟</span>
          <span v-else class="chip c-gray">{{ a.accountType }}</span>
        </div>
        <div class="vv num" :style="Number(a.balance) < 0 ? 'color:var(--red)' : ''">
          <small>¥</small>{{ money(a.balance) }}
        </div>
        <div class="human">{{ humanOf(a) }}</div>
      </div>
      <div class="ledger-card stat">
        <div class="lb">本月平台扣费 <span class="chip c-gray">{{ curPeriod }}</span></div>
        <div class="vv num" style="color: var(--red)">
          <small>¥</small>{{ feeThisMonth == null ? '—' : money(feeThisMonth) }}
        </div>
        <div class="human">平台这个月吃掉多少钱 —— 利润表单列,费率异动 M4 亮灯</div>
      </div>
      <p v-if="!accountsLoading && !cardAccounts.length" class="mini" style="grid-column: 1 / -1">
        还没有资金账户 —— 去 <a class="name-link" @click="router.push({ path: '/settings', query: { tab: 'account' } })">设置中心 · 资金账户</a> 先建「微信 / 现金」等账户并设期初。
      </p>
    </div>

    <!-- ② 平台结算对账(⚡任务来源 + 流程条按真实单据状态点亮 + SettlementPanel 三态) -->
    <div class="ledger-card" data-block="settlement">
      <h3>
        🏦 平台结算对账
        <span class="hint">⚡ 任务:每月 2 日自动生成「平台结算核对」派给财务(系统校验=当月已核销/已核对),逾期红灯;在途货款超 35 天驾驶舱亮灯</span>
      </h3>
      <div v-if="flowSteps.length" class="flow-bar" data-block="settle-flow-bar">
        <div v-for="(s, i) in flowSteps" :key="i" class="flow-step" :class="s.state">
          {{ s.label }}<span class="mini">{{ s.mini }}</span>
        </div>
      </div>
      <SettlementPanel @changed="onSettlementChanged" />
      <p class="mini" style="margin-top: 8px">
        💡 业财一体规矩:平台打款后<b>手工录入</b>结算单(区间+平台数+到账数)→ <b>必传平台账单凭证</b> → 确认核销自动对两差、回填在途货款;两差(漏单/扣款)红绿灯,超阈值挂差异复核。录错的已核销单走「红冲逆向」整体退回。
      </p>
    </div>

    <!-- ③ 流水账(只读:钱只能被单据改;线下收入走复合单) -->
    <div class="ledger-card" data-block="flows">
      <h3>
        📒 流水账
        <span class="hint">全部收支 · 可筛账户/类别/记账期 · 只读——每行都由单据产生</span>
        <el-button type="primary" style="margin-left: auto" @click="offlineVisible = true">
          🛒 记线下收入(复合单)
        </el-button>
      </h3>
      <div class="flow-filter">
        <el-select v-model="filter.accountId" placeholder="全部账户" clearable style="width: 170px" @change="resetAndLoad">
          <el-option v-for="a in accounts" :key="a.id" :label="a.accountName" :value="a.id" />
        </el-select>
        <el-select v-model="filter.category" placeholder="全部类别" clearable style="width: 150px" @change="resetAndLoad">
          <el-option v-for="c in FLOW_CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
        <el-date-picker v-model="filter.fromPeriod" type="month" value-format="YYYY-MM" placeholder="记账期起" style="width: 130px" @change="resetAndLoad" />
        <span class="mini">~</span>
        <el-date-picker v-model="filter.toPeriod" type="month" value-format="YYYY-MM" placeholder="记账期止" style="width: 130px" @change="resetAndLoad" />
        <span class="mini" style="margin-left: auto">
          杂费/设备支出在下方「支出与设备」录;账户差异修正走盘点页钱盘的资金调整单
        </span>
      </div>
      <el-table :data="flowPage?.records ?? []" v-loading="flowLoading" size="small">
        <el-table-column label="日期" width="100">
          <template #default="{ row }">{{ (row.bizTime ?? '').slice(0, 10) }}</template>
        </el-table-column>
        <el-table-column prop="flowNo" label="流水号" width="150" show-overflow-tooltip />
        <el-table-column prop="accountName" label="账户" width="120" show-overflow-tooltip />
        <el-table-column label="类别" width="110">
          <template #default="{ row }">
            <span class="chip" :class="categoryChipClass(row.category)">{{ row.category }}</span>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="170">
          <template #default="{ row }">
            <span class="mini">{{ row.remark || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="收入" align="right" width="110">
          <template #default="{ row }">
            <b v-if="row.direction === '收'" class="num" style="color: var(--green)">+{{ money(row.amount) }}</b>
            <span v-else class="mini">—</span>
          </template>
        </el-table-column>
        <el-table-column label="支出" align="right" width="110">
          <template #default="{ row }">
            <b v-if="row.direction === '支'" class="num" style="color: var(--red)">−{{ money(row.amount) }}</b>
            <span v-else class="mini">—</span>
          </template>
        </el-table-column>
        <el-table-column label="关联单据" width="140">
          <template #default="{ row }">
            <span v-if="row.refDocType" class="mini">{{ row.refDocType }} #{{ row.refDocId }}</span>
            <span v-else class="mini">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="bookPeriod" label="记账期" width="90" />
      </el-table>
      <div class="flow-foot">
        <span class="mini">共 {{ flowPage?.total ?? 0 }} 笔</span>
        <el-pagination
          v-model:current-page="current"
          v-model:page-size="pageSize"
          :total="flowPage?.total ?? 0"
          :page-sizes="[20, 50, 100]"
          layout="prev, pager, next, sizes"
          size="small"
          @current-change="loadFlows"
          @size-change="resetAndLoad"
        />
      </div>
    </div>

    <!-- ④ 索赔(盘亏→找厂家/平台要钱;盘点五步向导也从这发起) -->
    <div class="ledger-card" data-block="claim">
      <h3>🧾 索赔 <span class="hint">吞货/掉货/被盗 → 找厂家/平台要钱 · 应收挂账跟踪 · 到账凭证必传</span></h3>
      <ClaimPanel />
    </div>

    <!-- ⑤ 支出与设备台账(杂费在这里手录 → mockup「＋记一笔」的正式家) -->
    <div class="ledger-card" data-block="expense">
      <h3>💸 支出与设备台账 <span class="hint">电费/维修/杂支/设备购置 · 凭证硬门禁 · 设备购置同步进台账看回本</span></h3>
      <ExpensePanel />
    </div>

    <div class="two-col">
      <!-- ⑦ 月度钱盘入口(CashCheckPanel 的正式家在盘点页 SOP,不重复装配防双真相源) -->
      <div class="ledger-card" data-block="cashcheck-entry">
        <h3>💰 月度钱盘三核对 <span class="hint">SOP · D2 · ⚡每月 1 日任务自动生成</span></h3>
        <p class="mini" style="line-height: 1.9; margin: 0 0 10px">
          ① 账户核对:系统余额 vs 微信/现金实际余额,差异走<b>资金调整单</b>(唯一出口)<br />
          ② 平台到账核对:上月结算是否到账、金额对不对<br />
          ③ 应付核对:系统应付 vs 供应商对方账,不符走补录/红冲
        </p>
        <el-button type="primary" @click="router.push('/stocktake')">去盘点页走钱盘 SOP →</el-button>
        <span class="mini" style="margin-left: 8px">货和钱一起盘,面板就在盘点页下方</span>
      </div>

      <!-- ⑧ AI 资金洞察灰位(M4 开放,mockup 卡位画灰防"做没做"分不清) -->
      <div class="ledger-card milestone-card" data-block="ai-insight">
        <h3 style="color: #b9b2a0">🤖 AI 资金洞察 <span class="chip c-gray">里程碑 4 开放</span></h3>
        <p class="mini" style="line-height: 1.9; margin: 0">
          费率异动亮灯(手续费率环比突变)· 结算差异自动归因(退款/漏单/额外扣款)·
          资金趋势与回本预测 —— 数据基座本页已备好,M4 点亮。
        </p>
      </div>
    </div>

    <p class="ledger-foot-note">
      — 兑换抵扣是付款单上的独立字段(对应「厂家已结账」),对账不再靠脑子记 —
    </p>

    <!-- ⑥ 线下收入复合单(机器故障顾客直转老板:补销售+落流水+盘点豁免三件套) -->
    <OfflineSaleDialog v-model="offlineVisible" @created="onOfflineCreated" />
  </div>
</template>

<style scoped>
/* 账户卡行(mockup g4;卡数随账户数自适应) */
.acct-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}
.stat {
  margin-bottom: 0;
}
.stat .lb {
  font-size: 12.5px;
  color: var(--ink2);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
}
.stat .vv {
  font-size: 26px;
  font-weight: 700;
  margin: 6px 0 2px;
}
.stat .vv small {
  font-size: 14px;
  margin-right: 2px;
}
.stat .human {
  font-size: 12px;
  color: var(--ink2);
  border-top: 1px dashed var(--line2);
  margin-top: 10px;
  padding-top: 8px;
  line-height: 1.6;
}

/* 编号流程条(mockup p7 五连横条,同盘点页样式语言) */
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

/* 流水筛选行 */
.flow-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.flow-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  flex-wrap: wrap;
  gap: 8px;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.milestone-card {
  border-style: dashed;
}

@media (max-width: 768px) {
  .two-col {
    grid-template-columns: 1fr;
  }
  .flow-bar {
    flex-direction: column;
  }
  .flow-step:first-child { border-radius: 8px 8px 0 0; }
  .flow-step:last-child { border-radius: 0 0 8px 8px; }
  .flow-step + .flow-step { border-left: none; border-top: 2px dashed rgba(255, 255, 255, 0.6); }
}
</style>
