<script setup lang="ts">
import { ref } from 'vue'
import { aiApi, type LlmTransparency } from '@/api/ai'

/**
 * 全系统唯一 AI 过程标签(全局 §4.7 铁律):AI 结论旁挂一个小标签 🔬 AI过程 →
 * 一个弹窗 Tab 切四件套(推理过程 / 完整输出 / 确信分 / 原始数据)。
 * 禁自造同类弹窗,任何 AI 结论都复用本组件,传 llm_call_id 即可。
 */
const props = defineProps<{ callId: number | null | undefined; size?: 'small' | 'mini' }>()

const visible = ref(false)
const loading = ref(false)
const data = ref<LlmTransparency | null>(null)
const tab = ref('reasoning')

async function open() {
  if (props.callId == null) return
  visible.value = true
  loading.value = true
  try {
    data.value = await aiApi.transparency(props.callId)
  } finally {
    loading.value = false
  }
}

function pretty(json: string | null): string {
  if (!json) return '—'
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}
</script>

<template>
  <span v-if="props.callId != null">
    <button class="llm-badge" :class="{ mini: props.size === 'mini' }" @click.stop="open">
      🔬 AI过程
    </button>
    <el-dialog v-model="visible" title="🔬 AI 过程透明(四件套)" width="640px" append-to-body>
      <div v-loading="loading">
        <div v-if="data" class="llm-head">
          <span class="chip c-blue">模型 {{ data.model }}</span>
          <span class="chip c-gray">{{ data.scene }}</span>
          <span class="chip" :class="data.callStatus === '降级' ? 'c-amber' : 'c-green'">
            {{ data.callStatus }}
          </span>
          <span v-if="data.cacheHit" class="chip c-gray">当日缓存命中</span>
          <span v-if="data.confidence != null" class="chip c-blue">
            确信 {{ Math.round(Number(data.confidence) * 100) }}%
          </span>
        </div>
        <el-tabs v-if="data" v-model="tab">
          <el-tab-pane label="① 推理过程" name="reasoning">
            <pre class="llm-pre">{{ data.reasoning || '—' }}</pre>
          </el-tab-pane>
          <el-tab-pane label="② 完整输出" name="output">
            <pre class="llm-pre">{{ data.outputText || '—' }}</pre>
          </el-tab-pane>
          <el-tab-pane label="③ 确信分构成" name="conf">
            <p class="mini">
              确信分 = 规则真算(数据完备度 / 相似度),非 LLM 拍脑袋。当前:
              <b>{{ data.confidence != null ? Math.round(Number(data.confidence) * 100) + '%' : '—' }}</b>
            </p>
            <pre class="llm-pre">{{ pretty(data.inputDigest) }}</pre>
          </el-tab-pane>
          <el-tab-pane label="④ 原始数据" name="raw">
            <pre class="llm-pre">{{ pretty(data.inputDigest) }}</pre>
            <p class="mini num" style="margin-top: 8px">
              tokens {{ data.cost.tokensIn ?? 0 }}/{{ data.cost.tokensOut ?? 0 }} ·
              {{ data.cost.durationMs ?? 0 }}ms · 指纹 {{ data.promptFingerprint || '—' }}
            </p>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>
  </span>
</template>

<style scoped>
.llm-badge {
  border: 1px solid var(--border, #d9c8a8);
  background: transparent;
  color: var(--ink-2, #7a6a48);
  border-radius: 10px;
  padding: 1px 8px;
  font-size: 12px;
  cursor: pointer;
  line-height: 1.6;
}
.llm-badge:hover {
  background: rgba(0, 0, 0, 0.03);
}
.llm-badge.mini {
  font-size: 11px;
  padding: 0 6px;
}
.llm-head {
  margin-bottom: 10px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.llm-pre {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.6;
  background: rgba(0, 0, 0, 0.03);
  padding: 10px;
  border-radius: 6px;
  max-height: 340px;
  overflow: auto;
  margin: 0;
}
</style>
