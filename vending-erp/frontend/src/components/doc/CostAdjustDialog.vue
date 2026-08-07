<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  costAdjustExecute, costAdjustPreview, getDocDetail,
  type CostAdjustPreview, type DocDetail,
} from '@/api/doc'

/**
 * 成本调整对话框(M1-7,P0-1 单价错场景 · 附录C):
 * 从采购入库单详情进 → 勾选录错的明细行 → 输入正确单价 → 预览未售/已售切分 → 执行。
 * 未售部分:ledger 插 qty=0/amount=Δ 调整流水(调存货金额);
 * 已售部分:不追溯,记入单据备注 + pl_line='成本调整'(M3 利润表行承接)。
 */

const props = defineProps<{
  docId: number | null
  /** productId → 展示名(父页有商品名时传入,如「SP001 · 可乐」);缺省显示 SKU#id */
  productNames?: Record<number, string>
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ (e: 'done'): void }>()

interface EditRow {
  docItemId: number
  productLabel: string
  qty: number
  wrongPrice: number
  selected: boolean
  correctPrice: number | null
}

const loading = ref(false)
const step = ref<'pick' | 'preview'>('pick')
const detail = ref<DocDetail | null>(null)
const rows = ref<EditRow[]>([])
const preview = ref<CostAdjustPreview | null>(null)
const remark = ref('')
const executing = ref(false)

watch(visible, async (v) => {
  if (!v || !props.docId) return
  step.value = 'pick'
  preview.value = null
  remark.value = ''
  loading.value = true
  try {
    detail.value = await getDocDetail(props.docId)
    rows.value = detail.value.items.map((i) => ({
      docItemId: i.id,
      productLabel: props.productNames?.[i.productId] ?? `SKU#${i.productId}`,
      qty: Number(i.qty),
      wrongPrice: Number(i.unitPrice ?? 0),
      selected: false,
      correctPrice: null,
    }))
  } finally {
    loading.value = false
  }
})

const pickedLines = computed(() =>
  rows.value
    .filter((r) => r.selected && r.correctPrice != null && r.correctPrice > 0)
    .map((r) => ({ docItemId: r.docItemId, correctPrice: r.correctPrice! })))

async function doPreview() {
  if (!props.docId) return
  if (!pickedLines.value.length) return ElMessage.warning('请勾选至少一行并填入正确单价')
  const same = rows.value.find((r) => r.selected && r.correctPrice === r.wrongPrice)
  if (same) return ElMessage.warning(`${same.productLabel} 正确单价与原单价相同,无需调整`)
  loading.value = true
  try {
    preview.value = await costAdjustPreview(props.docId, pickedLines.value)
    step.value = 'preview'
  } finally {
    loading.value = false
  }
}

async function doExecute() {
  if (!props.docId) return
  executing.value = true
  try {
    await costAdjustExecute(props.docId, pickedLines.value, remark.value.trim() || undefined)
    ElMessage.success('成本调整单已过账:未售部分已调存货金额(qty=0 流水),已售部分留待利润表成本调整行(M3)')
    visible.value = false
    emit('done')
  } finally {
    executing.value = false
  }
}

function n(v: number | string | undefined | null): number {
  return Number(v ?? 0)
}
</script>

