<script setup lang="ts">
import { computed, ref } from 'vue'
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

const confPct = computed(() =>
  data.value?.confidence != null ? Math.round(Number(data.value.confidence) * 100) + '%' : '—',
)

/**
 * P2-7 整改:Tab③「确信分构成」讲清"这个分怎么来的",不再重复 Tab④原始数据。
 * 依后端 confidence_source(并行票)分档说明;字段取不到 → 占位说明,绝不报错。
 */
const confSource = computed(() => (data.value?.confidenceSource ?? '').toLowerCase())
const confBreakdown = computed(() => {
  if (data.value?.confidence == null) {
    return {
      tag: '暂无',
      tagType: 'info' as const,
      desc: '本次调用未产生置信分(如纯规则侦测/降级兜底),故不展示构成。',
    }
  }
  if (confSource.value === 'computed') {
    return {
      tag: '真算 · 按数据完备度/相似度',
      tagType: 'success' as const,
      desc:
        '本场景置信分为规则真算:= 命中/有值维度 ÷ 应有维度(或最佳候选的相似度)。' +
        '数据越全、匹配越准,分越高。分子分母的原始取值见 ④ 原始数据。',
    }
  }
  if (confSource.value === 'fixed') {
    return {
      tag: '基准置信 · 固定档',
      tagType: 'warning' as const,
      desc:
        '本场景置信分为基准置信(固定档):按结论类型给定档位(如命中模板/有数据给高档、未命中/无数据给低档),' +
        '不随本次数据逐次真算。它表达"这类结论一般有多可信",不是本次样本的完备度。',
    }
  }
  // confidence_source 取不到(旧后端/字段未返)——占位说明,不臆断真算与否
  return {
    tag: '构成占位',
    tagType: 'info' as const,
    desc:
      '置信分用于表达该结论的可信程度(来自数据完备度或相似度)。本次返回未带来源标记(confidence_source),' +
      '故仅展示分值与占位说明;具体输入取值见 ④ 原始数据。后端补齐来源字段后,这里会显示"真算/固定档"的分档解释。',
  }
})
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
            <div class="conf-head">
              <span class="conf-num">{{ confPct }}</span>
              <el-tag :type="confBreakdown.tagType" size="small" effect="plain">{{ confBreakdown.tag }}</el-tag>
            </div>
            <p class="conf-desc">{{ confBreakdown.desc }}</p>
            <p class="mini" style="color: var(--ink-2, #7a6a48)">
              说明:数字全部由规则引擎算出,LLM 只负责把话说顺、从不参与算数与打分。原始输入取值见 ④ 原始数据。
            </p>
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
.conf-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.conf-num {
  font-size: 26px;
  font-weight: 700;
  font-family: var(--num);
  color: var(--green, #2f6f4f);
}
.conf-desc {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink, #2c3e50);
  margin: 0 0 8px;
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
