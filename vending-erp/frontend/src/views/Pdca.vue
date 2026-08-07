<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pdcaApi,
  SCENES,
  ITEM_STATUSES,
  COMPARE_OPS,
  type BoardResp,
  type ItemRow,
  type MetricDef,
  type MetricValue,
  type ItemSaveReq,
} from '@/api/pdca'

/**
 * 改进循环 PDCA(M4-3,对照 mockup p11 / §8.4):
 * 这页只回答两件事——①六个环节这个月的体检红绿灯;②所有改进措施到期了没有、见效了没有。
 * 改进不再"说过就忘":每条改进任务带 结构化验证指标(指标键+目标值+比较方向),
 * 到期取指标当前值 vs 目标自动判定:通过关闭 / 未达升级 / 取不到数需人工判定。
 */

const loading = ref(false)
const board = ref<BoardResp | null>(null)
const items = ref<ItemRow[]>([])
const metrics = ref<MetricDef[]>([])
const statusFilter = ref('') // ''=全部
const sceneFilter = ref('')

async function loadBoard() {
  board.value = await pdcaApi.board()
}
async function loadItems() {
  items.value = await pdcaApi.items({
    status: statusFilter.value || undefined,
    scene: sceneFilter.value || undefined,
  })
}
async function loadAll() {
  loading.value = true
  try {
    const [, , m] = await Promise.all([loadBoard(), loadItems(), pdcaApi.metrics()])
    metrics.value = m
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  loading.value = true
  try {
    metrics.value = await pdcaApi.metrics()
    await Promise.all([loadBoard(), loadItems()])
  } finally {
    loading.value = false
  }
})

// ---------- 灯 → 颜色 ----------
const LIGHT_CLASS: Record<string, string> = {
  green: 'c-green',
  amber: 'c-amber',
  red: 'c-red',
  gray: 'c-gray',
}
const LIGHT_TEXT: Record<string, string> = { green: '绿', amber: '黄', red: '红', gray: '—' }
function lightBorder(level: string) {
  return level === 'red'
    ? 'var(--red)'
    : level === 'amber'
      ? 'var(--amber)'
      : level === 'green'
        ? 'var(--green)'
        : 'var(--line2)'
}

// ---------- 状态 chip ----------
function statusChip(s: string): string {
  if (s === '验证通过') return 'c-green'
  if (s === '未见效升级') return 'c-red'
  if (s === '已关闭') return 'c-gray'
  return 'c-amber' // 进行中
}

// ---------- 单条回查 ----------
const rechecking = ref<number | null>(null)
async function doRecheck(row: ItemRow) {
  rechecking.value = row.id
  try {
    const r = await pdcaApi.recheck(row.id)
    const icon = r.result === '通过' ? '✅' : r.result === '未达' ? '⚠️' : 'ℹ️'
    const type = r.result === '通过' ? 'success' : r.result === '未达' ? 'warning' : 'info'
    ElMessage({ message: `${icon} 回查结果:${r.result}｜${r.note}`, type, duration: 5000, showClose: true })
    await Promise.all([loadItems(), loadBoard()])
  } catch (e: any) {
    ElMessage.error(e?.message || '回查失败')
  } finally {
    rechecking.value = null
  }
}

// ---------- 批量到期回查 ----------
const batchLoading = ref(false)
async function doRecheckDue() {
  batchLoading.value = true
  try {
    const r = await pdcaApi.recheckDue()
    if (r.total === 0) {
      ElMessage.info('当前没有到期待回查的进行中任务')
    } else {
      ElMessage({
        message: `到期回查 ${r.total} 条：✅通过 ${r.passed}｜⚠️未达升级 ${r.failed}｜ℹ️需人工判定 ${r.manual}`,
        type: 'success',
        duration: 6000,
        showClose: true,
      })
    }
    await Promise.all([loadItems(), loadBoard()])
  } finally {
    batchLoading.value = false
  }
}

