<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import {
  commitProductImport,
  downloadProductImportTemplate,
  parseProductImport,
  type ProductImportCommitResp,
  type ProductImportRow,
} from '@/api/basedata'

/**
 * 商品建档导入:选中附件立刻解析,解析结果落到下面的列表里,能改能删,确认后才入档。
 *
 * 与导入中心通道③的分工:通道③按条码给「已有商品」挂别名,挂不上进待绑队列;
 * 这里反过来先把档案建出来,顺手绑上后台编号+条码,并消掉队列里对得上的待绑条目。
 *
 * 校验的唯一真相源在服务端:这里不复刻判重/判数字的规则,提交后失败行原样回填、
 * 带服务端给的原因留在表里继续改(与导入中心「修改重导」同一套路)。
 */
const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'saved'): void
}>()

const parsing = ref(false)
const saving = ref(false)
const fileName = ref('')
const rows = ref<ProductImportRow[]>([])
const warnings = ref<string[]>([])
const pendingHit = ref(0)
const onlyError = ref(false)
const lastResult = ref<ProductImportCommitResp | null>(null)

const createCount = computed(() => rows.value.filter((r) => r.action === '新建').length)
const updateCount = computed(() => rows.value.filter((r) => r.action === '更新').length)
const errorCount = computed(() => rows.value.filter((r) => r.action === '错误').length)
const shownRows = computed(() => (onlyError.value ? rows.value.filter((r) => r.action === '错误') : rows.value))

function reset() {
  fileName.value = ''
  rows.value = []
  warnings.value = []
  pendingHit.value = 0
  onlyError.value = false
  lastResult.value = null
}

watch(
  () => props.visible,
  (show) => {
    if (show) reset()
  },
)

function close() {
  emit('update:visible', false)
}

/** 选中文件即解析,不用再点一次"开始解析" */
async function onPick(file: UploadFile) {
  const raw = file.raw
  if (!raw) return
  if (!/\.xlsx$/i.test(raw.name)) {
    ElMessage.error('只支持 .xlsx(老的 .xls 请用 Excel 另存为 xlsx)')
    return
  }
  parsing.value = true
  lastResult.value = null
  try {
    const resp = await parseProductImport(raw)
    fileName.value = resp.fileName
    rows.value = resp.rows
    warnings.value = resp.warnings || []
    pendingHit.value = resp.pendingHitCount
    onlyError.value = false
    ElMessage.success(
      `解析完成:共 ${resp.rowTotal} 行 · 新建 ${resp.createCount} · 更新 ${resp.updateCount}` +
        (resp.errorCount ? ` · 有问题 ${resp.errorCount}` : ''),
    )
  } catch {
    // 解析失败(缺必填列等)由 request 拦截器统一弹消息,这里保持列表原样
  } finally {
    parsing.value = false
  }
}

function removeRow(row: ProductImportRow) {
  rows.value = rows.value.filter((r) => r !== row)
}

async function submit() {
  if (!rows.value.length) {
    ElMessage.warning('先选一个 Excel 文件')
    return
  }
  saving.value = true
  try {
    const resp = await commitProductImport(rows.value)
    lastResult.value = resp
    const tail =
      (resp.aliasBound ? ` · 绑别名 ${resp.aliasBound}` : '') +
      (resp.pendingCleared ? ` · 消掉待绑 ${resp.pendingCleared}` : '')
    if (resp.failed > 0) {
      // 失败行留在表里继续改(服务端给的原因已回填),成功的那些已经入档
      const failedCodes = new Set(resp.errors.map((e) => e.skuCode))
      rows.value = rows.value
        .filter((r) => failedCodes.has(r.skuCode))
        .map((r) => ({
          ...r,
          action: '错误' as const,
          errorMsg: resp.errors.find((e) => e.skuCode === r.skuCode)?.message ?? r.errorMsg,
        }))
      onlyError.value = false
      pendingHit.value = 0 // 这批待绑已经消过了,别再顶着解析时的预估数
      ElMessage.warning(
        `已入档 ${resp.created + resp.updated} 条(新建 ${resp.created}/更新 ${resp.updated})${tail};` +
          `还有 ${resp.failed} 条没过,原因已标在下面,改完再点一次导入。`,
      )
      emit('saved')
      return
    }
    ElMessage.success(`导入完成:新建 ${resp.created} · 更新 ${resp.updated}${tail}`)
    emit('saved')
    close()
  } finally {
    saving.value = false
  }
}

function rowClass({ row }: { row: ProductImportRow }) {
  return row.action === '错误' ? 'row-error' : ''
}

function actionChip(action?: string) {
  if (action === '新建') return 'c-green'
  if (action === '更新') return 'c-blue'
  return 'c-red'
}
</script>

