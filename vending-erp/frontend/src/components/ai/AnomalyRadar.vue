<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { aiApi, type Anomaly } from '@/api/ai'
import LlmTransparencyBadge from './LlmTransparencyBadge.vue'
import MockBadge from './MockBadge.vue'

const router = useRouter()

/**
 * 驾驶舱「异常雷达」卡(接入点#4):规则引擎侦测异常清单(销量骤变/盘差/结算差异),
 * 每条可展开 LLM 归因叙事(mock)。数字由规则出,LLM 只解释;🔬 过程可查。
 * limit:驾驶舱只展示 TOP N(红灯优先),其余折叠计数;不传=全展开(独立页用)。
 */
const props = withDefaults(defineProps<{ limit?: number }>(), { limit: 0 })

const rows = ref<Anomaly[]>([])
/** 红灯优先排序后按 limit 截断 */
const shownRows = computed(() => {
  const sorted = [...rows.value].sort(
    (a, b) => (a.severity === '红' ? 0 : 1) - (b.severity === '红' ? 0 : 1),
  )
  return props.limit > 0 ? sorted.slice(0, props.limit) : sorted
})
const hiddenCount = computed(() =>
  props.limit > 0 ? Math.max(0, rows.value.length - props.limit) : 0,
)
const redCount = computed(() => rows.value.filter((r) => r.severity === '红').length)
const loading = ref(false)
const explains = ref<Record<string, { text: string; llmCallId: number } | undefined>>({})
const explaining = ref<Record<string, boolean>>({})

/**
 * 律3 下钻:销量骤变异常 key = "sales-swing:{machineId}:{productId}",
 * 从中抠出机器/单品 id(为 null / 'null' / 空则视为无,不给链接)。
 */
function entityIds(row: Anomaly): { machineId: number | null; productId: number | null } {
  if (row.type !== 'sales-swing') return { machineId: null, productId: null }
  const parts = String(row.key).split(':')
  const parse = (v: string | undefined) => {
    const n = Number(v)
    return v && v !== 'null' && Number.isFinite(n) && n > 0 ? n : null
  }
  return { machineId: parse(parts[1]), productId: parse(parts[2]) }
}

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
      <h4>
        🛰 异常雷达 <span class="chip c-gray">规则侦测 · AI 归因</span>
        <span v-if="redCount" class="chip c-red" style="margin-left: 6px">🔴 {{ redCount }} 红</span>
      </h4>
      <el-button link size="small" @click="load">刷新</el-button>
    </div>
    <div v-loading="loading">
      <el-empty v-if="!rows.length" description="暂无异常,系统很干净 ✓" :image-size="54" />
      <div v-for="row in shownRows" :key="row.key" class="anomaly-row">
        <div class="anomaly-title">
          <span class="chip" :class="row.severity === '红' ? 'c-red' : 'c-amber'">{{ row.severity }}</span>
          <span>{{ row.title }}</span>
        </div>
        <!-- 律3 下钻:销量骤变异常挂机器/单品详情链接 -->
        <div v-if="entityIds(row).machineId || entityIds(row).productId" class="anomaly-links">
          <a
            v-if="entityIds(row).machineId"
            class="entity-link"
            @click="router.push(`/machines/${entityIds(row).machineId}`)"
            >🏪 机器详情 →</a
          >
          <a
            v-if="entityIds(row).productId"
            class="entity-link"
            @click="router.push(`/products/${entityIds(row).productId}`)"
            >🧃 单品详情 →</a
          >
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
            <MockBadge :text="explains[row.key]!.text" />
            <LlmTransparencyBadge :call-id="explains[row.key]!.llmCallId" size="mini" />
          </template>
        </div>
      </div>
      <div v-if="hiddenCount" class="anomaly-more">
        还有 {{ hiddenCount }} 条异常(仅显 TOP {{ limit }},红灯优先)· 去 BI 经营分析看全部
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
.anomaly-links {
  margin-top: 3px;
  padding-left: 4px;
  display: flex;
  gap: 12px;
}
.entity-link {
  font-size: 12px;
  color: var(--green, #2f6f4f);
  cursor: pointer;
  font-weight: 600;
}
.entity-link:hover {
  text-decoration: underline;
}
.anomaly-actions {
  margin-top: 4px;
  padding-left: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.anomaly-explain {
  font-size: 12.5px;
  color: var(--ink2, #7a6a48);
  margin-right: 6px;
}
.anomaly-more {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--border, #e5dcc6);
  font-size: 12px;
  color: var(--ink2, #7a6a48);
}
</style>
