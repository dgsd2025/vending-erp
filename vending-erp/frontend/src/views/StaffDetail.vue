<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  taskApi,
  ROLE_LABELS,
  type StaffOverviewResp,
  type OpLog,
  type PageResult,
} from '@/api/task'

/**
 * 员工详情(M2-6,对照 mockup p16 体检报告式):
 * 人也是一等公民——干了什么、干得准不准、手上压着什么,一页看清。
 * 定位是工作画像+交接备忘,数据全部来自任务实例与 op_log 留痕,不需要额外填报。
 */
const route = useRoute()
const router = useRouter()
const name = computed(() => String(route.params.name ?? ''))

const loading = ref(false)
const overview = ref<StaffOverviewResp | null>(null)
const logs = ref<PageResult<OpLog> | null>(null)
const logPage = ref(1)

async function load() {
  loading.value = true
  try {
    const [o, l] = await Promise.all([
      taskApi.staffOverview(name.value),
      taskApi.staffOpLogs(name.value, logPage.value, 15),
    ])
    overview.value = o
    logs.value = l
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(name, load)
watch(logPage, async () => {
  logs.value = await taskApi.staffOpLogs(name.value, logPage.value, 15)
})

const roleChips = computed(() =>
  (overview.value?.roles ?? []).map((c) => ROLE_LABELS[c] ?? c),
)

/** 对象类型 → 人话(经手单据统计) */
const TARGET_LABELS: Record<string, string> = {
  doc_head: '单据',
  product: '商品档案',
  supplier: '供应商',
  machine: '机器',
  slot: '货道',
  sku_alias: '别名绑定',
  alias_pending: '别名处理',
  import_batch: '导入批次',
  replenish_config: '补货参数',
  task_instance: '任务操作',
  user_role: '角色配置',
  routine_task: '任务定义',
  period_lock: '锁账',
  price_log: '改价',
}
const targetLabel = (t: string) => TARGET_LABELS[t] ?? t

const fmtTime = (t?: string | null) => (t ? t.replace('T', ' ').slice(5, 16) : '—')

/** 留痕行人话:动作 + 对象 + ID */
function logLine(l: OpLog) {
  return `${l.action} · ${targetLabel(l.targetType)}${l.targetId ? ' #' + l.targetId : ''}`
}

/** 任务与质量横条(mockup p16 hbar;全部由 overview 真数据推得,没数据显"—") */
const pct = (num: number, den: number) => (den > 0 ? Math.round((num / den) * 100) : null)
const qualityBars = computed(() => {
  const o = overview.value
  const total = o?.taskTotal30d ?? 0
  const auto = o?.taskAutoDone30d ?? 0
  const manual = o?.taskManualDone30d ?? 0
  return [
    { label: '任务完成率', value: o?.completionRate ?? pct(o?.taskDone30d ?? 0, total) },
    { label: '按时率(1−逾期)', value: total > 0 ? 100 - (pct(o?.taskOverdue30d ?? 0, total) ?? 0) : null },
    { label: '系统校验通过占比', value: pct(auto, auto + manual) },
  ]
})
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 人员 / 员工详情</div>
    <div class="ledger-title">
      <h2>{{ name }}</h2>
      <span v-for="(r, i) in roleChips" :key="i" class="chip c-blue">{{ r }}</span>
      <span class="chip c-gray">SSO:园区主系统(接入前占位)</span>
      <span class="chip" :class="overview?.active ? 'c-green' : 'c-gray'">
        {{ overview?.active ? '在岗' : '未配角色/停用' }}
      </span>
      <!-- mockup p16 头部三动作:角色/转派在任务日历完成;离职交接打包属里程碑4 -->
      <span class="head-actions">
        <el-button size="small" @click="router.push('/tasks')">调整角色</el-button>
        <el-button size="small" @click="router.push('/tasks')">转派其任务</el-button>
        <el-tooltip content="名下任务已可在任务日历逐个转派;一键打包交接(任务+单据+职责清单):里程碑 4 开放" placement="bottom">
          <el-button size="small" disabled style="color: var(--red)">离职交接</el-button>
        </el-tooltip>
      </span>
    </div>
    <p class="ledger-note">
      人也是一等公民:干了什么、干得准不准、手上压着什么,一页看清。定位是<b>工作画像 + 交接备忘</b>,
      数据全部来自任务实例和操作留痕,不需要额外填报。
    </p>

    <!-- 数字卡(p16 六卡式,M2 点亮 5 张;差错率=红冲统计,M3 钱账后点亮) -->
    <div class="stat-grid">
      <div class="ledger-card stat">
        <div class="lb">今日待办</div>
        <div class="vv num">{{ overview?.todayTodo ?? 0 }}</div>
        <span class="mini">来自任务日历(按角色派)</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">近30天任务完成</div>
        <div class="vv num">
          {{ overview?.taskDone30d ?? 0 }}<small style="font-size: 13px">/{{ overview?.taskTotal30d ?? 0 }}</small>
        </div>
        <span class="mini" v-if="overview?.completionRate != null">完成率 {{ overview.completionRate }}%</span>
        <span class="mini" v-else>近30天暂无派给 TA 的任务</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">完成方式</div>
        <div class="vv num">
          ✅{{ overview?.taskAutoDone30d ?? 0 }}
          <small style="font-size: 13px; color: var(--amber)">🟡{{ overview?.taskManualDone30d ?? 0 }}</small>
        </div>
        <span class="mini">系统校验 vs 手动补标(黄标多=该查流程)</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">逾期(近30天)</div>
        <div class="vv num" :style="{ color: (overview?.taskOverdue30d ?? 0) > 0 ? 'var(--red)' : 'inherit' }">
          {{ overview?.taskOverdue30d ?? 0 }}
        </div>
        <span class="mini">逾期未完成→次日红灯</span>
      </div>
      <div class="ledger-card stat">
        <div class="lb">经手留痕</div>
        <div class="vv num">{{ overview?.opLogTotal ?? 0 }}<small style="font-size: 13px">条</small></div>
        <span class="mini">
          {{ (overview?.opLogStats ?? []).slice(0, 3).map((s) => `${targetLabel(s.targetType)} ${s.cnt}`).join(' · ') || '暂无操作' }}
        </span>
      </div>
      <!-- mockup p16 另两卡:红冲差错率/经手金额靠钱账单据链,里程碑3 点亮 -->
      <div class="ledger-card stat dim">
        <div class="lb">录单差错率</div>
        <div class="vv num">—</div>
        <span class="chip c-gray">里程碑 3 开放</span>
      </div>
      <div class="ledger-card stat dim">
        <div class="lb">经手金额</div>
        <div class="vv num">—</div>
        <span class="chip c-gray">里程碑 3 开放</span>
      </div>
    </div>

    <!-- 📋 任务与质量横条(mockup p16 hbar;真数据推得) -->
    <div class="ledger-card">
      <h3>📋 任务与质量(近 30 天) <span class="hint">全部由任务实例真数据推得;没派过任务显"—"</span></h3>
      <div v-for="b in qualityBars" :key="b.label" class="hbar">
        <span class="nm">{{ b.label }}</span>
        <div class="tk"><i :style="{ width: (b.value ?? 0) + '%' }" :class="{ warn: b.value != null && b.value < 90 }"></i></div>
        <span class="vl num">{{ b.value == null ? '—' : b.value + '%' }}</span>
      </div>
      <p class="mini" style="margin: 8px 0 0">
        🤖 <b>AI 工作画像</b>(把上面的数说成一段人话+改进建议):<span class="chip c-gray">里程碑 4 开放(AI 全家桶)</span>
      </p>
    </div>

    <div class="two-col">
      <!-- 经手单据统计 -->
      <div class="ledger-card">
        <h3>🧾 经手单据统计 <span class="hint">按对象类型聚合 op_log,交接备忘用</span></h3>
        <table class="ttab">
          <tr><th>对象</th><th class="num">操作次数</th></tr>
          <tr v-for="s in overview?.opLogStats ?? []" :key="s.targetType">
            <td>{{ targetLabel(s.targetType) }}</td>
            <td class="num">{{ s.cnt }}</td>
          </tr>
        </table>
        <el-empty v-if="!overview?.opLogStats?.length" description="暂无经手记录" :image-size="50" />
        <p class="mini" style="margin-top: 8px">
          🧳 离职交接:名下未完成任务在任务日历一键转派;操作历史永久保留(停角色只断入口,不删历史)。
        </p>
      </div>

      <!-- 操作留痕时间线 -->
      <div class="ledger-card">
        <h3>📜 操作留痕时间线 <span class="hint">op_log 按人过滤 · 不可删</span></h3>
        <table class="ttab">
          <tr v-for="l in logs?.records ?? []" :key="l.id">
            <td class="mini" style="white-space: nowrap">{{ fmtTime(l.opTime) }}</td>
            <td>{{ logLine(l) }}</td>
          </tr>
        </table>
        <el-empty v-if="!logs?.records?.length" description="暂无操作记录" :image-size="50" />
        <el-pagination
          v-if="(logs?.total ?? 0) > 15"
          v-model:current-page="logPage"
          layout="prev, pager, next, total"
          :total="logs?.total ?? 0"
          :page-size="15"
          small
          style="margin-top: 10px; justify-content: flex-end"
        />
      </div>
    </div>

    <p class="ledger-foot-note">
      — 定位是工作画像+交接备忘,不是监控:所有指标来自任务与单据留痕,人人可见自己的页 —
    </p>
  </div>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(128px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.stat {
  margin-bottom: 0;
  padding: 12px 14px;
}
.stat .lb {
  font-size: 12px;
  color: var(--ink2);
}
.stat .vv {
  font-size: 24px;
  font-weight: 700;
  margin: 5px 0 2px;
}
/* 灰位卡:里程碑未到,画出来防"做没做"分不清 */
.stat.dim {
  opacity: 0.75;
  border-style: dashed;
}
/* 头部动作按钮组(mockup p16) */
.head-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
/* 质量横条(mockup .hbar) */
.hbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  font-size: 12.5px;
}
.hbar .nm {
  width: 150px;
  color: var(--ink2);
  flex-shrink: 0;
}
.hbar .tk {
  flex: 1;
  height: 14px;
  background: #eee9dd;
  border-radius: 7px;
  overflow: hidden;
}
.hbar .tk i {
  display: block;
  height: 100%;
  border-radius: 7px;
  background: linear-gradient(90deg, var(--green), #3f8465);
  transition: width 0.3s;
}
.hbar .tk i.warn {
  background: linear-gradient(90deg, #c9a13c, #b58a2a);
}
.hbar .vl {
  width: 48px;
  text-align: right;
  font-weight: 700;
}
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
/* 手机版(≤768px):数字卡两列、双栏竖排(M2-5 铁律10) */
@media (max-width: 768px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .two-col {
    grid-template-columns: 1fr;
  }
  .two-col > * {
    min-width: 0;
  }
  .ledger-title {
    flex-wrap: wrap;
  }
  .head-actions {
    margin-left: 0;
    width: 100%;
  }
  .hbar .nm {
    width: 120px;
  }
}
.ttab {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
  margin-top: 8px;
}
.ttab th {
  font-size: 12px;
  color: var(--ink2);
  text-align: left;
  padding: 7px 8px;
  border-bottom: 2px solid var(--line2);
  background: #faf7ef;
}
.ttab td {
  padding: 7px 8px;
  border-bottom: 1px solid var(--line);
}
.ttab .num,
.ttab td.num {
  text-align: right;
  font-family: var(--num);
}
</style>
