<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import {
  importsApi,
  initialApi,
  type CommitResp,
  type ImportBatch,
  type ImportError,
  type ImportFileType,
  type InitialStatusResp,
  type PreviewResp,
  type FixSuggestResp,
  type PriceChange,
  type Step1PreviewResp,
  type Step2PreviewResp,
  type Step3PreviewResp,
  type ValidateResp,
} from '@/api/imports'
import AliasPendingDrawer from '@/components/basedata/AliasPendingDrawer.vue'
import LlmTransparencyBadge from '@/components/ai/LlmTransparencyBadge.vue'

/**
 * 导入中心(M1-3):全系统数据入口。对照 mockup p5 导入部分。
 * 三通道卡片(上传→预览→确认)+ 批次历史(回滚/错误)+ 待绑定队列 + 改价待确认。
 */

interface Channel {
  type: ImportFileType
  icon: string
  title: string
  desc: string
  target: string
}

const channels: Channel[] = [
  {
    type: '出货明细',
    icon: '💰',
    title: '通道① 出货明细 → 销售记录',
    desc: '按订单号+订单类型去重,重复导入不怕;别名条码为主、名称兜底,不认识的进待绑定队列',
    target: 'sale_record',
  },
  {
    type: '系统补货记录',
    icon: '🚚',
    title: '通道② 补货记录 → 出库上架转移单',
    desc: '转移单唯一生产者;负数=取回逆向转移;自动冲抵手工预挂单;仓库不足亮"待补录采购"红灯',
    target: 'doc(出库上架)',
  },
  {
    type: '商品列表',
    icon: '🏷️',
    title: '通道③ 商品列表 → 别名初始化',
    desc: '后台商品编号+条码批量挂到 SKU(靠条码匹配);挂不上的进待绑定队列',
    target: 'sku_alias',
  },
]

// ---------- 上传 → 预览 → 确认 ----------

const preview = ref<PreviewResp | null>(null)
const previewVisible = ref(false)
const uploading = ref<string | null>(null)
const confirming = ref(false)
const lastResult = ref<CommitResp | null>(null)

function makeUploader(type: ImportFileType) {
  return async (options: UploadRequestOptions) => {
    uploading.value = type
    try {
      preview.value = await importsApi.upload(type, options.file as File)
      fixResult.value = null // 每次新上传清掉上次的自愈建议
      fixMap.value = {}
      previewVisible.value = true
    } finally {
      uploading.value = null
    }
  }
}

const previewColumns = computed(() => preview.value?.headers ?? [])

// ---------- 导入自愈(接入点#9 IMPORT_FIX)----------
const fixSuggesting = ref(false)
const fixApplying = ref(false)
const fixResult = ref<FixSuggestResp | null>(null)
// 用户在下拉里确认后的映射:期望列 → 文件实际表头
const fixMap = ref<Record<string, string>>({})

async function runFixSuggest(force = false) {
  if (!preview.value) return
  fixSuggesting.value = true
  try {
    const r = await importsApi.fixSuggest(preview.value.token, force)
    fixResult.value = r
    // 预填下拉:用 AI/规则的建议值
    const m: Record<string, string> = {}
    r.mappings.forEach((mp) => {
      if (mp.suggested) m[mp.expected] = mp.suggested
    })
    fixMap.value = m
  } finally {
    fixSuggesting.value = false
  }
}

async function applyFix() {
  if (!preview.value) return
  // 校验:必填列都得选了才让应用
  const missingReq = (fixResult.value?.mappings ?? []).filter(
    (mp) => mp.required && !fixMap.value[mp.expected],
  )
  if (missingReq.length) {
    ElMessage.warning(`还有必填列没对上:${missingReq.map((m) => m.expected).join('、')}`)
    return
  }
  fixApplying.value = true
  try {
    const resp = await importsApi.fixApply(preview.value.token, fixMap.value)
    preview.value = resp
    if (resp.columnsOk) {
      ElMessage.success('列映射已应用,现在可以确认导入了')
      fixResult.value = null
    } else {
      ElMessage.warning('还有必填列没对上,请继续调整')
    }
  } finally {
    fixApplying.value = false
  }
}

