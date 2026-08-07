<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiApi, type NlQueryResult } from '@/api/ai'
import LlmTransparencyBadge from './LlmTransparencyBadge.vue'
import MockBadge from './MockBadge.vue'

/**
 * BI「问数」框(接入点#6,实验性):自然语言 → SQL(只读白名单视图 + LIMIT)→ 表格。
 * mock 版:预置 5 个常见问题模板匹配;真 key 版走 LLM text2SQL(生成 SQL 仍过安全闸)。
 */
const q = ref('')
const loading = ref(false)
const result = ref<NlQueryResult | null>(null)
const suggestions = ref<string[]>([])

async function ask(question?: string) {
  const text = question ?? q.value
  if (!text.trim()) {
    ElMessage.warning('先输入要查什么')
    return
  }
  q.value = text
  loading.value = true
  try {
    result.value = await aiApi.nlQuery(text)
  } finally {
    loading.value = false
  }
}

const columns = () =>
  result.value && result.value.rows.length ? Object.keys(result.value.rows[0]) : []

onMounted(async () => {
  suggestions.value = await aiApi.nlSuggestions()
})
</script>

<template>
  <div class="ledger-card" data-block="nl-query">
    <div class="card-head">
      <h4>💬 问数 <span class="chip c-amber">实验性</span></h4>
      <span class="mini">只读白名单视图,安全闸拦一切写操作</span>
    </div>
    <div style="display: flex; gap: 6px; margin: 6px 0">
      <el-input
        v-model="q"
        placeholder="例:各机器本月毛利多少?/ 库存还剩哪些?/ 损耗都花在哪?"
        clearable
        @keyup.enter="ask()"
      />
      <el-button type="primary" :loading="loading" @click="ask()">问</el-button>
    </div>
    <div class="chips">
      <span v-for="s in suggestions" :key="s" class="chip c-blue clickable" @click="ask(s)">{{ s }}</span>
    </div>

    <div v-if="result" v-loading="loading" style="margin-top: 10px">
      <p class="mini">
        <b>{{ result.matchedTemplate }}</b>
        <span class="chip c-gray" style="margin-left: 6px">{{ result.mode }}</span>
        <MockBadge :mode="result.mode" />
        <LlmTransparencyBadge :call-id="result.llmCallId" size="mini" />
      </p>
      <el-table :data="result.rows" size="small" max-height="320">
        <el-table-column
          v-for="col in columns()"
          :key="col"
          :prop="col"
          :label="col"
          min-width="110"
        />
        <template #empty>
          <el-empty description="没查到数据(可能这段时间没有记录)" :image-size="54" />
        </template>
      </el-table>
      <p class="mini num" style="margin-top: 6px; opacity: 0.7">SQL: {{ result.sql }}</p>
    </div>
  </div>
</template>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.card-head h4 {
  margin: 0;
}
.chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.clickable {
  cursor: pointer;
}
</style>
