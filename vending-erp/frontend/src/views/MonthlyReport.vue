<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import LlmTransparencyBadge from '@/components/ai/LlmTransparencyBadge.vue'
import MockBadge from '@/components/ai/MockBadge.vue'
import { aiApi, type AiModelRow } from '@/api/ai'
import {
  monthlyApi,
  type AnalysisReport,
  type CalendarBoard,
  type MonthlyPackage,
} from '@/api/monthly'

/**
 * 月度报表包(M4-4,§10.3 七件套 + §10.2 财务工作日历)。
 * 财务的月度工作从"做表"变成"核对 + 看报告":六件套预览 Tab + AI 起草的经营分析报告 + 一键导出 Excel/Word。
 * 全部只读聚合已有服务;AI 段(第七件综述)挂 🔬 过程 + [MOCK] 徽标 + 可换模型。
 */
const route = useRoute()
const router = useRouter()
const month = ref<string>(typeof route.query.month === 'string' ? route.query.month : '')
const months = ref<string[]>([])
const dataAsOf = ref<string | null>(null)

const pkg = ref<MonthlyPackage | null>(null)
const report = ref<AnalysisReport | null>(null)
const board = ref<CalendarBoard | null>(null)
const models = ref<AiModelRow[]>([])
const activeTab = ref('inventory')
const loading = ref(false)
const reportLoading = ref(false)