async function confirmImport() {
  if (!preview.value) return
  confirming.value = true
  try {
    const resp = await importsApi.confirm(preview.value.token)
    lastResult.value = resp
    previewVisible.value = false
    ElMessage.success(`批次 ${resp.batchNo} 导入完成:成功 ${resp.rowOk} · 重复跳过 ${resp.rowDup} · 失败 ${resp.rowFail}`)
    await loadBatches()
    if (resp.priceChangeCount > 0) openPriceDialog(resp.batchId)
  } finally {
    confirming.value = false
  }
}

// ---------- 批次历史 ----------

const batches = ref<ImportBatch[]>([])
const batchTotal = ref(0)
const batchPage = ref(1)
const batchLoading = ref(false)

async function loadBatches() {
  batchLoading.value = true
  try {
    const page = await importsApi.batches(batchPage.value, 10)
    batches.value = page.records
    batchTotal.value = page.total
  } finally {
    batchLoading.value = false
  }
}

async function doRollback(row: ImportBatch) {
  await ElMessageBox.confirm(
    `整批回滚「${row.batchNo}」?将撤销该批产生的销售记录/转移单/库存流水/机器快照。已被下游引用会拒绝。`,
    '确认回滚',
    { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '再想想' },
  )
  const resp = await importsApi.rollback(row.id)
  if (resp.success) {
    ElMessage.success(
      `已回滚:销售记录 -${resp.saleRemoved} · 单据作废 ${resp.docsVoided} · 流水 -${resp.ledgerRemoved} · 快照 -${resp.snapshotRemoved}`,
    )
  } else {
    await ElMessageBox.alert(resp.blockers.join('\n'), '已被下游引用,拒绝回滚', { type: 'error' })
  }
  await loadBatches()
}

async function doReprocess(row: ImportBatch) {
  const resp = await importsApi.reprocess(row.id)
  if (resp.scanned === 0) ElMessage.info('该批没有待绑定行')
  else if (resp.rebound > 0)
    ElMessage.success(`回补完成:扫描 ${resp.scanned} 行,回补 ${resp.rebound} 行,仍待绑定 ${resp.stillPending} 行`)
  else ElMessage.warning(`扫描 ${resp.scanned} 行,还没有可回补的绑定——先去「待绑定队列」绑定`)
}

// ---------- 行级错误 ----------

const errorsVisible = ref(false)
const errorRows = ref<ImportError[]>([])
const errorBatch = ref<ImportBatch | null>(null)

async function openErrors(row: ImportBatch) {
  errorBatch.value = row
  errorRows.value = (await importsApi.errors(row.id, 1, 200)).records
  errorsVisible.value = true
}

// ---------- 改价待确认 ----------

const priceVisible = ref(false)
const priceRows = ref<PriceChange[]>([])
const priceBatchId = ref<number | null>(null)
const priceChecked = ref<PriceChange[]>([])

async function openPriceDialog(batchId: number) {
  priceBatchId.value = batchId
  priceRows.value = await importsApi.priceChanges(batchId)
  priceChecked.value = []
  priceVisible.value = true
}

async function confirmPrices() {
  if (!priceBatchId.value || priceChecked.value.length === 0) {
    ElMessage.warning('先勾选要更新档案的改价项')
    return
  }
  const n = await importsApi.confirmPriceChanges(
    priceBatchId.value,
    priceChecked.value.map((r) => ({ productId: r.productId, newPrice: r.newPrice })),
  )
  ElMessage.success(`已更新 ${n} 个商品参考价并写入 price_log`)
  priceVisible.value = false
}

// ---------- 待绑定队列 ----------

const pendingVisible = ref(false)

// ---------- 期初导入向导(M1-6) ----------

const wizardVisible = ref(false)
const wizardStatus = ref<InitialStatusResp | null>(null)
const wizardStep = ref(0) // 0/1/2 = 三步,3 = 对平校验
const wizardUploading = ref(false)
const wizardConfirming = ref(false)
const step1Preview = ref<Step1PreviewResp | null>(null)
const step2Preview = ref<Step2PreviewResp | null>(null)
const step3Preview = ref<Step3PreviewResp | null>(null)
/** 一码多品冲突处理方案:code → split/first */
const resolutions = ref<Record<string, 'split' | 'first'>>({})
const expectedPurchase = ref<number>(27838.54)
const expectedSale = ref<number>(25113.5)
const validateResult = ref<ValidateResp | null>(null)

