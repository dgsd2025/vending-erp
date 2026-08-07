<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  confirmAliasPending,
  ignoreAliasPending,
  pageAliasPending,
  type AliasPending,
} from '@/api/basedata'
import ProductSelect from './ProductSelect.vue'
import LlmTransparencyBadge from '@/components/ai/LlmTransparencyBadge.vue'
import MockBadge from '@/components/ai/MockBadge.vue'
import { aiApi } from '@/api/ai'

/**
 * 别名待绑定队列抽屉:导入遇到不认识的 编号+条码 进这里,系统给建议、人只点确认。
 * (队列数据由 M1-3 导入模块生产;这里是消费端。)
 *
 * M4-5 兑现 M1-10 P2-6 锁定的验收:AliasSuggestService 是建议写手(规则打分,标「规则建议」),
 * 写回 suggest_product_id / ai_confidence / llm_call_id;置信 chip 旁挂唯一 🔬 过程入口
 * (LlmTransparencyBadge,四件套弹窗);LLM(mock)只复核话术,数字由规则引擎出(七律#7)。
 * 真 key 后走 embedding+LLM 复核,前端零改动。
 */
const props = defineProps<{ visible: boolean }>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'changed'): void
}>()

const rows = ref<AliasPending[]>([])
const loading = ref(false)
const suggesting = ref(false)
const chosen = ref<Record<number, number | null>>({})
/** 最近一次生成建议的链路(「规则建议(mock断路)」/「embedding+LLM复核」)——供 MockBadge 判 mock */
const suggestMode = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    const page = await pageAliasPending({ current: 1, size: 50, pendingStatus: '待绑定' })
    rows.value = page.records
    const map: Record<number, number | null> = {}
    for (const r of page.records) map[r.id] = r.suggestProductId ?? null
    chosen.value = map
  } finally {
    loading.value = false
  }
}

/** 生成归集建议(接入点#1):规则打分写回 suggest/confidence/llm_call_id */
async function genSuggest() {
  suggesting.value = true
  try {
    const s = await aiApi.aliasSuggest()
    suggestMode.value = s.mode != null ? String(s.mode) : null
    ElMessage.success(`已生成建议:命中 ${s.suggested ?? 0} 条 · 无匹配 ${s.noMatch ?? 0} 条(${s.mode ?? ''})`)
    await load()
  } finally {
    suggesting.value = false
  }
}

watch(
  () => props.visible,
  (show) => {
    if (show) load()
  },
)

async function confirm(row: AliasPending) {
  const productId = chosen.value[row.id]
  if (!productId) {
    ElMessage.warning('先选要绑到哪个商品')
    return
  }
  await confirmAliasPending(row.id, productId)
  ElMessage.success(`「${row.aliasName}」已绑定`)
  await load()
  emit('changed')
}

async function ignore(row: AliasPending) {
  await ElMessageBox.confirm(`忽略「${row.aliasName}」?之后不再提醒。`, '确认忽略', {
    type: 'warning',
  })
  await ignoreAliasPending(row.id)
  await load()
  emit('changed')
}
</script>

<template>
  <el-drawer
    :model-value="props.visible"
    title="⚠️ 待绑别名队列(不绑毛利算不准)"
    size="680px"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <p class="mini" style="margin-top: 0">
      导入出货明细遇到不认识的「后台编号+条码」会进这里;<b>系统给建议,你只点确认</b>。绑定后由导入模块回补历史销售归属。
    </p>
    <div class="flex items-center gap-8px" style="margin-bottom: 8px">
      <el-button size="small" type="primary" plain :loading="suggesting" @click="genSuggest">
        🤖 生成归集建议
      </el-button>
      <MockBadge :mode="suggestMode" />
    </div>
    <el-table :data="rows" v-loading="loading" size="small">
      <el-table-column label="后台商品" min-width="170">
        <template #default="{ row }">
          <b>{{ row.aliasName }}</b>
          <div class="mini num">{{ row.aliasCode || '—' }} · {{ row.aliasBarcode || '无条码' }}</div>
          <span class="chip c-gray">出现 {{ row.hitCount }} 次</span>
          <!-- M4-5 兑现 P2-6:置信 chip 接真数据源(规则打分)+ 唯一 🔬 过程入口 -->
          <span v-if="row.aiConfidence != null" class="chip c-blue" style="margin-left: 4px">
            规则建议 {{ Math.round(Number(row.aiConfidence) * 100) }}%
          </span>
          <LlmTransparencyBadge v-if="row.llmCallId != null" :call-id="row.llmCallId" size="mini" />
        </template>
      </el-table-column>
      <el-table-column label="绑到商品" min-width="200">
        <template #default="{ row }">
          <ProductSelect v-model="chosen[row.id]" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button type="primary" size="small" @click="confirm(row)">绑定 →</el-button>
          <el-button link size="small" @click="ignore(row)">忽略</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="队列空了,别名都归集好了 ✓" :image-size="60" />
      </template>
    </el-table>
  </el-drawer>
</template>