<template>
  <el-dialog
    :model-value="props.visible"
    title="⬆ 导入商品列表(建档)"
    width="1100px"
    top="6vh"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <!-- ⚡ 任务来源 + 流程条(七律#2:要人干活的页面必须说清谁生成、干到第几步) -->
    <p class="mini" style="margin-top: 0">
      来源:厂家后台「商品列表」导出,或按模板自己填。
      <b>①选文件(自动解析)→ ②在下面核对/改错 → ③确认入档</b>。
      编号已存在的走<b>更新</b>(表里给了值的字段才覆盖);建档同时把「后台编号+条码」绑成别名,
      待绑队列里对得上的会一起消掉。
    </p>

    <div class="ledger-card" style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
      <el-upload
        :auto-upload="false"
        :show-file-list="false"
        accept=".xlsx"
        :on-change="onPick"
      >
        <el-button type="primary" :loading="parsing">
          {{ rows.length ? '重新选文件' : '选择 Excel 文件' }}
        </el-button>
      </el-upload>
      <el-button link type="primary" @click="downloadProductImportTemplate()">⬇ 下载模板</el-button>
      <span v-if="fileName" class="mini">{{ fileName }}</span>
      <template v-if="rows.length">
        <span class="chip c-gray">共 {{ rows.length }} 行</span>
        <span class="chip c-green">新建 {{ createCount }}</span>
        <span class="chip c-blue">更新 {{ updateCount }}</span>
        <span v-if="errorCount" class="chip c-red">有问题 {{ errorCount }}</span>
        <span v-if="pendingHit" class="chip c-amber">顺带消待绑 {{ pendingHit }}</span>
        <el-checkbox v-if="errorCount" v-model="onlyError" style="margin-left: auto">只看有问题的行</el-checkbox>
      </template>
    </div>

    <el-alert
      v-for="w in warnings"
      :key="w"
      :title="w"
      type="warning"
      :closable="false"
      style="margin-bottom: 6px"
    />

    <div v-if="!rows.length" class="ledger-card">
      <el-empty
        :description="parsing ? '正在解析…' : '选一个 .xlsx,解析结果会直接落到这里,可以改完再入档'"
        :image-size="70"
      />
    </div>

    <el-table
      v-else
      :data="shownRows"
      v-loading="parsing"
      size="small"
      max-height="440"
      :row-class-name="rowClass"
    >
      <el-table-column label="行" width="52" align="right">
        <template #default="{ row }"><span class="mini num">{{ row.rowNo ?? '—' }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="86">
        <template #default="{ row }">
          <el-tooltip v-if="row.errorMsg" :content="row.errorMsg" placement="top">
            <span class="chip" :class="actionChip(row.action)">{{ row.action }}</span>
          </el-tooltip>
          <span v-else class="chip" :class="actionChip(row.action)">{{ row.action }}</span>
        </template>
      </el-table-column>
      <el-table-column label="商品编号 *" width="120">
        <template #default="{ row }"><el-input v-model="row.skuCode" size="small" /></template>
      </el-table-column>
      <el-table-column label="商品名称 *" min-width="190">
        <template #default="{ row }"><el-input v-model="row.productName" size="small" /></template>
      </el-table-column>
      <el-table-column label="条码" width="140">
        <template #default="{ row }"><el-input v-model="row.barcode" size="small" /></template>
      </el-table-column>
      <el-table-column label="分类" width="90">
        <template #default="{ row }"><el-input v-model="row.category" size="small" /></template>
      </el-table-column>
      <el-table-column label="单位" width="70">
        <template #default="{ row }"><el-input v-model="row.unit" size="small" /></template>
      </el-table-column>
      <el-table-column label="箱规" width="80">
        <template #default="{ row }"><el-input v-model="row.boxSpec" size="small" type="number" /></template>
      </el-table-column>
      <el-table-column label="保质期(天)" width="96">
        <template #default="{ row }"><el-input v-model="row.shelfLifeDays" size="small" type="number" /></template>
      </el-table-column>
      <el-table-column label="参考成本" width="90">
        <template #default="{ row }"><el-input v-model="row.refCost" size="small" type="number" /></template>
      </el-table-column>
      <el-table-column label="参考售价" width="90">
        <template #default="{ row }"><el-input v-model="row.refPrice" size="small" type="number" /></template>
      </el-table-column>
      <el-table-column label="机内上限" width="90">
        <template #default="{ row }"><el-input v-model="row.minDisplayQty" size="small" type="number" /></template>
      </el-table-column>
      <el-table-column label="操作" width="60" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="removeRow(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有符合筛选的行" :image-size="60" />
      </template>
    </el-table>

    <p v-if="rows.length" class="mini" style="margin-bottom: 0">
      有问题的行会被跳过,其余照常入档;想改哪一格直接在上表里改。
      入档走的是商品档案的正规口径 —— 谁改的、改了什么记 op_log,售价变动写 price_log。
    </p>

    <template #footer>
      <el-button @click="close">关闭</el-button>
      <el-button type="primary" :loading="saving" :disabled="!rows.length" @click="submit">
        确认入档(新建 {{ createCount }} / 更新 {{ updateCount }})
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:deep(.row-error) {
  background: #fef2f2;
}
</style>