function money(v: number | null | undefined): string {
  if (v == null) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function statusText(s: string): string {
  return s === 'AUTO_DONE' ? '自动完成' : s === 'PENDING_MANUAL' ? '待人工' : '进行中'
}
function statusType(s: string): 'success' | 'warning' | 'info' {
  return s === 'AUTO_DONE' ? 'success' : s === 'PENDING_MANUAL' ? 'warning' : 'info'
}

async function loadAll() {
  loading.value = true
  try {
    const p = await monthlyApi.pkg(month.value || undefined)
    pkg.value = p
    months.value = p.months
    dataAsOf.value = p.dataAsOf
    if (!month.value) month.value = p.month
    board.value = await monthlyApi.calendar(month.value)
    await loadReport(false)
  } finally {
    loading.value = false
  }
}

async function loadReport(force: boolean) {
  reportLoading.value = true
  try {
    report.value = await monthlyApi.analysis(month.value || undefined, force)
  } finally {
    reportLoading.value = false
  }
}

async function changeModel(modelKey: string) {
  try {
    await aiApi.setModel('monthly', modelKey)
    ElMessage.success('已切换月报起草模型,重新起草中…')
    await loadReport(true)
  } catch {
    /* 错误已由拦截器提示 */
  }
}

function reload() {
  loadAll()
}

onMounted(async () => {
  try {
    models.value = await aiApi.models()
  } catch {
    /* 模型列表拿不到不影响主流程 */
  }
  await loadAll()
})
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 月度报表</div>
    <div class="ledger-title">
      <h2>月度报表包</h2>
      <span class="sub">§10.3 七件套一键出 · 财务从"做表"变"核对 + 看报告" · 预计 2-3 天压到半天</span>
    </div>

    <!-- ⚡ 任务来源 + 月份切换 + 导出 -->
    <el-card shadow="never" class="mb-12px">
      <div class="flex items-center gap-12px flex-wrap">
        <b>报表月份</b>
        <el-select v-model="month" style="width: 150px" @change="reload">
          <el-option v-for="m in months" :key="m" :label="m" :value="m" />
        </el-select>
        <el-button size="small" @click="reload">刷新</el-button>
        <el-tag v-if="pkg?.locked" type="warning" effect="plain">该月已锁账 · 归档不重算</el-tag>
        <el-tag effect="plain" type="success" style="margin-left: auto">
          数据截至 {{ dataAsOf ?? '——(尚未导入)' }}
        </el-tag>
      </div>
      <el-divider class="my-10px" />
      <div class="flex items-center gap-12px flex-wrap">
        <span class="text-12px text-gray-500">
          ⚡ 报表包每月 1-5 日由系统自动生成,财务只需核对 + 与老板过报告
        </span>
        <div style="margin-left: auto" class="flex gap-8px">
          <el-button type="success" @click="monthlyApi.exportExcel(month || undefined)">
            ⬇ 导出 Excel(六件套)
          </el-button>
          <el-button type="primary" @click="monthlyApi.exportWord(month || undefined)">
            ⬇ 导出 Word(分析报告)
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 财务月度工作日历 -->
    <el-card shadow="never" class="mb-12px" v-if="board">
      <template #header>
        <b>📅 财务月度工作日历</b>
        <span class="text-12px text-gray-400"> · §10.2 每月 1-5 日 · 大部分自动</span>
        <el-tag v-if="board.pendingCount > 0" type="warning" size="small" style="margin-left: 8px">
          {{ board.pendingCount }} 项待人工
        </el-tag>
      </template>
      <div class="cal-grid">
        <div v-for="d in board.days" :key="d.day" class="cal-day">
          <div class="cal-day-head">
            <b>{{ d.day }} 日</b>
            <el-tag :type="statusType(d.status)" size="small" effect="light">
              {{ statusText(d.status) }}
            </el-tag>
          </div>
          <div class="cal-finance">{{ d.financeAction }}</div>
          <div class="cal-auto">🤖 {{ d.systemAuto }}</div>
          <div class="cal-note">{{ d.note }}</div>
        </div>
      </div>
    </el-card>

    <!-- 七件套预览 Tab(前六件表格 + 第七件报告) -->
    <el-card shadow="never" class="mb-12px" v-if="pkg">
      <el-tabs v-model="activeTab">
        <!-- ① 进销存 -->
        <el-tab-pane label="① 进销存汇总" name="inventory">
          <el-table :data="pkg.inventory.rows" size="small" border max-height="440">
            <el-table-column label="SKU" prop="code" width="110" />
            <el-table-column label="商品" min-width="140">
              <template #default="{ row }">
                <a v-if="row.productId" class="plink" @click="router.push(`/products/${row.productId}`)">{{ row.name }} ↗</a>
                <span v-else>{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column label="期初" align="right" width="80"><template #default="{ row }">{{ row.openingQty }}</template></el-table-column>
            <el-table-column label="入库" align="right" width="80"><template #default="{ row }">{{ row.inQty }}</template></el-table-column>
            <el-table-column label="出库" align="right" width="80"><template #default="{ row }">{{ row.outQty }}</template></el-table-column>
            <el-table-column label="期末" align="right" width="80"><template #default="{ row }">{{ row.closingQty }}</template></el-table-column>
            <el-table-column label="期末金额" align="right" width="110"><template #default="{ row }">{{ row.hasCost ? money(row.closingAmt) : '—' }}</template></el-table-column>
          </el-table>
          <div class="tab-total" v-if="pkg.inventory.total">
            合计期末库存金额:<b>¥{{ money(pkg.inventory.total.closingAmt) }}</b>
          </div>
        </el-tab-pane>

        <!-- ② 简版利润表 -->
        <el-tab-pane label="② 简版利润表" name="profit">
          <el-table :data="pkg.profit.rows" size="small" border>
            <el-table-column label="项目" min-width="150">
              <template #default="{ row }">
                <b v-if="row.subtotal">{{ row.label }}</b><span v-else>{{ row.label }}</span>
              </template>
            </el-table-column>
            <el-table-column label="金额(对经营利润贡献)" align="right" width="180">
              <template #default="{ row }">
                <b v-if="row.subtotal">¥{{ money(row.amount) }}</b>
                <span v-else :class="{ 'neg': row.amount < 0 }">¥{{ money(row.amount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="口径说明" min-width="240">
              <template #default="{ row }"><span class="text-12px text-gray-500">{{ row.note }}</span></template>
            </el-table-column>
          </el-table>
          <div class="tab-total">当月经营利润(老板最想要的一个数):<b>¥{{ money(pkg.profit.operatingProfit) }}</b></div>
        </el-tab-pane>

        <!-- ③ 现金流水 -->
        <el-tab-pane label="③ 现金流水汇总" name="cashflow">
          <div class="grid-2">
            <div>
              <div class="sub-h">按账户</div>
              <el-table :data="pkg.cashflow.byAccount" size="small" border>
                <el-table-column label="账户" prop="name" min-width="110" />
                <el-table-column label="收" align="right" width="90"><template #default="{ row }">{{ money(row.inflow) }}</template></el-table-column>
                <el-table-column label="支" align="right" width="90"><template #default="{ row }">{{ money(row.outflow) }}</template></el-table-column>
                <el-table-column label="净额" align="right" width="90"><template #default="{ row }">{{ money(row.net) }}</template></el-table-column>
              </el-table>
            </div>
            <div>
              <div class="sub-h">各账户月末余额</div>
              <el-table :data="pkg.cashflow.monthEndBalances" size="small" border>
                <el-table-column label="账户" prop="accountName" min-width="110" />
                <el-table-column label="期初" align="right" width="100"><template #default="{ row }">{{ money(row.openingBalance) }}</template></el-table-column>
                <el-table-column label="月末余额" align="right" width="110"><template #default="{ row }">{{ money(row.monthEndBalance) }}<el-tag v-if="row.virtual" size="small" effect="plain" type="info" class="ml-4px">虚拟</el-tag></template></el-table-column>
              </el-table>
            </div>
          </div>
          <div class="tab-total">
            本月 收 ¥{{ money(pkg.cashflow.totalInflow) }} · 支 ¥{{ money(pkg.cashflow.totalOutflow) }} ·
            净 ¥{{ money(pkg.cashflow.totalNet) }} · 月末现金合计(不含虚拟)<b>¥{{ money(pkg.cashflow.monthEndCashTotal) }}</b>
          </div>
        </el-tab-pane>

        <!-- ④ 往来 -->
        <el-tab-pane label="④ 往来表" name="payable">
          <el-table :data="pkg.payable.suppliers" size="small" border>
            <el-table-column label="供应商" prop="supplierName" min-width="130" />
            <el-table-column label="结算方式" prop="settleMethod" width="100" />
            <el-table-column label="本期采购" align="right" width="100"><template #default="{ row }">{{ money(row.purchaseTotal) }}</template></el-table-column>
            <el-table-column label="本期付款" align="right" width="100"><template #default="{ row }">{{ money(row.paymentTotal) }}</template></el-table-column>
            <el-table-column label="应付余额" align="right" width="110">
              <template #default="{ row }">
                <span :class="{ 'neg': row.balance < 0 }">¥{{ money(row.balance) }}</span>
                <el-tag v-if="row.prepay" size="small" type="info" effect="plain" class="ml-4px">预付</el-tag>
                <el-tag v-if="row.overdue" size="small" type="danger" effect="plain" class="ml-4px">逾期</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <div class="tab-total">
            应付合计 <b>¥{{ money(pkg.payable.payableTotal) }}</b> ·
            平台待结算 ¥{{ money(pkg.payable.platformPending?.pendingBalance) }}
            <el-tag v-if="pkg.payable.platformPending?.overdue" type="danger" size="small">超期</el-tag>
          </div>
        </el-tab-pane>

        <!-- ⑤ 损耗 -->
        <el-tab-pane label="⑤ 损耗报表" name="loss">
          <el-table :data="pkg.loss.rows" size="small" border>
            <el-table-column label="原因" prop="reason" min-width="120" />
            <el-table-column label="行数" align="right" width="90"><template #default="{ row }">{{ row.itemCount }}</template></el-table-column>
            <el-table-column label="数量" align="right" width="90"><template #default="{ row }">{{ row.qty }}</template></el-table-column>
            <el-table-column label="金额" align="right" width="110"><template #default="{ row }">¥{{ money(row.amount) }}</template></el-table-column>
          </el-table>
          <el-empty v-if="!pkg.loss.rows.length" description="本月无损耗记录" :image-size="60" />
          <div class="tab-total" v-else>本月损耗合计 <b>¥{{ money(pkg.loss.totalAmount) }}</b> · 最大原因「{{ pkg.loss.topReason }}」</div>
        </el-tab-pane>

        <!-- ⑥ 资产 -->
        <el-tab-pane label="⑥ 资产快照" name="asset">
          <div class="asset-cards" v-if="pkg.asset.snapshot">
            <div class="asset-c"><span>库存资产</span><b>¥{{ money(pkg.asset.snapshot.inventoryAmount) }}</b></div>
            <div class="asset-c"><span>平台待结算</span><b>¥{{ money(pkg.asset.snapshot.platformPending) }}</b></div>
            <div class="asset-c"><span>账户现金</span><b>¥{{ money(pkg.asset.snapshot.cashTotal) }}</b></div>
            <div class="asset-c"><span>索赔应收</span><b>¥{{ money(pkg.asset.snapshot.claimReceivable) }}</b></div>
            <div class="asset-c"><span>应付供应商</span><b class="neg">−¥{{ money(pkg.asset.snapshot.payableTotal) }}</b></div>
            <div class="asset-c net"><span>净家底</span><b>¥{{ money(pkg.asset.snapshot.netAsset) }}</b></div>
          </div>
          <div class="sub-h mt-12px">净家底趋势(近 12 月归档快照)</div>
          <el-table :data="pkg.asset.trend" size="small" border>
            <el-table-column label="月份" prop="period" width="110" />
            <el-table-column label="库存" align="right"><template #default="{ row }">{{ money(row.inventoryAmount) }}</template></el-table-column>
            <el-table-column label="现金" align="right"><template #default="{ row }">{{ money(row.cashTotal) }}</template></el-table-column>
            <el-table-column label="应付" align="right"><template #default="{ row }">{{ money(row.payableTotal) }}</template></el-table-column>
            <el-table-column label="净家底" align="right"><template #default="{ row }"><b>{{ money(row.netAsset) }}</b></template></el-table-column>
          </el-table>
          <el-empty v-if="!pkg.asset.trend.length" description="尚无归档快照" :image-size="60" />
        </el-tab-pane>

        <!-- ⑦ 经营分析报告 -->
        <el-tab-pane label="⑦ 经营分析报告" name="analysis">
          <div v-loading="reportLoading" v-if="report">
            <!-- AI 综述段 -->
            <el-card shadow="never" class="ai-summary mb-12px">
              <div class="flex items-center gap-8px mb-6px flex-wrap">
                <b>🤖 AI 起草综述</b>
                <LlmTransparencyBadge :call-id="report.llmCallId" size="small" />
                <MockBadge :mock="report.mock" :model="report.model" />
                <el-tag v-if="report.cacheHit" type="info" size="small" effect="plain">缓存命中</el-tag>
                <div class="flex items-center gap-6px" style="margin-left: auto">
                  <span class="text-12px text-gray-400">起草模型</span>
                  <el-select :model-value="report.model" size="small" style="width: 170px" @change="changeModel">
                    <el-option v-for="m in models" :key="m.key" :label="m.effectiveModel" :value="m.effectiveModel" />
                    <el-option v-if="!models.length" :label="report.model" :value="report.model" />
                  </el-select>
                  <el-button size="small" @click="loadReport(true)">重新起草</el-button>
                </div>
              </div>
              <div class="ai-text">{{ report.aiText }}</div>
            </el-card>

            <!-- 七节 -->
            <div v-for="s in report.sections" :key="s.key" class="sec">
              <div class="sec-title">{{ s.title }}</div>
              <div class="sec-narrative">{{ s.narrative }}</div>
              <el-table :data="s.dataPoints" size="small" border>
                <el-table-column label="指标" prop="label" width="160" />
                <el-table-column label="数值" prop="value" min-width="200" />
                <el-table-column label="数据溯源" width="230">
                  <template #default="{ row }"><el-tag size="small" effect="plain" type="success">{{ row.source }}</el-tag></template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script lang="ts">
export default { name: 'MonthlyReport' }
</script>

<style scoped>
.neg { color: #c0392b; }
.plink { color: #2f6f4f; cursor: pointer; font-weight: 600; }
.plink:hover { text-decoration: underline; }
.tab-total { margin-top: 10px; padding: 8px 12px; background: #f7f4ea; border-radius: 6px; font-size: 13px; }
.tab-total b { color: #2f6f4f; font-size: 15px; }
.sub-h { font-weight: 600; margin: 6px 0; color: #555; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.cal-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.cal-day { border: 1px solid #e6e0cf; border-radius: 8px; padding: 10px 12px; background: #fcfaf3; }
.cal-day-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.cal-finance { font-size: 13px; color: #333; margin-bottom: 4px; }
.cal-auto { font-size: 12px; color: #6b8f6b; margin-bottom: 4px; }
.cal-note { font-size: 12px; color: #999; }
.asset-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.asset-c { border: 1px solid #e6e0cf; border-radius: 8px; padding: 12px; background: #fcfaf3; display: flex; flex-direction: column; gap: 4px; }
.asset-c span { font-size: 12px; color: #888; }
.asset-c b { font-size: 18px; }
.asset-c.net { background: #eef6ef; border-color: #cfe6d4; }
.asset-c.net b { color: #2f6f4f; }
.ai-summary { background: #f3f7fb; border: 1px solid #dce7f0; }
.ai-text { white-space: pre-wrap; line-height: 1.7; font-size: 14px; color: #2c3e50; }
.sec { margin-bottom: 16px; }
.sec-title { font-weight: 700; font-size: 15px; margin-bottom: 4px; color: #2f4f4f; }
.sec-narrative { font-size: 13px; color: #444; line-height: 1.6; margin-bottom: 8px; }
@media (max-width: 900px) {
  .cal-grid, .asset-cards { grid-template-columns: 1fr 1fr; }
  .grid-2 { grid-template-columns: 1fr; }
}
</style>
