<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  adoptPlan, clearanceAlerts, ignorePlan, machineSuggestions, planDetail, purchaseSuggestions, recalc,
  type ClearanceAlertRow, type PlanDetailResp, type PlanRow, type SuggestionsResp,
} from '@/api/replenish'
import {
  getGlobalReplenishConfig, pageSuppliers, saveReplenishConfig,
  type ReplenishConfig, type Supplier,
} from '@/api/basedata'
import { createPurchaseOrder } from '@/api/purchase'
import { generateTickets } from '@/api/prekit'

/**
 * AI 补货提示页(M2-1/M2-2,对照 mockup p2):
 * 规则引擎算数字 · AI 讲人话 · 每个数字可点开看计算过程(🔬 四 Tab)。
 * 两级两策略:机器侧 = 到访补满货道(par level);仓库侧 = (R,S) 公式;慢销 min/max。
 */

const router = useRouter()
const loading = ref(false)
const activeTab = ref<'machine' | 'purchase'>('machine')
const machineData = ref<SuggestionsResp | null>(null)
const purchaseData = ref<SuggestionsResp | null>(null)
const globalCfg = ref<ReplenishConfig | null>(null)

async function load() {
  loading.value = true
  try {
    const [m, p, cfg, alerts] = await Promise.all([
      machineSuggestions(), purchaseSuggestions(), getGlobalReplenishConfig(),
      // 清仓提醒挂了不拦补货主流程,退化为空列表(M2-10 P1-3)
      clearanceAlerts().catch(() => [] as ClearanceAlertRow[]),
    ])
    machineData.value = m
    purchaseData.value = p
    globalCfg.value = cfg
    clearAlerts.value = alerts
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ---------- 🏷 清仓残余三选一(M2-10 P1-3:P2-10 死锁解除三条腿的前端消费方) ----------
const clearAlerts = ref<ClearanceAlertRow[]>([])
const choiceChip: Record<string, string> = { 退供: 'info', 报损: 'danger', 换机促销: 'warning' }

// ---------- 数据新鲜度(铁律#1:超 3 天建议变灰) ----------
const staleDays = computed(() => machineData.value?.staleDays ?? purchaseData.value?.staleDays ?? null)
const isStale = computed(() => staleDays.value != null && Number(staleDays.value) > 3)
const dataAsOf = computed(() => machineData.value?.dataAsOf ?? purchaseData.value?.dataAsOf ?? null)

// ---------- 行内计算明细(formula_json = 引擎唯一真相源,前端不重算) ----------
function detailOf(row: PlanRow): Record<string, unknown> {
  try {
    return row.formulaJson ? JSON.parse(row.formulaJson) : {}
  } catch {
    return {}
  }
}
const urgencyType: Record<string, string> = { 缺货: 'danger', 急: 'danger', '3天内': 'warning', 正常: 'success' }

// ---------- 机器侧:按机分组卡片 ----------
const machineRows = computed(() => (machineData.value?.rows ?? []).filter((r) => r.planStatus !== '已忽略'))
const machineGroups = computed(() => {
  const map = new Map<number, { machineId: number; machineName: string; rows: PlanRow[] }>()
  for (const r of machineRows.value) {
    const key = r.machineId ?? 0
    if (!map.has(key)) map.set(key, { machineId: key, machineName: r.machineName ?? `机器#${key}`, rows: [] })
    map.get(key)!.rows.push(r)
  }
  return [...map.values()]
})
const machineChecked = reactive<Record<number, boolean>>({})
const machineCheckedCount = computed(() => machineRows.value.filter((r) => machineChecked[r.id]).length)

// ---------- 机器侧 → 生成配货单(M2-3 真通) ----------
const prekitLoading = ref(false)
async function doGeneratePrekit() {
  const checked = machineRows.value.filter((r) => machineChecked[r.id])
  if (checked.length === 0) {
    ElMessage.warning('先勾选机器侧建议行')
    return
  }
  prekitLoading.value = true
  try {
    const resp = await generateTickets(checked.map((r) => ({ planId: r.id })))
    for (const r of checked) machineChecked[r.id] = false
    await load()
    await ElMessageBox.confirm(
      `已按机器分组生成 ${resp.ticketIds.length} 张配货单(共 ${resp.itemCount} 行,带出量在出库上架页可改)。仓库照单装箱后标记「已执行」,次日导入后台补货记录自动核销算带回率。`,
      '🧺 配货单已生成',
      { confirmButtonText: '去出库上架页', cancelButtonText: '留在本页', type: 'success' },
    ).then(() => router.push('/outbound')).catch(() => undefined)
  } finally {
    prekitLoading.value = false
  }
}

// ---------- 仓库侧 ----------
const purchaseRows = computed(() => (purchaseData.value?.rows ?? []).filter((r) => r.planStatus !== '已忽略'))
const purchaseSelection = ref<PlanRow[]>([])
const purchaseTotalBoxes = computed(() =>
  purchaseRows.value.reduce((s, r) => {
    const d = detailOf(r)
    const m = String(d['整箱取整'] ?? '').match(/^([\d.]+) 箱/)
    return s + (m ? Number(m[1]) : 0)
  }, 0),
)

// ---------- 重新计算 ----------
const recalcLoading = ref(false)
async function doRecalc() {
  recalcLoading.value = true
  try {
    const r = await recalc()
    ElMessage.success(`已重算:机器侧 ${r.machineRows} 条 · 采购 ${r.purchaseRows} 条(数据截至 ${r.dataAsOf})`)
    await load()
  } finally {
    recalcLoading.value = false
  }
}

// ---------- 忽略 ----------
async function doIgnore(row: PlanRow) {
  await ignorePlan(row.id)
  ElMessage.success('已忽略(重算不会再生成,可在历史里恢复)')
  await load()
}

// ---------- 🔬 过程弹窗(透明四件套) ----------
const processVisible = ref(false)
const processLoading = ref(false)
const processTab = ref('calc')
const processData = ref<PlanDetailResp | null>(null)
const processTitle = ref('')
async function openProcess(row: PlanRow) {
  processTitle.value = `${row.productName} · ${row.machineName ?? '仓库采购'} · 建议 ${trim(row.suggestQty)} ${row.unit ?? ''}`
  processVisible.value = true
  processTab.value = 'calc'
  processLoading.value = true
  try {
    processData.value = await planDetail(row.id)
  } finally {
    processLoading.value = false
  }
}
const processDetail = computed<Record<string, unknown>>(() => {
  const f = processData.value?.plan?.formulaJson
  try {
    return f ? JSON.parse(f) : {}
  } catch {
    return {}
  }
})
const confidencePct = computed(() => {
  const c = processData.value?.llm?.confidence
  return c == null ? null : Math.round(Number(c) * 100)
})

// ---------- 参数设置(复用 M1-2 config 接口) ----------
const cfgVisible = ref(false)
const cfgForm = reactive<ReplenishConfig>({})
const cfgSaving = ref(false)
function openCfg() {
  Object.assign(cfgForm, globalCfg.value ?? {})
  cfgVisible.value = true
}
async function saveCfg() {
  cfgSaving.value = true
  try {
    globalCfg.value = await saveReplenishConfig({ ...cfgForm, machineId: 0, productId: 0 })
    cfgVisible.value = false
    ElMessage.success('参数已保存(已写 op_log),点「重新计算」生效')
  } finally {
    cfgSaving.value = false
  }
}

// ---------- 仓库侧:一键转订货单(真通 M1-4 purchase) ----------
const poVisible = ref(false)
const poSaving = ref(false)
const poSupplierId = ref<number | null>(null)
const suppliers = ref<Supplier[]>([])
async function openPoDialog() {
  if (purchaseSelection.value.length === 0) {
    ElMessage.warning('先勾选要采购的商品行')
    return
  }
  const page = await pageSuppliers({ size: 100 })
  suppliers.value = page.records
  poSupplierId.value = suppliers.value[0]?.id ?? null
  poVisible.value = true
}
async function createPo() {
  if (!poSupplierId.value) {
    ElMessage.warning('请选择供应商')
    return
  }
  poSaving.value = true
  try {
    const poId = await createPurchaseOrder({
      supplierId: poSupplierId.value,
      remark: `AI 补货建议转订货单(建议日 ${purchaseData.value?.planDate ?? ''})`,
      items: purchaseSelection.value.map((r) => ({
        productId: r.productId,
        qtyOrdered: Number(r.boxRoundQty ?? r.suggestQty),
      })),
    })
    for (const r of purchaseSelection.value) await adoptPlan(r.id)
    poVisible.value = false
    await load()
    await ElMessageBox.confirm(
      `订货单草稿已生成(${purchaseSelection.value.length} 个商品,整箱数量已带入),去采购页下单?`,
      '✅ 已转订货单', { confirmButtonText: '去采购页', cancelButtonText: '留在本页', type: 'success' },
    ).then(() => router.push('/purchase')).catch(() => undefined)
    void poId
  } finally {
    poSaving.value = false
  }
}

// ---------- 小工具 ----------
const trim = (v: number | string | null | undefined) =>
  v == null ? '—' : Number(v).toLocaleString(undefined, { maximumFractionDigits: 1 })
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / AI 补货提示</div>
    <div class="ledger-title">
      <h2>AI 补货提示</h2>
      <span class="sub">规则引擎算数字 · AI 讲人话 · 每个数字可点开看计算过程</span>
    </div>
    <p class="ledger-note">
      目标:<b>用最少的库存,撑满一个补货周期不断货</b>。周期你定,该补什么、补多少,系统算。
    </p>

    <!-- ⚡任务来源说明 + 编号流程条(七律#2:领着人干活) -->
    <el-alert :type="isStale ? 'warning' : 'info'" :closable="false" class="mb-12px">
      <template #title>
        <div class="flex items-center gap-8px flex-wrap">
          <span>⚡ 本页建议由补货引擎按「数据截至 <b>{{ dataAsOf ?? '——(尚未导入)' }}</b>」的销售记录生成</span>
          <el-tag v-if="isStale" type="warning" size="small">⚠️ 数据已 {{ staleDays }} 天未更新,建议先去导入中心导最新数据(过期数据不出建议)</el-tag>
        </div>
        <div class="flow-bar">
          <span :class="{ cur: isStale }">① 每早导入昨日数据</span>
          <span :class="{ cur: !isStale }">② 重新计算</span>
          <span>③ 机器侧:照单补满货道 → 生成配货单</span>
          <span>④ 仓库侧:缺口一键转订货单</span>
        </div>
      </template>
    </el-alert>

    <!-- 参数条(mockup p2:两级两策略 + R/服务水平) -->
    <el-card shadow="never" class="mb-12px">
      <div class="flex items-center gap-24px flex-wrap">
        <div>
          <div class="mini mb-4px">🚚 去机器补货(目标:货架永远不空)</div>
          <el-tag type="success" effect="dark">每天 · 到访补满货道(不套公式)</el-tag>
        </div>
        <el-divider direction="vertical" style="height: 36px" />
        <div>
          <div class="mini mb-4px">📦 找供应商拿货周期 R(按 R,S 公式算采购量)</div>
          <b class="num">R = {{ globalCfg?.cycleDays ?? 15 }} 天</b>
          <span class="mini ml-8px">提前期 L = {{ trim(globalCfg?.leadTimeDays ?? 1) }} 天</span>
        </div>
        <div>
          <div class="mini mb-4px">服务水平</div>
          <b class="num">{{ Math.round(Number(globalCfg?.serviceLevel ?? 0.95) * 100) }}%</b>
        </div>
        <div>
          <div class="mini mb-4px">慢销品(日均&lt;1.5)min/max</div>
          <b class="num">{{ trim(globalCfg?.slowMinBoxes ?? 0.5) }} ~ {{ trim(globalCfg?.slowMaxBoxes ?? 1) }} 箱</b>
        </div>
        <div style="margin-left: auto" class="flex gap-8px">
          <el-button @click="openCfg">⚙️ 改参数</el-button>
          <el-button type="primary" :loading="recalcLoading" @click="doRecalc">↻ 重新计算</el-button>
        </div>
      </div>
    </el-card>
    <p class="mini mb-12px" style="margin-top: -4px">
      💡 两件事别混:<b>机器侧</b>去了就补满(跑一趟的人力比压几瓶货贵);<b>仓库侧</b>才用公式算"该向供应商订多少"。慢销品不套公式,直接 min/max 补满。
    </p>

    <!-- 🤖 AI 摘要(mockup p2 有,页级 LLM 汇总属 AI 全家桶 → 灰位占位) -->
    <div class="ai-summary-dim mb-12px">
      🤖 <b>AI 摘要</b>:把整页建议汇成三句人话(共需补多少件 · 缺口要不要先采购 · 先补哪台机)
      <span class="chip c-gray">里程碑 4 开放(AI 全家桶)</span>
      <span class="mini">现在每行的 🔬 已能看单条建议的算法与 AI 解释</span>
    </div>

    <el-tabs v-model="activeTab" :class="{ 'stale-dim': isStale }">
      <!-- ============ 机器侧 Tab ============ -->
      <el-tab-pane label="🚚 机器侧 · 补满货道" name="machine">
        <el-empty
          v-if="machineGroups.length === 0"
          description="暂无机器侧建议:货道都满着,或还没绑定货道容量(设置中心 → 机器 → 货道)"
        />
        <el-card v-for="g in machineGroups" :key="g.machineId" shadow="never" class="mb-12px">
          <h3 style="font-family: var(--serif); margin: 0 0 10px">
            <!-- M2-10 P1-2:机器名下钻机器详情(七律#3,同 Dashboard 机器卡一把尺) -->
            <template v-if="g.machineId">📦 <a class="name-link" @click="router.push(`/machines/${g.machineId}`)">{{ g.machineName }} ↗</a></template>
            <template v-else>📦 {{ g.machineName }}</template>
            <span class="hint">{{ g.rows.length }} 个 SKU 需补 · 机内库存 = 快照 + 业务时间增量推算</span>
          </h3>
          <el-table :data="g.rows" size="small">
            <el-table-column width="40">
              <template #default="{ row }">
                <el-checkbox v-model="machineChecked[row.id]" :disabled="isStale" />
              </template>
            </el-table-column>
            <el-table-column label="商品" min-width="170">
              <template #default="{ row }">
                <b class="name-link" @click="router.push(`/products/${row.productId}`)">{{ row.productName }} ↗</b>
                <span class="mini ml-4px">{{ row.skuCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="机内现有" align="right" width="90">
              <template #default="{ row }">
                <b :class="Number(row.currentQty) <= 0 ? 'text-red-600' : ''" class="num">{{ trim(row.currentQty) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="日均卖" align="right" width="80">
              <template #default="{ row }"><span class="num">{{ trim(row.avgDaily) }}</span></template>
            </el-table-column>
            <el-table-column label="存销比" align="right" width="90">
              <template #default="{ row }">
                <span class="num">{{ detailOf(row)['存销比(还能卖几天)'] == null ? '—' : `${detailOf(row)['存销比(还能卖几天)']} 天` }}</span>
              </template>
            </el-table-column>
            <el-table-column label="预计售罄" width="100">
              <template #default="{ row }">
                <el-tag
                  v-if="detailOf(row)['预计售罄日']"
                  :type="(urgencyType[String(detailOf(row)['紧急度'])] as any) ?? 'info'"
                  size="small" effect="plain"
                >
                  {{ String(detailOf(row)['预计售罄日']).slice(5) }}
                </el-tag>
                <span v-else class="mini">—</span>
              </template>
            </el-table-column>
            <el-table-column label="补到水位" align="right" width="90">
              <template #default="{ row }"><span class="num">{{ trim(row.targetLevelS) }}</span></template>
            </el-table-column>
            <el-table-column label="建议补" align="right" width="90">
              <template #default="{ row }">
                <b class="num" style="color: var(--green)">{{ trim(row.suggestQty) }} {{ row.unit }}</b>
              </template>
            </el-table-column>
            <el-table-column label="紧急度" width="90">
              <template #default="{ row }">
                <el-tag :type="(urgencyType[String(detailOf(row)['紧急度'])] as any) ?? 'info'" size="small">
                  {{ detailOf(row)['紧急度'] ?? '正常' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="" width="120" fixed="right">
              <template #default="{ row }">
                <span class="ai-badge" @click="openProcess(row)">🔬</span>
                <el-button link size="small" @click="doIgnore(row)">忽略</el-button>
              </template>
            </el-table-column>
          </el-table>
          <p v-if="g.rows.some((r: PlanRow) => detailOf(r)['临期约束触发']?.toString().startsWith('是'))" class="mini mt-6px">
            ⚠️ 本机有 SKU 触发临期约束(机内上限 = 日均 × 保质期 × 0.5),目标水位已自动压低,点 🔬 看明细。
          </p>
        </el-card>

        <div v-if="machineGroups.length" class="flex items-center gap-12px">
          <el-button
            type="primary"
            :disabled="isStale || machineCheckedCount === 0"
            :loading="prekitLoading"
            @click="doGeneratePrekit"
          >
            🧺 生成配货单(已勾 {{ machineCheckedCount }} 行)
          </el-button>
          <span class="mini">配货单 = 补货员照单从仓库装箱,到机器直接开箱补(pre-kit,不用现场数货);按机分组,生成后建议行标「已生成配货单」</span>
        </div>
      </el-tab-pane>

      <!-- ============ 仓库侧 Tab ============ -->
      <el-tab-pane label="⛺ 仓库侧 · 采购建议" name="purchase">
        <!-- 🏷 清仓提醒折叠条(M2-10 P1-3:清仓中商品滞留超 30 天仓库还压着货 → 三选一) -->
        <el-collapse v-if="clearAlerts.length" class="mb-12px clearance-collapse">
          <el-collapse-item name="clearance">
            <template #title>
              <span class="clearance-title">
                🏷 <b>清仓提醒({{ clearAlerts.length }})</b>
                —— 进入清仓超 30 天仓库还压着货,靠自然销售清不掉了,请三选一处理
              </span>
            </template>
            <el-table :data="clearAlerts" size="small">
              <el-table-column label="商品" min-width="170">
                <template #default="{ row }">
                  <b class="name-link" @click="router.push(`/products/${row.productId}`)">{{ row.productName }} ↗</b>
                  <span class="mini ml-4px">{{ row.skuCode }}</span>
                </template>
              </el-table-column>
              <el-table-column label="仓库余量" align="right" width="100">
                <template #default="{ row }"><b class="num">{{ trim(row.warehouseQty) }}</b></template>
              </el-table-column>
              <el-table-column label="滞留天数" align="right" width="130">
                <template #default="{ row }">
                  <b class="num" style="color: var(--amber)">{{ row.daysInClearance }} 天</b>
                  <div class="mini">清仓自 {{ row.clearanceSince }}</div>
                </template>
              </el-table-column>
              <el-table-column label="三选一建议" min-width="200">
                <template #default="{ row }">
                  <el-tag
                    v-for="c in row.choices"
                    :key="c"
                    :type="(choiceChip[c] as any) ?? 'info'"
                    size="small"
                    effect="plain"
                    class="mr-4px"
                  >{{ c }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
            <p class="mini mt-8px">
              💡 退供 = 联系供应商退货(走采购退货);报损 = 认亏出库(走盘点报损);换机促销 = 挪到卖得动的机器降价清(走配货单)。清仓中商品不再产生采购建议,这里是它唯一的出路提醒。
            </p>
          </el-collapse-item>
        </el-collapse>
        <el-empty v-if="purchaseRows.length === 0" description="暂无采购建议:仓库 + 机内 + 在途都够撑一个周期" />
        <el-card v-else shadow="never">
          <h3 style="font-family: var(--serif); margin: 0 0 10px">
            ⛺ 仓库不够了 · 采购建议
            <span class="hint">S = 日均×(R+L) + 安全库存 · 已扣仓库/机内/在途 · 按整箱换算</span>
          </h3>
          <el-table :data="purchaseRows" size="small" @selection-change="purchaseSelection = $event">
            <el-table-column type="selection" width="40" :selectable="() => !isStale" />
            <el-table-column label="商品" min-width="170">
              <template #default="{ row }">
                <b class="name-link" @click="router.push(`/products/${row.productId}`)">{{ row.productName }} ↗</b>
                <el-tag v-if="String(detailOf(row)['计算模式']) === '慢销min-max'" type="warning" size="small" class="ml-4px">
                  🐢 慢销
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="日均卖" align="right" width="80">
              <template #default="{ row }"><span class="num">{{ trim(row.avgDaily) }}</span></template>
            </el-table-column>
            <el-table-column label="现有(仓+机+在途)" align="right" width="115">
              <template #default="{ row }"><span class="num">{{ trim(row.currentQty) }}</span></template>
            </el-table-column>
            <el-table-column label="补到水位 S" align="right" width="90">
              <template #default="{ row }"><span class="num">{{ trim(row.targetLevelS) }}</span></template>
            </el-table-column>
            <el-table-column label="安全库存" align="right" width="80">
              <template #default="{ row }">
                <span class="num">{{ row.safetyStock == null ? '—' : trim(row.safetyStock) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="本周期缺口" align="right" width="100">
              <template #default="{ row }"><span class="num">{{ trim(row.suggestQty) }}</span></template>
            </el-table-column>
            <el-table-column label="建议采购(整箱)" align="right" width="130">
              <template #default="{ row }">
                <b class="num" style="color: var(--green)">
                  {{ String(detailOf(row)['整箱取整'] ?? `${trim(row.boxRoundQty)} ${row.unit}`) }}
                </b>
              </template>
            </el-table-column>
            <el-table-column label="" width="120" fixed="right">
              <template #default="{ row }">
                <span class="ai-badge" @click="openProcess(row)">🔬</span>
                <el-button link size="small" @click="doIgnore(row)">忽略</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="flex items-center gap-12px mt-12px">
            <el-button type="primary" :disabled="isStale || purchaseSelection.length === 0" @click="openPoDialog">
              🧾 一键转订货单(已勾 {{ purchaseSelection.length }} 行)
            </el-button>
            <span class="mini">合计约 {{ purchaseTotalBoxes }} 箱 · 转单后建议行标「已采纳」,收货入库走采购页</span>
          </div>
          <p class="mini mt-8px">
            💰 预计花费 · 参考供应商(按最近一次进价估算 + 上次从谁家拿的):<span class="chip c-gray">里程碑 3 开放</span>(钱账/供应商往来上线后每行点亮)
          </p>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <p class="ledger-foot-note">— 补货数字由 (R,S) 周期补货模型计算,AI 只负责解释,不改数字 —</p>

    <!-- ============ 🔬 过程弹窗(透明四件套) ============ -->
    <el-dialog v-model="processVisible" width="720px" :title="`🔬 这个数怎么算出来的 — ${processTitle}`">
      <el-tabs v-model="processTab" v-loading="processLoading">
        <el-tab-pane label="推理过程" name="calc">
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item v-for="(v, k) in processDetail" :key="k" :label="String(k)">
              {{ v == null ? '—' : v }}
            </el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane label="完整输出" name="output">
          <div class="llm-output">
            <p>{{ processData?.llm?.outputText ?? processData?.plan?.aiExplain ?? '(无 AI 输出)' }}</p>
            <p class="mini">
              — 模型:{{ processData?.llm?.model ?? 'mock' }} · 生成于 {{ processData?.llm?.createTime ?? '—' }}
              · 状态:{{ processData?.llm?.callStatus ?? '—' }} · 提示词 replenish-explain-v1
            </p>
          </div>
        </el-tab-pane>
        <el-tab-pane label="确信分" name="confidence">
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="综合置信度">
              <b :style="{ color: (confidencePct ?? 0) >= 80 ? 'var(--green)' : 'var(--amber)' }">
                {{ confidencePct == null ? '—' : `${confidencePct}%` }}
              </b>
            </el-descriptions-item>
            <el-descriptions-item label="构成(规则置信 = 数据完备度)">
              样本天数 {{ processDetail['样本天数'] ?? '—' }} / 28 天满窗
            </el-descriptions-item>
            <el-descriptions-item label="注意事项">
              若近期园区有活动/放假,请手动调整——模型还不知道你们的日历;样本不足 4 周时星期系数自动停用。
            </el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane label="原始数据" name="raw">
          <pre class="raw-json">{{ processData?.llm?.inputDigest ?? processData?.plan?.formulaJson ?? '(无)' }}</pre>
          <p class="mini">💾 数据源:sale_record 出货明细(正常+兑换)· 货道档案 · 库存流水 · 订货单在途</p>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- ============ 参数弹窗(复用 M1-2 config 接口) ============ -->
    <el-dialog v-model="cfgVisible" width="480px" title="⚙️ 补货参数(全局;改动写 op_log)">
      <el-form label-width="160px" size="small">
        <el-form-item label="补货周期 R(天)">
          <el-input-number v-model="cfgForm.cycleDays" :min="1" :max="60" />
        </el-form-item>
        <el-form-item label="服务水平">
          <el-select v-model="cfgForm.serviceLevel" style="width: 160px">
            <el-option label="90%(长尾)" :value="0.9" />
            <el-option label="95%(默认)" :value="0.95" />
            <el-option label="98%(爆款)" :value="0.98" />
          </el-select>
        </el-form-item>
        <el-form-item label="提前期 L(天)">
          <el-input-number v-model="cfgForm.leadTimeDays as number" :min="0" :max="30" />
        </el-form-item>
        <el-form-item label="慢销 min(箱)">
          <el-input-number v-model="cfgForm.slowMinBoxes as number" :min="0" :step="0.5" />
        </el-form-item>
        <el-form-item label="慢销 max(箱)">
          <el-input-number v-model="cfgForm.slowMaxBoxes as number" :min="0.5" :step="0.5" />
        </el-form-item>
        <el-form-item label="AI 解释模型">
          <el-input v-model="cfgForm.llmModel" placeholder="空 = 跟随系统配置(mock 阶段仅展示)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cfgVisible = false">取消</el-button>
        <el-button type="primary" :loading="cfgSaving" @click="saveCfg">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 转订货单弹窗 ============ -->
    <el-dialog v-model="poVisible" width="520px" title="🧾 转订货单(草稿)">
      <p class="mini mb-8px">勾选的 {{ purchaseSelection.length }} 个商品按<b>整箱数量</b>带入订货单草稿;单价在采购页录单时比价填写。</p>
      <el-form label-width="80px" size="small">
        <el-form-item label="供应商">
          <el-select v-model="poSupplierId" style="width: 100%" placeholder="选择供应商">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id!" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-table :data="purchaseSelection" size="small" max-height="240">
        <el-table-column prop="productName" label="商品" />
        <el-table-column label="数量" align="right" width="120">
          <template #default="{ row }">{{ trim(row.boxRoundQty ?? row.suggestQty) }} {{ row.unit }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="poVisible = false">取消</el-button>
        <el-button type="primary" :loading="poSaving" @click="createPo">生成订货单草稿</el-button>
      </template>
    </el-dialog>
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
.flow-bar span.cur {
  background: var(--green-soft);
  color: var(--green);
  font-weight: 700;
}
/* 🏷 清仓提醒折叠条(M2-10 P1-3):黄灯语气,展开是三选一表 */
.clearance-collapse {
  border: 1px solid #ecd8b8;
  border-radius: 10px;
  overflow: hidden;
}
.clearance-collapse :deep(.el-collapse-item__header) {
  background: var(--amber-soft);
  padding: 0 14px;
  font-size: 13px;
}
.clearance-collapse :deep(.el-collapse-item__content) {
  padding: 10px 14px 12px;
}
.clearance-title {
  color: #8a5a1d;
}
/* AI 摘要灰位(mockup p2 a-blue 摘要条的占位形态,里程碑4 点亮) */
.ai-summary-dim {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12.5px;
  color: var(--ink2);
  background: #f1ede2;
  border: 1px dashed var(--line2);
  border-radius: 10px;
  padding: 9px 14px;
}
.ai-badge {
  cursor: pointer;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 12px;
  background: var(--blue-soft);
  color: var(--blue);
  margin-right: 6px;
  user-select: none;
}
.ai-badge:hover {
  filter: brightness(0.95);
}
.stale-dim :deep(.el-table),
.stale-dim :deep(.el-card) {
  opacity: 0.55;
  filter: grayscale(0.6);
}
.llm-output {
  background: #f6f3ea;
  padding: 14px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.9;
}
.raw-json {
  background: #f6f3ea;
  padding: 12px;
  border-radius: 8px;
  font-size: 12px;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