async function openWizard() {
  wizardVisible.value = true
  await refreshWizardStatus()
}

async function refreshWizardStatus() {
  wizardStatus.value = await initialApi.status()
  const s = wizardStatus.value
  wizardStep.value = !s.step1.done ? 0 : !s.step2.done ? 1 : !s.step3.done ? 2 : 3
}

function wizardUploader(step: 1 | 2 | 3) {
  return async (options: UploadRequestOptions) => {
    wizardUploading.value = true
    try {
      const resp = await initialApi.stepUpload(step, options.file as File)
      if (step === 1) {
        step1Preview.value = resp
        resolutions.value = {}
        for (const g of resp.conflicts) resolutions.value[g.code] = 'split'
      } else if (step === 2) {
        step2Preview.value = resp
      } else {
        step3Preview.value = resp
      }
    } finally {
      wizardUploading.value = false
    }
  }
}

async function confirmStep1() {
  if (!step1Preview.value) return
  wizardConfirming.value = true
  try {
    const r = await initialApi.step1Confirm(
      step1Preview.value.token,
      Object.entries(resolutions.value).map(([code, mode]) => ({ code, mode })),
    )
    ElMessage.success(
      `第①步完成:商品 +${r.productCreated}(拆分 ${r.splitProducts})· 机器 +${r.machineCreated} · 别名 ${r.aliasCreated}`,
    )
    step1Preview.value = null
    await refreshWizardStatus()
    await loadBatches()
  } finally {
    wizardConfirming.value = false
  }
}

async function confirmStep2() {
  if (!step2Preview.value) return
  wizardConfirming.value = true
  try {
    const r = await initialApi.step2Confirm(step2Preview.value.token)
    ElMessage.success(`第②步完成:期初单 ${r.docsCreated} 张 · 明细 ${r.itemCount} 行 · 金额 ¥${r.totalAmt}`)
    step2Preview.value = null
    await refreshWizardStatus()
    await loadBatches()
  } finally {
    wizardConfirming.value = false
  }
}

async function confirmStep3() {
  if (!step3Preview.value) return
  wizardConfirming.value = true
  try {
    const r = await initialApi.step3Confirm(step3Preview.value.token)
    ElMessage.success(`第③步完成:成功 ${r.rowOk} · 重复跳过 ${r.rowDup} · 失败 ${r.rowFail} · 待绑定 ${r.pendingBind}`)
    step3Preview.value = null
    await refreshWizardStatus()
    await loadBatches()
  } finally {
    wizardConfirming.value = false
  }
}

async function doValidate() {
  wizardConfirming.value = true
  try {
    validateResult.value = await initialApi.validate(expectedPurchase.value, expectedSale.value)
    if (validateResult.value.pass) {
      ElMessage.success('✅ 对平通过,期初完成!(op_log 已留痕)')
    } else {
      ElMessage.warning('对平未过,请核对差异')
    }
  } finally {
    wizardConfirming.value = false
  }
}

const fmtMoney = (v: number | null | undefined) =>
  v == null ? '—' : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

onMounted(loadBatches)

