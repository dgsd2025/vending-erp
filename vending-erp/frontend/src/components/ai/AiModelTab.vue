<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiApi, type AiModelRow } from '@/api/ai'

/**
 * 设置中心「AI 模型配置」Tab(§12.2):每个接入点独立可换模型,默认值预填。
 * 铁律:模型可选不写死;未配 key 全部走 mock(mock 返回带 [MOCK] 前缀文案)。
 */
const rows = ref<AiModelRow[]>([])
const loading = ref(false)
const editing = ref<Record<string, string>>({})

async function load() {
  loading.value = true
  try {
    rows.value = await aiApi.models()
    const map: Record<string, string> = {}
    for (const r of rows.value) map[r.key] = r.configuredModel ?? ''
    editing.value = map
  } finally {
    loading.value = false
  }
}

async function save(row: AiModelRow) {
  await aiApi.setModel(row.key, editing.value[row.key] ?? '')
  ElMessage.success(`「${row.label}」模型已更新`)
  await load()
}

const phaseLabel = (p: number) => (p === 1 ? '一期' : p === 2 ? '二期' : '三期')

onMounted(load)
</script>

<template>
  <div>
    <p class="ledger-note" style="margin-top: 0">
      每个 AI 接入点可独立选模型(留空 = 用默认)。当前网关为 <b>mock 占位</b>,
      配好 <span class="num">LLM_BASE_URL / LLM_API_KEY</span> 后<b>一键切真网关</b>,业务代码零改动。
      成本量级 &lt; ¥30/月(§12.2),瓶颈从来不是钱。
    </p>
    <el-table :data="rows" v-loading="loading" size="small" data-block="ai-model-table">
      <el-table-column label="接入点" min-width="230">
        <template #default="{ row }">
          <b>#{{ row.description.match(/#(\d+)/)?.[1] || '' }} {{ row.label }}</b>
          <span class="chip c-gray" style="margin-left: 6px">{{ phaseLabel(row.phase) }}</span>
          <span v-if="row.wired" class="chip c-green" style="margin-left: 4px">已挂接</span>
          <span v-else class="chip c-gray" style="margin-left: 4px">待接</span>
          <div class="mini">{{ row.description }}</div>
        </template>
      </el-table-column>
      <el-table-column label="默认(§12.2)" width="150">
        <template #default="{ row }">
          <span class="num mini">{{ row.defaultModel }}</span>
        </template>
      </el-table-column>
      <el-table-column label="生效模型" width="170">
        <template #default="{ row }">
          <span class="num">{{ row.effectiveModel }}</span>
          <div v-if="!row.wired" class="mini">(挂接后生效)</div>
        </template>
      </el-table-column>
      <el-table-column label="改用模型(空=默认)" min-width="220">
        <template #default="{ row }">
          <div style="display: flex; gap: 6px">
            <el-input
              v-model="editing[row.key]"
              size="small"
              :placeholder="row.defaultModel"
              clearable
            />
            <el-button size="small" type="primary" @click="save(row)">保存</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <p class="ledger-foot-note">
      — 改动记 op_log,谁改的、改了什么永远可查;每次 AI 调用落 llm_call_log(🔬 过程可查、24h 不重复烧钱) —
    </p>
  </div>
</template>