// ---------- 手动关闭 ----------
async function doClose(row: ItemRow) {
  try {
    const { value } = await ElMessageBox.prompt('关闭这条改进任务的原因(必填,留痕):', '手动关闭', {
      confirmButtonText: '确认关闭',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim() ? true : '必须写原因'),
    })
    await pdcaApi.close(row.id, value.trim())
    ElMessage.success('已关闭')
    await Promise.all([loadItems(), loadBoard()])
  } catch {
    /* 用户取消 */
  }
}

// ---------- 新建/编辑对话框 ----------
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<ItemSaveReq>({
  sourceScene: '盘点',
  problemDesc: '',
  measure: '',
  metricKey: '',
  metricParam: '',
  targetValue: undefined,
  compareOp: '<=',
  verifyDate: '',
})
const metricPreview = ref<MetricValue | null>(null)

const selectedMetric = computed(() => metrics.value.find((m) => m.key === form.metricKey))

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    sourceScene: '盘点',
    problemDesc: '',
    measure: '',
    verifyMetric: '',
    metricKey: '',
    metricParam: '',
    targetValue: undefined,
    compareOp: '<=',
    verifyDate: '',
    sourceRefType: null,
    sourceRefId: null,
    aiDraft: false,
  })
  metricPreview.value = null
  dialogVisible.value = true
}
function openEdit(row: ItemRow) {
  editingId.value = row.id
  Object.assign(form, {
    sourceScene: row.sourceScene,
    problemDesc: row.problemDesc,
    measure: row.measure,
    verifyMetric: row.verifyMetric,
    metricKey: row.metricKey || '',
    metricParam: row.metricParam || '',
    targetValue: row.targetValue ?? undefined,
    compareOp: row.compareOp || '<=',
    verifyDate: row.verifyDate,
    sourceRefType: row.sourceRefType,
    sourceRefId: row.sourceRefId,
    aiDraft: row.aiDraft,
  })
  metricPreview.value = null
  dialogVisible.value = true
  previewMetric()
}
function onMetricChange() {
  const def = selectedMetric.value
  if (def) form.compareOp = def.defaultOp || '<='
  form.metricParam = ''
  metricPreview.value = null
  if (def) previewMetric()
}
async function previewMetric() {
  if (!form.metricKey) {
    metricPreview.value = null
    return
  }
  metricPreview.value = await pdcaApi.metricValue(form.metricKey, form.metricParam || undefined)
}
async function submit() {
  if (!form.problemDesc.trim() || !form.measure.trim()) {
    ElMessage.warning('问题描述和改进措施必填')
    return
  }
  if (!form.verifyDate) {
    ElMessage.warning('验证日期必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await pdcaApi.update(editingId.value, { ...form })
      ElMessage.success('已保存')
    } else {
      await pdcaApi.create({ ...form })
      ElMessage.success('已登记改进任务')
    }
    dialogVisible.value = false
    await Promise.all([loadItems(), loadBoard()])
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const dueCount = computed(() => board.value?.dueCount ?? 0)
</script>

<template>
  <div v-loading="loading" class="ledger-page">
    <div class="crumb">园区小卖 ERP / 分析决策 / 改进循环</div>
    <div class="ptitle">
      <h2>改进循环 PDCA</h2>
      <span class="sub">每个环节:定目标 → 干 → 检查 → 改进 · 改了有没有用,下月见分晓</span>
    </div>

    <!-- ⚡任务来源说明 + 编号流程条(七律#2:领着人干活) -->
    <el-alert :type="dueCount > 0 ? 'warning' : 'info'" :closable="false" class="mb-12px">
      <template #title>
        <div class="flex items-center gap-8px flex-wrap">
          <span>⚡ 这页只回答两件事:<b>①六环节这个月的体检红绿灯;②所有改进措施到期了没、见效了没</b>。改进不再"说过就忘"。</span>
          <el-tag v-if="dueCount > 0" type="warning" size="small">⏰ {{ dueCount }} 条已到期待回查</el-tag>
        </div>
        <div class="flow-bar">
          <span>① C 检查:系统自动算本期指标</span>
          <span>② 红灯必产生改进任务(A)</span>
          <span>③ 任务带验证指标+期限</span>
          <span :class="{ cur: dueCount > 0 }">④ 到期自动回查:通过关闭 / 未达升级</span>
        </div>
      </template>
    </el-alert>

    <!-- 六环节体检看板 -->
    <el-card shadow="never" class="ledger-card mb-12px">
      <h3>
        六环节体检看板
        <span class="hint">C 检查指标 · 系统自动算({{ board?.period }})· 红灯必须产生改进任务</span>
      </h3>
      <div class="board-grid">
        <div
          v-for="s in board?.scenes || []"
          :key="s.scene"
          class="scene-card"
          :style="{ borderColor: lightBorder(s.light) }"
        >
          <div class="scene-head">
            <span class="scene-name">{{ s.icon }} {{ s.scene }}</span>
            <span class="chip" :class="LIGHT_CLASS[s.light]">{{ LIGHT_TEXT[s.light] }}灯</span>
          </div>
          <div class="scene-target mini">🎯 {{ s.target }}</div>
          <div v-for="ind in s.indicators" :key="ind.label" class="ind-row">
            <span class="ind-label">{{ ind.label }}</span>
            <span class="ind-val" :class="LIGHT_CLASS[ind.level]">{{ ind.display }}</span>
          </div>
          <div class="scene-foot">
            <span class="mini">进行中任务 {{ s.openItems }}</span>
            <span
              v-if="s.light === 'red' || s.light === 'amber'"
              class="mini"
              style="color: var(--amber)"
              >💡 {{ s.hint }}</span
            >
          </div>
        </div>
      </div>
    </el-card>

    <!-- 改进任务清单 -->
    <el-card shadow="never" class="ledger-card">
      <h3>
        改进任务清单
        <span class="hint">所有环节的 A 都落这里 · 带验证指标和期限 · 到期自动回查</span>
        <span style="margin-left: auto; display: inline-flex; gap: 8px">
          <el-button size="small" :loading="batchLoading" @click="doRecheckDue"
            >⏰ 一键回查到期任务</el-button
          >
          <el-button type="primary" size="small" @click="openCreate">＋ 手动登记</el-button>
        </span>
      </h3>

      <div class="flex items-center gap-12px mb-8px" style="margin-top: 10px">
        <el-select v-model="statusFilter" placeholder="全部状态" size="small" style="width: 140px" @change="loadItems" clearable>
          <el-option label="全部状态" value="" />
          <el-option v-for="s in ITEM_STATUSES" :key="s" :label="s" :value="s" />
        </el-select>
        <el-select v-model="sceneFilter" placeholder="全部环节" size="small" style="width: 130px" @change="loadItems" clearable>
          <el-option label="全部环节" value="" />
          <el-option v-for="s in SCENES" :key="s" :label="s" :value="s" />
        </el-select>
      </div>

      <el-table :data="items" size="small" style="margin-top: 6px" row-key="id">
        <el-table-column label="#" width="52">
          <template #default="{ row }">
            <span class="num">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="70">
          <template #default="{ row }">
            <span class="chip c-blue">{{ row.sourceScene }}</span>
          </template>
        </el-table-column>
        <el-table-column label="问题 → 改进措施" min-width="260">
          <template #default="{ row }">
            <div>{{ row.problemDesc }}</div>
            <div class="mini">→ {{ row.measure }}</div>
            <el-tag v-if="row.aiDraft" size="small" type="info" style="margin-top: 2px">🤖 AI 起草</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="验证指标" min-width="170">
          <template #default="{ row }">
            <div class="mini">{{ row.verifyMetric }}</div>
            <div v-if="row.verifyValue != null" class="mini" style="color: var(--ink2)">
              最近回查值:{{ row.verifyValue }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="验证日期" width="106">
          <template #default="{ row }">
            <span class="num" :style="row.due ? 'color:var(--red);font-weight:700' : ''">
              {{ row.due ? '⏰ ' : '' }}{{ row.verifyDate }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="chip" :class="statusChip(row.itemStatus)">{{ row.itemStatus }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.itemStatus === '进行中' || row.itemStatus === '未见效升级'">
              <el-button
                link
                type="primary"
                size="small"
                :loading="rechecking === row.id"
                @click="doRecheck(row)"
                >🔍 回查</el-button
              >
              <el-button link size="small" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="doClose(row)">关闭</el-button>
            </template>
            <span v-else class="mini">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-alert type="info" :closable="false" style="margin-top: 12px">
        <span class="mini">
          🤖 改进建议可由 AI 起草(基于 C 指标异常),老板一键采纳后进清单(AI 网关里程碑 4-5 接入);
          到期系统自动对比指标给出"通过 / 未见效 / 需人工判定"三种结论。
        </span>
      </el-alert>
      <p class="ledger-foot-note">— PDCA 的落地 = 一张改进任务表 + 到期自动回查,不新增任何管理负担 —</p>
    </el-card>

    <!-- 新建/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑改进任务' : '登记改进任务'"
      width="620px"
    >
      <el-form label-width="96px" size="small">
        <el-form-item label="来源环节">
          <el-select v-model="form.sourceScene" style="width: 160px">
            <el-option v-for="s in SCENES" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="问题描述">
          <el-input v-model="form.problemDesc" type="textarea" :rows="2" placeholder="如:过期报损集中在面包" />
        </el-form-item>
        <el-form-item label="改进措施">
          <el-input v-model="form.measure" type="textarea" :rows="2" placeholder="如:机内上限 24→12" />
        </el-form-item>
        <el-form-item label="验证指标">
          <el-select
            v-model="form.metricKey"
            style="width: 100%"
            clearable
            placeholder="(可空=到期需人工判定)"
            @change="onMetricChange"
          >
            <el-option
              v-for="m in metrics"
              :key="m.key"
              :label="`${m.name}(${m.unit})`"
              :value="m.key"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="selectedMetric?.paramHint" label="指标参数">
          <el-input
            v-model="form.metricParam"
            style="width: 100%"
            :placeholder="selectedMetric.paramHint"
            @blur="previewMetric"
          />
        </el-form-item>
        <el-form-item v-if="form.metricKey" label="目标值">
          <el-input-number v-model="form.targetValue" :precision="2" :step="1" style="width: 150px" />
          <el-select v-model="form.compareOp" style="width: 90px; margin-left: 8px">
            <el-option v-for="op in COMPARE_OPS" :key="op.value" :label="op.value" :value="op.value" />
          </el-select>
          <span class="mini" style="margin-left: 8px">
            {{ COMPARE_OPS.find((o) => o.value === form.compareOp)?.label }}
          </span>
        </el-form-item>
        <el-form-item v-if="metricPreview" label="指标现值">
          <span v-if="metricPreview.value != null" class="chip c-green">
            当前 {{ metricPreview.value }}{{ metricPreview.unit }} · {{ metricPreview.note }}
          </span>
          <span v-else class="chip c-gray">取不到:{{ metricPreview.note }}(到期将走"需人工判定")</span>
        </el-form-item>
        <el-form-item label="验证日期">
          <el-date-picker
            v-model="form.verifyDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="通常=下次月盘"
            style="width: 180px"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.board-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px;
  margin-top: 12px;
}
.scene-card {
  border: 1.5px solid var(--line2);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--paper, #fff);
}
.scene-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.scene-name {
  font-weight: 700;
  font-size: 13.5px;
}
.scene-target {
  margin: 4px 0 8px;
}
.ind-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 3px 0;
  border-top: 1px dashed var(--line2);
}
.ind-label {
  font-size: 12px;
  color: var(--ink2);
}
.ind-val {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
  font-family: var(--num);
}
.scene-foot {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--line2);
}
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
  background: var(--amber-soft);
  color: var(--amber);
  font-weight: 700;
}
</style>
