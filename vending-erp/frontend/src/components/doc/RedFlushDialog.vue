<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { redFlushExecute, redFlushPreview, type RedFlushPreview } from '@/api/doc'

/**
 * 红冲影响清单确认对话框(M1-7,P0-1 / 穿行场景10)。
 * 铁律:红冲必过影响清单——先展示 库存变动/应付影响/毛利影响,用户确认后才真执行;
 * 有下游动作(被冲抵预挂单/已进结算链/已被红冲)直接列拦截原因,不给执行按钮;
 * 原单在锁账线内 → 需老板越权(占位)+强制备注。
 */

const props = defineProps<{ docId: number | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ (e: 'done'): void }>()

const loading = ref(false)
const executing = ref(false)
const preview = ref<RedFlushPreview | null>(null)
const reason = ref('')
const bossOverride = ref(false)

watch(visible, async (v) => {
  if (!v || !props.docId) return
  preview.value = null
  reason.value = ''
  bossOverride.value = false
  loading.value = true
  try {
    preview.value = await redFlushPreview(props.docId)
  } finally {
    loading.value = false
  }
})

async function doExecute() {
  if (!props.docId || !preview.value) return
  if (!reason.value.trim()) return ElMessage.warning('请填写红冲原因(必填)')
  if (preview.value.lockedPeriod && !bossOverride.value)
    return ElMessage.warning(`原单入账月 ${preview.value.bookPeriod} 已锁账,需勾选「老板越权」才能红冲`)
  executing.value = true
  try {
    await redFlushExecute(props.docId, reason.value.trim(), bossOverride.value)
    ElMessage.success(`已红冲 ${preview.value.originDocNo}:反向单已过账(免负库存拦截),原单转「已红冲」`)
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
  <el-dialog v-model="visible" width="720px" :close-on-click-modal="false">
    <template #header>
      <b>🔄 红冲影响清单 · {{ preview?.originDocNo || '' }}</b>
      <span class="mini" style="margin-left: 10px">{{ preview?.docType }} · {{ preview?.docStatus }}</span>
    </template>

    <div v-loading="loading">
      <template v-if="preview">
        <!-- 下游拦截 -->
        <div v-if="!preview.executable" class="ledger-card" style="border-left: 3px solid var(--red)">
          <h3>⛔ 不能整单红冲(有下游动作/状态不符)</h3>
          <p v-for="(b, i) in preview.blockers" :key="i" class="mini" style="margin: 4px 0; color: var(--red)">
            {{ i + 1 }}. {{ b }}
          </p>
          <p class="mini">口径(P0-1):整单红冲仅限「无下游动作」的已确认单;单价错→成本调整单;已付款→应付红字(M3)。</p>
        </div>

        <template v-else>
          <!-- 锁账提示 -->
          <p v-if="preview.lockedPeriod" class="ledger-note" style="border-left: 3px solid var(--amber)">
            🔒 原单入账月 <b class="num">{{ preview.bookPeriod }}</b> 已锁账(锁账线 {{ preview.lockLine }})。
            锁定期单据默认禁红冲——老板可越权(强制备注),反向单入<b>当月</b>,旧报表永不重算。
          </p>

          <!-- ① 库存变动 -->
          <div class="ledger-card">
            <h3>📦 库存变动 <span class="hint">红冲=按原单方向反向过账,免负库存拦截</span></h3>
            <el-table :data="preview.stockChanges" size="small">
              <el-table-column label="商品" min-width="150">
                <template #default="{ row }">{{ row.skuCode }} · {{ row.productName || row.productId }}</template>
              </el-table-column>
              <el-table-column label="仓库变动" width="90" align="right">
                <template #default="{ row }">
                  <b class="num" :style="n(row.warehouseDelta) < 0 ? 'color: var(--red)' : 'color: var(--green)'">
                    {{ n(row.warehouseDelta) > 0 ? '+' : '' }}{{ n(row.warehouseDelta) }}
                  </b>
                </template>
              </el-table-column>
              <el-table-column label="机器变动" width="90" align="right">
                <template #default="{ row }">
                  <span v-if="n(row.machineDelta) === 0" class="mini">—</span>
                  <b v-else class="num" :style="n(row.machineDelta) < 0 ? 'color: var(--red)' : 'color: var(--green)'">
                    {{ n(row.machineDelta) > 0 ? '+' : '' }}{{ n(row.machineDelta) }}
                  </b>
                </template>
              </el-table-column>
              <el-table-column label="仓库现有 → 红冲后" width="150" align="right">
                <template #default="{ row }">
                  <span class="num">{{ n(row.warehouseNow) }} → </span>
                  <b class="num" :style="n(row.warehouseAfter) < 0 ? 'color: var(--red)' : ''">{{ n(row.warehouseAfter) }}</b>
                  <span v-if="n(row.warehouseAfter) < 0" class="chip c-red" style="margin-left: 4px">负库存放行</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- ② 应付影响 -->
          <div v-if="preview.payableImpact" class="ledger-card">
            <h3>💳 应付影响</h3>
            <p class="mini" style="margin: 4px 0">
              <span class="chip c-gray">应付链未启用(M3)</span>
              红冲后应付将变动 <b class="num" style="color: var(--red)">¥{{ n(preview.payableImpact.wouldReverseAmount).toFixed(2) }}</b>
            </p>
            <p class="mini">{{ preview.payableImpact.note }}</p>
          </div>

          <!-- ③ 毛利影响 -->
          <div class="ledger-card">
            <h3>📉 毛利影响(涉及月份)</h3>
            <el-table :data="preview.marginImpacts" size="small">
              <el-table-column label="月份" width="90">
                <template #default="{ row }"><span class="num">{{ row.period }}</span></template>
              </el-table-column>
              <el-table-column label="账本" width="70">
                <template #default="{ row }">{{ row.locationType }}</template>
              </el-table-column>
              <el-table-column label="存货成本金额变动" align="right">
                <template #default="{ row }">
                  <b class="num" :style="n(row.costAmountReversal) < 0 ? 'color: var(--red)' : 'color: var(--green)'">
                    {{ n(row.costAmountReversal) > 0 ? '+' : '' }}¥{{ n(row.costAmountReversal).toFixed(2) }}
                  </b>
                </template>
              </el-table-column>
            </el-table>
            <p class="mini" style="margin: 6px 0 0">{{ preview.marginNote }}</p>
          </div>

          <!-- 原因 + 越权 -->
          <el-input v-model="reason" type="textarea" :rows="2" placeholder="红冲原因(必填,如:8/3 这单数量录错,供应商实发 20 件)" />
          <el-checkbox v-if="preview.lockedPeriod" v-model="bossOverride" style="margin-top: 8px">
            🔓 老板越权红冲锁账期单据(角色占位,操作与备注全程留痕 op_log)
          </el-checkbox>
        </template>
      </template>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button v-if="preview?.executable" type="danger" :loading="executing" @click="doExecute">
        ✓ 确认红冲(生成等额反向单)
      </el-button>
    </template>
  </el-dialog>
</template>