<template>
  <el-dialog v-model="visible" width="760px" :close-on-click-modal="false">
    <template #header>
      <b>🧮 成本调整(单价错) · {{ detail?.head.docNo || '' }}</b>
      <span class="mini" style="margin-left: 10px">数量没错只是价格录错 → 不用红冲,开成本调整单</span>
    </template>

    <div v-loading="loading">
      <!-- 第一步:选错误行 + 输入正确单价 -->
      <template v-if="step === 'pick'">
        <table class="entry-table" style="width: 100%">
          <thead>
            <tr><th style="width: 40px" /><th>商品</th><th>数量</th><th>录错的单价 ¥</th><th>正确单价 ¥</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.docItemId">
              <td><el-checkbox v-model="row.selected" /></td>
              <td>{{ row.productLabel }}</td>
              <td><span class="num">{{ row.qty }}</span></td>
              <td><span class="num" :style="row.selected ? 'text-decoration: line-through; color: var(--red)' : ''">{{ row.wrongPrice.toFixed(2) }}</span></td>
              <td>
                <el-input-number
                  v-model="row.correctPrice" :disabled="!row.selected"
                  :min="0.0001" :precision="2" :controls="false" style="width: 110px" placeholder="正确价"
                />
              </td>
            </tr>
          </tbody>
        </table>
        <p class="mini" style="margin-top: 10px">
          💡 口径(P0-1/附录C):<b>未售部分</b>按 Δ单价 调存货金额(qty=0 流水,加权成本随之变);
          <b>已售部分不追溯</b>,金额进利润表「成本调整」行(M3 实装)。数量录错请走「红冲」。
        </p>
      </template>

      <!-- 第二步:预览未售/已售切分 -->
      <template v-else-if="preview">
        <div class="ledger-card">
          <h3>📋 调整明细 <span class="hint">已售 = 原单业务日后销量(正常+兑换),FIFO 近似</span></h3>
          <el-table :data="preview.lines" size="small">
            <el-table-column label="商品" min-width="130">
              <template #default="{ row }">{{ row.skuCode }} · {{ row.productName || row.productId }}</template>
            </el-table-column>
            <el-table-column label="单价修正" width="120" align="right">
              <template #default="{ row }">
                <span class="num">{{ n(row.wrongPrice).toFixed(2) }} → {{ n(row.correctPrice).toFixed(2) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="未售/已售" width="90" align="right">
              <template #default="{ row }"><span class="num">{{ n(row.unsoldQty) }} / {{ n(row.soldQty) }}</span></template>
            </el-table-column>
            <el-table-column label="调存货(未售)" width="110" align="right">
              <template #default="{ row }">
                <b class="num" :style="n(row.unsoldAdjustAmount) < 0 ? 'color: var(--green)' : 'color: var(--red)'">
                  {{ n(row.unsoldAdjustAmount) > 0 ? '+' : '' }}¥{{ n(row.unsoldAdjustAmount).toFixed(2) }}
                </b>
              </template>
            </el-table-column>
            <el-table-column label="已售不追溯" width="110" align="right">
              <template #default="{ row }">
                <span class="num">{{ n(row.soldAdjustAmount) > 0 ? '+' : '' }}¥{{ n(row.soldAdjustAmount).toFixed(2) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <p class="mini" style="margin: 8px 0 0">
            合计:调存货 <b class="num">{{ n(preview.unsoldAdjustTotal) > 0 ? '+' : '' }}¥{{ n(preview.unsoldAdjustTotal).toFixed(2) }}</b>
            · 已售进利润表成本调整行 <b class="num">{{ n(preview.soldAdjustTotal) > 0 ? '+' : '' }}¥{{ n(preview.soldAdjustTotal).toFixed(2) }}</b>
          </p>
          <p class="mini">{{ preview.note }}</p>
        </div>
        <el-input v-model="remark" placeholder="备注(可空,如:供应商发票核对后更正)" />
      </template>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button v-if="step === 'pick'" type="primary" @click="doPreview">下一步:预览调整</el-button>
      <template v-else>
        <el-button @click="step = 'pick'">← 返回改价</el-button>
        <el-button type="primary" :loading="executing" @click="doExecute">✓ 生成成本调整单并过账</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.entry-table {
  border-collapse: collapse;
}

.entry-table th {
  text-align: left;
  font-size: 11.5px;
  color: var(--ink2);
  font-weight: 400;
  padding: 4px 6px;
  border-bottom: 1px solid var(--line);
}

.entry-table td {
  padding: 6px;
  border-bottom: 1px dashed var(--line);
  font-size: 13px;
}
</style>
