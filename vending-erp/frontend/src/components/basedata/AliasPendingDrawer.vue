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

/**
 * 别名待绑定队列抽屉:导入遇到不认识的 编号+条码 进这里,系统给建议、人只点确认。
 * (队列数据由 M1-3 导入模块生产;这里是消费端,接口已立好。)
 *
 * TODO(M4 接真 AI 前必读,M1-10 P2-6 审计锁定验收标准):
 * 当前后端没有任何写手写 suggest_product_id / ai_confidence(M1 全 mock,LLM 零参与),
 * 置信 chip 界面文案暂用「规则建议」,不许叫「AI」。
 * 接真 AI 的那个 PR 必须连带四件套才许合并(七律#7 + 全局 §4.7 AI 分析开发铁律):
 *   ① 🔬 过程入口(推理过程/完整输出/置信构成/原始数据 四 Tab 弹窗)挂在置信 chip 旁;
 *   ② LLM 调用落 llm_call_log(模型/prompt/tokens/耗时可查);
 *   ③ 建议字段写手与 🔬 入口同 PR 上线,禁止只写字段不带过程;
 *   ④ 数字仍由规则引擎出,AI 只做解释/建议(七律#7)。
 * 后端对应位置:AliasService.bind 的「AI建议采纳」分支——同样锁此验收。
 */
const props = defineProps<{ visible: boolean }>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'changed'): void
}>()

const rows = ref<AliasPending[]>([])
const loading = ref(false)
const chosen = ref<Record<number, number | null>>({})

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
    <el-table :data="rows" v-loading="loading" size="small">
      <el-table-column label="后台商品" min-width="170">
        <template #default="{ row }">
          <b>{{ row.aliasName }}</b>
          <div class="mini num">{{ row.aliasCode || '—' }} · {{ row.aliasBarcode || '无条码' }}</div>
          <span class="chip c-gray">出现 {{ row.hitCount }} 次</span>
          <!-- TODO(P2-6):接真 AI 前文案锁「规则建议」;接真 AI 时此 chip 必须连带 🔬 过程入口(见文件顶部验收标准) -->
          <span v-if="row.aiConfidence != null" class="chip c-blue" style="margin-left: 4px">
            规则建议 {{ Math.round(Number(row.aiConfidence) * 100) }}%
          </span>
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
