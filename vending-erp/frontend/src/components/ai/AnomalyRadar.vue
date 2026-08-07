<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { aiApi, type Anomaly } from '@/api/ai'
import LlmTransparencyBadge from './LlmTransparencyBadge.vue'

/**
 * 驾驶舱「异常雷达」卡(接入点#4):规则引擎侦测异常清单(销量骤变/盘差/结算差异),
 * 每条可展开 LLM 归因叙事(mock)。数字由规则出,LLM 只解释;🔬 过程可查。
 */
const rows = ref<Anomaly[]>([])
const loading = ref(false)
const explains = ref<Record<string, { text: string; llmCallId: number } | undefined>>({})
const explaining = ref<Record<string, boolean>>({})

async function load() {
  loading.value = true
  try {
    rows.value = await aiApi.anomalies()
  } finally {
    loading.value = false
  }
}

async function explain(row: Anomaly) {
  if (explains.value[row.key]) return
  explaining.value[row.key] = true
  try {
    const r = await aiApi.anomalyExplain(row)
    explains.value[row.key] = { text: String(r.text), llmCallId: Number(r.llmCallId) }
  } finally {
    explaining.value[row.key] = false
  }
}

onMounted(load)
</script>

<template>
  <div class="ledger-card" data-block="anomaly-radar">
    <div class="card-head">
      <h4>🛰 异常雷达 <span class="chip c-gray">规则侦测 · AI 归因</span></h4>
      <el-button link size="small" @click="load">刷新</el-button>
    </div>
    <div v-loading="loading">
      <el-empty v-if="!rows.length" description="暂无异常,系统很干净 ✓" :image-size="54" />
      <div v-for="row in rows" :key="row.key" class="anomaly-row">
        <div class="anomaly-title">
          <span class="chip" :class="row.severity === '红' ? 'c-red' : 'c-amber'">{{ row.severity }}</span>
          <span>{{ row.title }}</span>
        </div>
        <div class="anomaly-actions">
          <el-button
            v-if="!explains[row.key]"
            link
            size="small"
            :loading="explaining[row.key]"
            @click="explain(row)"
          >
            AI 归因 →
          </el-button>
          <template v-else>
            <span class="anomaly-explain">{{ explains[row.key]!.text }}</span>
            <LlmTransparencyBadge :call-id="explains[row.key]!.llmCallId" size="mini" />
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-head h4 {
  margin: 0 0 8px;
}
.anomaly-row {
  padding: 8px 0;
  border-top: 1px dashed var(--border, #e5dcc6);
}
.anomaly-title {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 13px;
  line-height: 1.5;
}
.anomaly-actions {
  margin-top: 4px;
  padding-left: 4px;
}
.anomaly-explain {
  font-size: 12.5px;
  color: var(--ink2, #7a6a48);
  margin-right: 6px;
}
</style>
