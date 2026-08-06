<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DocDetailDrawer from '@/components/doc/DocDetailDrawer.vue'
import { pageMachines, type Machine } from '@/api/basedata'
import {
  ACCOUNT_ERROR_REASONS, DIFF_REASONS,
  cancelStocktake, confirmStocktake, createStocktake, getLossStats, getStocktake,
  listStocktakes, precheckStocktake, saveStocktakeItems, submitStocktake,
  type LossStatRow, type PrecheckResp, type StocktakeDetail, type StocktakeListRow,
} from '@/api/stocktake'

/**
 * 盘点页(M2-4,对照 mockup p9):
 * ⚡任务来源+编号流程条 → 新建盘点(选范围,系统快照账面)→ 盘点表格(账面带出,
 * 只填差异,差异行高亮+原因必选)→ 提交 → 五步向导(1 系统查账 / 2 归因汇总 /
 * 3 生成盘盈亏单[>¥50 红标老板确认] / 4-5 灰位标里程碑)→ 历史列表 + 损耗小结。
 * 桌面版为主;手机适配是并行票 M2-5,DOM 结构保持干净分块。
 */

// ============================== 机器主数据 ==============================

const machines = ref<Machine[]>([])
async function loadMachines() {
  const page = await pageMachines({ current: 1, size: 100 })
  machines.value = page.records
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

async function doSave(silent = false) {
  if (!detail.value) return
  const bad = diffRows.value.find((r) => !r.diffReason)
  if (bad) {
    ElMessage.warning(`差异行必选原因:${bad.productName || bad.productId}`)
    return false
  }
  saving.value = true
  try {
    await saveStocktakeItems(
      detail.value.id,
      diffRows.value.map((r) => ({
        productId: r.productId,
        actualQty: r.actual,
        diffReason: r.diffReason,
        offlineExempt: r.offlineExempt,
      })),
    )
    if (!silent) ElMessage.success(`已保存 ${diffRows.value.length} 行差异(其余视同相符)`)
    return true
  } finally {
    saving.value = false
  }
}

async function doSubmit() {
  if (!detail.value) return
  if (!(await doSave(true))) return
  await submitStocktake(detail.value.id)
  ElMessage.success('已提交,进入五步向导——先看第 1 步系统查账')
  await loadList()
  await openDetail(detail.value.id)
}

async function doCancel() {
  if (!detail.value) return
  await ElMessageBox.confirm(`作废盘点单 ${detail.value.stNo}?未过账,可放心作废。`, '作废盘点', { type: 'warning' })
  await cancelStocktake(detail.value.id)
  ElMessage.success('已作废')
  detail.value = null
  await loadList()
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

onMounted(() => {
  loadMachines()
  loadList().then(() => {
    if (activeRow.value) openDetail(activeRow.value.id)
  })
  loadLossStats()
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
          <el-table-column label="状态" width="90">
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
