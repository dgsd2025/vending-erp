<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import {
  importsApi,
  type CommitResp,
  type ImportBatch,
  type ImportError,
  type ImportFileType,
  type PreviewResp,
  type PriceChange,
} from '@/api/imports'
import AliasPendingDrawer from '@/components/basedata/AliasPendingDrawer.vue'

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
      previewVisible.value = true
    } finally {
      uploading.value = null
    }
  }
}

const previewColumns = computed(() => preview.value?.headers ?? [])

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

onMounted(loadBatches)

const statusChip = (s: string) => (s === '已导入' ? 'success' : s === '已回滚' ? 'info' : 'warning')
</script>

<template>
  <div>
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
  </div>
</template>