const statusChip = (s: string) => (s === '已导入' ? 'success' : s === '已回滚' ? 'info' : 'warning')
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / 日常台账 / 导入中心</div>
    <div class="ledger-title">
      <h2>导入中心</h2>
      <span class="sub">货流两段式:出货明细回流销售账,补货记录生成转移单,商品列表挂别名</span>
    </div>
    <!-- ⚡ 任务来源 + 编号流程条(设计思维律②:领着人干活) -->
    <el-alert type="info" :closable="false" class="mb-12px">
      <template #title>
        ⚡ 任务:每天早上导"截至昨日"后台数据(fanmaiji.top 导出)· 流程三步:
        <b>① 上传文件 → ② 核对预览确认入账 → ③ 处理差异</b>(待绑定 / 改价 / 负库存红灯)
      </template>
    </el-alert>

    <!-- 三通道卡片 -->
    <div class="grid grid-cols-3 gap-12px mb-12px">
      <el-card v-for="ch in channels" :key="ch.type" shadow="never">
        <div class="text-28px">{{ ch.icon }}</div>
        <div class="font-bold text-14px mt-4px">{{ ch.title }}</div>
        <div class="text-12px text-gray-500 mt-6px" style="min-height: 54px">{{ ch.desc }}</div>
        <div class="text-11px text-gray-400 mb-8px">落表:{{ ch.target }} · 原始文件自动归档 · 整批可回滚</div>
        <el-upload
          :show-file-list="false"
          accept=".xlsx"
          :http-request="makeUploader(ch.type)"
          drag
        >
          <div class="py-10px text-13px">
            <el-icon v-if="uploading === ch.type" class="is-loading"><i /></el-icon>
            {{ uploading === ch.type ? '解析中…' : '拖 .xlsx 到这里,或点击选择' }}
          </div>
        </el-upload>
      </el-card>
    </div>

    <!-- 期初导入向导入口(M1-6) -->
    <el-card shadow="never" class="mb-12px">
      <div class="flex items-center gap-12px flex-wrap">
        <div class="text-24px">🧭</div>
        <div style="flex: 1; min-width: 240px">
          <b>期初导入向导(上线一次性)</b>
          <div class="text-12px text-gray-500">
            老 Excel 进销存套表三步搬家:①商品档案+别名(一码多品清洗)→ ②历史采购(建加权成本历史)→
            ③历史销售 → 与老账对平才算「期初完成」
          </div>
        </div>
        <el-button type="primary" @click="openWizard">进入向导</el-button>
      </div>
    </el-card>

    <!-- 上次导入摘要 -->
    <el-card v-if="lastResult" shadow="never" class="mb-12px">
      <div class="flex items-center gap-16px flex-wrap text-13px">
        <b>上次导入 · {{ lastResult.batchNo }}({{ lastResult.fileType }})</b>
        <span>总 {{ lastResult.rowTotal }} 行</span>
        <el-tag type="success" size="small">成功 {{ lastResult.rowOk }}</el-tag>
        <el-tag type="info" size="small">重复跳过 {{ lastResult.rowDup }}</el-tag>
        <el-tag v-if="lastResult.rowFail" type="danger" size="small">失败 {{ lastResult.rowFail }}</el-tag>
        <el-tag v-if="lastResult.pendingBind" type="warning" size="small">
          待绑定 {{ lastResult.pendingBind }}
        </el-tag>
        <span v-if="lastResult.fileType === '系统补货记录'">
          生成转移单 {{ lastResult.docsCreated }} 张 · 冲抵预挂单 {{ lastResult.matchedPrePending }} · 快照 {{ lastResult.snapshots }}
        </span>
        <el-button v-if="lastResult.priceChangeCount" size="small" type="warning" plain @click="openPriceDialog(lastResult.batchId)">
          💲 改价待确认 {{ lastResult.priceChangeCount }} 条
        </el-button>
        <el-button v-if="lastResult.pendingBind" size="small" type="warning" plain @click="pendingVisible = true">
          ⚠️ 去绑定
        </el-button>
      </div>
      <el-alert
        v-for="ns in lastResult.negativeStock"
        :key="ns.productId"
        type="warning"
        :closable="false"
        class="mt-8px"
        :title="`⚠️ 「${ns.productName}(${ns.skuCode})」仓库账已负 ${ns.balance}——后台先卖了没录采购?去补录采购入库 →`"
      />
    </el-card>

    <!-- 批次历史 + 待绑定入口 -->
    <el-card shadow="never">
      <div class="flex items-center justify-between mb-8px">
        <b>批次历史</b>
        <div>
          <el-button size="small" @click="pendingVisible = true">⚠️ 待绑定队列</el-button>
          <el-button size="small" @click="loadBatches">刷新</el-button>
        </div>
      </div>
      <el-table :data="batches" v-loading="batchLoading" size="small">
        <el-table-column prop="batchNo" label="批次号" width="200">
          <template #default="{ row }">
            <span class="font-mono text-12px">{{ row.batchNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="fileType" label="类型" width="110" />
        <el-table-column prop="fileName" label="文件" min-width="160" show-overflow-tooltip />
        <el-table-column prop="periodRange" label="数据区间" width="130" />
        <el-table-column label="行数(总/成/败/重)" width="150">
          <template #default="{ row }">
            <span class="text-12px">
              {{ row.rowTotal }} /
              <b class="text-green-700">{{ row.rowOk }}</b> /
              <b :class="row.rowFail ? 'text-red-600' : ''">{{ row.rowFail }}</b> /
              {{ row.rowDup }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="statusChip(row.batchStatus)" size="small">{{ row.batchStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="导入时间" width="150" />
        <el-table-column label="操作" width="300">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openErrors(row)">错误明细</el-button>
            <el-button link type="warning" size="small" @click="openPriceDialog(row.id)">改价清单</el-button>
            <el-button
              v-if="row.fileType === '出货明细' && row.batchStatus === '已导入'"
              link
              type="success"
              size="small"
              @click="doReprocess(row)"
            >
              重处理待绑定
            </el-button>
            <el-button
              v-if="row.batchStatus === '已导入' && row.fileType !== '商品列表'"
              link
              type="danger"
              size="small"
              @click="doRollback(row)"
            >
              回滚
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="batchPage"
        :total="batchTotal"
        :page-size="10"
        layout="prev, pager, next, total"
        class="mt-8px"
        @current-change="loadBatches"
      />
    </el-card>

    <!-- 预览确认对话框(两步式第②步) -->
    <el-dialog v-model="previewVisible" :title="`预览核对 · ${preview?.fileName ?? ''}`" width="860px" top="4vh">
      <template v-if="preview">
        <div class="mb-8px text-13px">
          共 <b>{{ preview.rowTotal }}</b> 行(下方仅预览前 20 行)· 类型:{{ preview.fileType }}
        </div>
        <el-alert
          v-for="w in preview.warnings"
          :key="w"
          type="error"
          :title="w"
          :closable="false"
          class="mb-6px"
        />
        <div class="mb-8px">
          <el-tag
            v-for="c in preview.columnChecks"
            :key="c.expected"
            :type="c.found ? 'success' : c.required ? 'danger' : 'info'"
            size="small"
            class="mr-4px mb-4px"
          >
            {{ c.found ? '✓' : '✗' }} {{ c.expected }}{{ c.required ? '' : '(选填)' }}
          </el-tag>
        </div>

        <!-- 导入自愈(接入点#9):缺必填列时,AI 把文件表头对到系统期望列 -->
        <div v-if="!preview.columnsOk" class="fix-panel mb-10px">
          <div class="flex items-center gap-8px flex-wrap mb-6px">
            <span class="text-13px" style="color: var(--amber, #c97e2c)">
              🩹 厂家像是改了模板?让 AI 把你表里的列对到系统需要的列,不用改 Excel。
            </span>
            <el-button size="small" type="warning" plain :loading="fixSuggesting" @click="runFixSuggest(false)">
              {{ fixResult ? '重新猜' : 'AI 猜列映射' }}
            </el-button>
          </div>
          <template v-if="fixResult && fixResult.mappings.length">
            <div class="text-12px text-gray-500 mb-8px flex items-center gap-6px">
              {{ fixResult.mode }} · 置信 {{ Math.round((fixResult.confidence ?? 0) * 100) }}%
              <LlmTransparencyBadge v-if="fixResult.llmCallId != null" :call-id="fixResult.llmCallId" size="mini" />
            </div>
            <div
              v-for="mp in fixResult.mappings"
              :key="mp.expected"
              class="flex items-center gap-8px mb-6px flex-wrap"
            >
              <span class="text-13px" style="width: 130px">
                {{ mp.expected }}<span v-if="mp.required" style="color: var(--red, #c0392b)">*</span>
              </span>
              <span class="text-gray-400">←</span>
              <el-select
                v-model="fixMap[mp.expected]"
                size="small"
                clearable
                filterable
                placeholder="选文件里的表头"
                style="width: 210px"
              >
                <el-option v-for="h in preview.headers" :key="h" :label="h" :value="h" />
              </el-select>
              <span class="text-12px text-gray-400">{{ mp.reason }}</span>
            </div>
            <el-button size="small" type="primary" :loading="fixApplying" @click="applyFix">
              按此映射重新校验 →
            </el-button>
          </template>
        </div>

        <div style="max-height: 46vh; overflow: auto">
          <el-table :data="preview.previewRows" size="small" border>
            <el-table-column
              v-for="h in previewColumns"
              :key="h"
              :prop="h"
              :label="h"
              min-width="110"
              show-overflow-tooltip
            />
          </el-table>
        </div>
      </template>
      <template #footer>
        <el-button @click="previewVisible = false">取消(不入账)</el-button>
        <el-button type="primary" :disabled="!preview?.columnsOk" :loading="confirming" @click="confirmImport">
          确认导入 → 入账
        </el-button>
      </template>
    </el-dialog>

    <!-- 行级错误 -->
    <el-dialog v-model="errorsVisible" :title="`行级错误 · ${errorBatch?.batchNo ?? ''}`" width="760px">
      <el-table :data="errorRows" size="small" max-height="500">
        <el-table-column prop="rowNo" label="行号" width="70" />
        <el-table-column prop="errorType" label="类型" width="110" />
        <el-table-column prop="errorMsg" label="原因" min-width="240" show-overflow-tooltip />
        <el-table-column prop="rawContent" label="原始内容" min-width="200" show-overflow-tooltip />
        <template #empty>
          <el-empty description="本批没有失败行 ✓" :image-size="60" />
        </template>
      </el-table>
    </el-dialog>

    <!-- 改价待确认清单(P2-9:确认后更新档案+写 price_log) -->
    <el-dialog v-model="priceVisible" title="💲 改价待确认(成交价 ≠ 档案参考价)" width="640px">
      <p class="text-12px text-gray-500 mt-0">
        勾选后「确认更新」会改商品档案参考价并写 price_log(喂定价 PDCA);不勾 = 保持档案价不变。
      </p>
      <el-table :data="priceRows" size="small" @selection-change="(v: PriceChange[]) => (priceChecked = v)">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="skuCode" label="编码" width="90" />
        <el-table-column prop="productName" label="商品" min-width="150" />
        <el-table-column label="档案价" width="90">
          <template #default="{ row }">¥{{ row.refPrice }}</template>
        </el-table-column>
        <el-table-column label="侦测到" width="90">
          <template #default="{ row }">
            <b class="text-amber-600">¥{{ row.newPrice }}</b>
          </template>
        </el-table-column>
        <el-table-column prop="rowCount" label="依据行数" width="90" />
        <template #empty>
          <el-empty description="本批没有改价差异 ✓" :image-size="60" />
        </template>
      </el-table>
      <template #footer>
        <el-button @click="priceVisible = false">先不改</el-button>
        <el-button type="primary" @click="confirmPrices">确认更新档案 + 写 price_log</el-button>
      </template>
    </el-dialog>

    <!-- 待绑定队列(复用 basedata 组件;绑定后回来点该批"重处理待绑定") -->
    <AliasPendingDrawer v-model:visible="pendingVisible" @changed="loadBatches" />

    <!-- 期初导入向导 -->
    <el-dialog v-model="wizardVisible" title="🧭 期初导入向导(三步 + 对平)" width="900px" top="4vh">
      <el-steps :active="wizardStep" finish-status="success" align-center class="mb-16px">
        <el-step title="① 商品档案+别名" description="一码多品清洗" />
        <el-step title="② 历史采购" description="建加权成本历史" />
        <el-step title="③ 历史销售" description="复用通道1去重" />
        <el-step title="④ 对平校验" description="与老账数字相符" />
      </el-steps>

      <el-alert type="info" :closable="false" class="mb-12px">
        <template #title>
          三步都上传<b>同一个老 Excel 套表原文件</b>(自动按 sheet 名取「商品档案 / 配比底稿 / 采购入库表 / 销售明细」),不用改造文件。
          已完成的步骤要重做:先到批次历史整批回滚,再回向导重导。
        </template>
      </el-alert>

      <!-- 第①步 -->
      <template v-if="wizardStep === 0">
        <div v-if="wizardStatus?.step1.done" class="text-13px mb-8px">
          ✅ 已完成(批次 {{ wizardStatus.step1.batchNo }})
        </div>
        <el-upload v-if="!step1Preview" :show-file-list="false" accept=".xlsx" :http-request="wizardUploader(1)" drag>
          <div class="py-16px text-13px">
            {{ wizardUploading ? '解析中…' : '拖老 Excel 套表到这里(读「商品档案」+「配比底稿」+「销售明细」)' }}
          </div>
        </el-upload>
        <template v-else>
          <div class="text-13px mb-8px">
            解析结果:商品 <b>{{ step1Preview.productCount }}</b> · 别名映射 <b>{{ step1Preview.aliasCount }}</b> ·
            机器 <b>{{ step1Preview.machineCount }}</b>
            <span v-if="step1Preview.autoCreateCodes.length" class="text-amber-600">
              · 档案缺失自动补建:{{ step1Preview.autoCreateCodes.join('、') }}
            </span>
          </div>
          <el-alert v-for="w in step1Preview.warnings" :key="w" type="warning" :title="w" :closable="false" class="mb-6px" />
          <template v-if="step1Preview.conflicts.length">
            <el-alert type="error" :closable="false" class="mb-8px"
              :title="`⚠️ 检出 ${step1Preview.conflicts.length} 组一码多品(老账硬伤)——每组选处理方案,不处理整批不放行`" />
            <el-table :data="step1Preview.conflicts" size="small" class="mb-8px">
              <el-table-column prop="code" label="原编码" width="90" />
              <el-table-column label="同码挂了多个商品" min-width="240">
                <template #default="{ row }">
                  <div v-for="(n, i) in row.names" :key="n" class="text-12px">
                    {{ n }} <span v-if="resolutions[row.code] === 'split'" class="text-green-700">→ {{ row.splitCodes[i] }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="处理方案" width="280">
                <template #default="{ row }">
                  <el-radio-group v-model="resolutions[row.code]" size="small">
                    <el-radio-button value="split">拆分为新码(推荐)</el-radio-button>
                    <el-radio-button value="first">取首行</el-radio-button>
                  </el-radio-group>
                </template>
              </el-table-column>
            </el-table>
          </template>
          <div class="text-right">
            <el-button @click="step1Preview = null">重新上传</el-button>
            <el-button type="primary" :loading="wizardConfirming" @click="confirmStep1">
              确认第①步 → 建档
            </el-button>
          </div>
        </template>
      </template>

      <!-- 第②步 -->
      <template v-else-if="wizardStep === 1">
        <el-upload v-if="!step2Preview" :show-file-list="false" accept=".xlsx" :http-request="wizardUploader(2)" drag>
          <div class="py-16px text-13px">
            {{ wizardUploading ? '解析中…' : '再拖同一个套表文件(读「采购入库表」→ 生成期初采购单据并过账)' }}
          </div>
        </el-upload>
        <template v-else>
          <div class="text-13px mb-8px">
            解析结果:<b>{{ step2Preview.rowCount }}</b> 行 · 总数量 <b>{{ step2Preview.totalQty }}</b> ·
            总金额 <b class="text-green-700">¥{{ fmtMoney(step2Preview.totalAmt) }}</b> ·
            入库日 {{ step2Preview.dates.join(' / ') }} · 供应商 {{ step2Preview.supplierNames.join('、') }}
          </div>
          <el-alert v-for="w in step2Preview.warnings" :key="w" type="error" :title="w" :closable="false" class="mb-6px" />
          <div v-if="step2Preview.missingProducts.length" class="text-12px text-red-600 mb-8px">
            档案缺失:{{ step2Preview.missingProducts.slice(0, 8).join('、') }}
            <span v-if="step2Preview.missingProducts.length > 8">…共 {{ step2Preview.missingProducts.length }} 个</span>
          </div>
          <div class="text-right">
            <el-button @click="step2Preview = null">重新上传</el-button>
            <el-button type="primary" :disabled="step2Preview.missingProducts.length > 0"
              :loading="wizardConfirming" @click="confirmStep2">
              确认第②步 → 期初单过账
            </el-button>
          </div>
        </template>
      </template>

      <!-- 第③步 -->
      <template v-else-if="wizardStep === 2">
        <el-upload v-if="!step3Preview" :show-file-list="false" accept=".xlsx" :http-request="wizardUploader(3)" drag>
          <div class="py-16px text-13px">
            {{ wizardUploading ? '解析中…' : '再拖同一个套表文件(读「销售明细」→ 复用通道1入销售记录,订单号去重)' }}
          </div>
        </el-upload>
        <template v-else>
          <div class="text-13px mb-8px">
            解析结果:<b>{{ step3Preview.rowCount }}</b> 行 ·
            总实收 <b class="text-green-700">¥{{ fmtMoney(step3Preview.totalAmt) }}</b>
          </div>
          <div class="text-right">
            <el-button @click="step3Preview = null">重新上传</el-button>
            <el-button type="primary" :loading="wizardConfirming" @click="confirmStep3">
              确认第③步 → 入销售记录
            </el-button>
          </div>
        </template>
      </template>

      <!-- 第④步:对平校验 -->
      <template v-else>
        <div class="text-13px mb-12px">
          三步已完成 ✅ 系统当前:总采购额
          <b>¥{{ fmtMoney(wizardStatus?.systemPurchaseTotal) }}</b> · 总销售额
          <b>¥{{ fmtMoney(wizardStatus?.systemSaleTotal) }}</b> —— 与老账数字对平(±0.5 元)才算期初完成。
        </div>
        <div class="flex items-center gap-12px flex-wrap mb-12px">
          <span class="text-13px">老账采购总额</span>
          <el-input-number v-model="expectedPurchase" :precision="2" :controls="false" style="width: 140px" />
          <span class="text-13px">老账销售总额</span>
          <el-input-number v-model="expectedSale" :precision="2" :controls="false" style="width: 140px" />
          <el-button type="primary" :loading="wizardConfirming" @click="doValidate">对平校验</el-button>
        </div>
        <template v-if="validateResult">
          <el-table :data="[
            { item: '采购总额', sys: validateResult.systemPurchase, exp: validateResult.expectedPurchase, diff: validateResult.purchaseDiff, pass: validateResult.purchasePass },
            { item: '销售总额', sys: validateResult.systemSale, exp: validateResult.expectedSale, diff: validateResult.saleDiff, pass: validateResult.salePass },
          ]" size="small" class="mb-8px">
            <el-table-column prop="item" label="口径" width="100" />
            <el-table-column label="系统数" align="right" width="130">
              <template #default="{ row }">¥{{ fmtMoney(row.sys) }}</template>
            </el-table-column>
            <el-table-column label="老账数" align="right" width="130">
              <template #default="{ row }">¥{{ fmtMoney(row.exp) }}</template>
            </el-table-column>
            <el-table-column label="差异" align="right" width="110">
              <template #default="{ row }">
                <b :class="row.pass ? 'text-green-700' : 'text-red-600'">{{ fmtMoney(row.diff) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="判定" width="100">
              <template #default="{ row }">
                <el-tag :type="row.pass ? 'success' : 'danger'" size="small">{{ row.pass ? '✓ 相符' : '✗ 不符' }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-alert v-if="validateResult.pass" type="success" :closable="false"
            title="🎉 期初完成!加权成本历史已建立,去「报表」页看毛利、去「库存」页看两级库存。" />
          <el-alert v-else type="warning" :closable="false"
            title="对不平:逐 SKU 与老账毛利对照表比对找原因(常见:漏行/一码多品选错/供应商行遗漏),必要时回滚该步重导。" />
        </template>
      </template>

      <template #footer>
        <span class="text-12px text-gray-400 mr-12px" v-if="wizardStatus">
          进度:① {{ wizardStatus.step1.done ? '✅' : '待做' }} · ② {{ wizardStatus.step2.done ? '✅' : '待做' }} ·
          ③ {{ wizardStatus.step3.done ? '✅' : '待做' }}
        </span>
        <el-button @click="wizardVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>
