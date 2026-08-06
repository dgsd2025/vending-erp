<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { reportApi, type GrossMarginResp, type StockResp } from '@/api/report'
import { importsApi, type ImportBatch } from '@/api/imports'
import { pageAliasPending } from '@/api/basedata'
import { taskApi, type TodayViewResp } from '@/api/task'
import { useAppStore } from '@/stores/app'

/**
 * 经营驾驶舱(M1-9,对照 mockup p1 的 M1 骨架版):
 * 一眼看清:卖得怎么样 → 钱赚了多少 → 现在该干什么。
 * M1 点亮:数据截至水印(真值=最近导入批次)/ 库存红灯待办 / 家底与本月数字卡 / 机器卡;
 * 今日工作台(任务日历)、AI 补货、钱账、BI 卡位画出,标「里程碑 N 开放」。
 */
const router = useRouter()
const appStore = useAppStore()

const loading = ref(false)
const stock = ref<StockResp | null>(null)
const gmSku = ref<GrossMarginResp | null>(null)
const gmMachine = ref<GrossMarginResp | null>(null)
const latestBatch = ref<ImportBatch | null>(null)
const pendingAlias = ref(0)
const priceChangeCount = ref(0)

async function load() {
  loading.value = true
  try {
    const [s, skuFirst, batches, pending] = await Promise.all([
      reportApi.stock(),
      reportApi.grossMargin(undefined, 'sku'),
      importsApi.batches(1, 1),
      pageAliasPending({ pendingStatus: '待处理', size: 1 }),
    ])
    stock.value = s
    // 默认月无销售(如只有测试单据的当月)→ 回退到最近一个有销售的月份
    let sku = skuFirst
    if (Number(sku.totalSalesAmt) === 0 && sku.months.length > 1) {
      for (let i = sku.months.length - 2; i >= 0; i--) {
        const prev = await reportApi.grossMargin(sku.months[i], 'sku')
        if (Number(prev.totalSalesAmt) !== 0) {
          sku = prev
          break
        }
      }
    }
    gmSku.value = sku
    gmMachine.value = await reportApi.grossMargin(sku.month, 'machine')
    latestBatch.value = batches.records[0] ?? null
    pendingAlias.value = pending.total
    if (s.dataAsOf) appStore.setDataAsOf(s.dataAsOf.slice(0, 10))
    // 改价待确认:取最近一个出货明细批次的改价清单条数
    const saleBatches = await importsApi.batches(1, 1, '出货明细')
    const sb = saleBatches.records[0]
    if (sb && sb.batchStatus === '已导入') {
      try {
        priceChangeCount.value = (await importsApi.priceChanges(sb.id)).length
      } catch {
        priceChangeCount.value = 0
      }
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

const money = (v: number | string | null | undefined) =>
  v == null ? '—' : `¥${Number(v).toLocaleString(undefined, { maximumFractionDigits: 0 })}`

const redCount = computed(
  () => (stock.value?.negativeCount ?? 0) + pendingAlias.value + priceChangeCount.value,
)

/** 机器卡:当月机器维毛利行(key=machineId) */
const machineRows = computed(() => (gmMachine.value?.rows ?? []).filter((r) => r.key != null))

// 今日工作台(M2-6 点亮):任务日历引擎的 3 条摘要,失败不拖累驾驶舱主加载
const todayTasks = ref<TodayViewResp | null>(null)
onMounted(async () => {
  try {
    todayTasks.value = await taskApi.today()
  } catch {
    todayTasks.value = null
  }
})
const taskChip = (s: string, dt?: string | null) =>
  s === '已完成' ? (dt === '系统校验' ? '✅' : '🟡') : s === '逾期' ? '🔴' : '⬜'

const today = new Date()
const weekday = ['日', '一', '二', '三', '四', '五', '六'][today.getDay()]
const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 首页</div>
    <div class="ledger-title">
      <h2>经营驾驶舱</h2>
      <span class="sub">{{ todayStr }} 星期{{ weekday }}</span>
    </div>
    <p class="ledger-note">
      一眼看清:<b>卖得怎么样 → 钱赚了多少 → 现在该干什么</b>
      <span class="chip" :class="stock?.dataAsOf ? 'c-green' : 'c-amber'" style="margin-left: 8px">
        🕐 数据截至:{{ stock?.dataAsOf ? stock.dataAsOf.replace('T', ' ').slice(0, 16) : '——(尚未导入)' }}
      </span>
      <span v-if="latestBatch" class="mini" style="margin-left: 6px">
        最近导入:{{ latestBatch.fileType }} · {{ (latestBatch.createTime ?? '').replace('T', ' ').slice(5, 16) }} ✓
      </span>
    </p>

    <!-- 今日工作台(M2-6 点亮:任务日历引擎的今日 3 条摘要,点击跳任务日历) -->
    <div class="ledger-card work-strip" style="cursor: pointer" @click="router.push('/tasks')">
      <h3 style="color: #fff; margin: 0">📋 今日工作台</h3>
      <template v-if="todayTasks">
        <span
          v-for="t in todayTasks.instances.slice(0, 3)"
          :key="t.id"
          class="chip"
          style="background: rgba(255, 255, 255, 0.15); color: #e8efe9"
        >
          {{ taskChip(t.instanceStatus, t.doneType) }} {{ t.taskName }}
          <b v-if="t.assigneeUserName" style="color: #ffd27a">· {{ t.assigneeUserName }}</b>
        </span>
        <span v-if="!todayTasks.instances.length" class="chip" style="background: rgba(255, 255, 255, 0.15); color: #c3d4c8">
          今日无到期任务
        </span>
        <span v-if="todayTasks.overdue.length" class="chip" style="background: rgba(192, 57, 43, 0.35); color: #ffd0c7">
          🔴 {{ todayTasks.overdue.length }} 个逾期
        </span>
        <span class="mini" style="color: #9db8a8; margin-left: auto">
          待办 {{ todayTasks.todoCount }} · 已完成 {{ todayTasks.doneCount }} · 任务日历 →
        </span>
      </template>
      <span v-else class="mini" style="color: #9db8a8; margin-left: auto">
        任务绑角色、角色绑人;完成有系统校验,不是打勾就算 · 任务日历 →
      </span>
    </div>

    <!-- 红灯待办(M1 真值,点击跳对应页) -->
    <div class="alerts">
      <div
        v-if="stock?.negativeCount"
        class="alert a-red"
        @click="router.push('/inventory')"
      >
        🚨 <b>{{ stock.negativeCount }} 个 SKU 负库存</b> —— 可能漏录采购单,先补录再谈毛利
        <span class="go">去库存页处理 →</span>
      </div>
      <div v-if="pendingAlias" class="alert a-amber" @click="router.push('/import')">
        ⚠️ <b>{{ pendingAlias }} 个新商品待绑别名</b> —— 不绑毛利算不准
        <span class="go">去绑定 →</span>
      </div>
      <div v-if="priceChangeCount" class="alert a-amber" @click="router.push('/import')">
        💲 <b>最近批次 {{ priceChangeCount }} 条改价待确认</b>(成交价 ≠ 档案参考价)
        <span class="go">去确认 →</span>
      </div>
      <div v-if="!redCount" class="alert a-blue">
        ✅ 库存红灯清零:无负库存 · 无待绑别名 · 无改价待确认 —— 台账健康
      </div>
    </div>

    <!-- 数字卡:本月销售/毛利 + 两级家底 -->
    <div class="stat-grid">
      <div class="ledger-card stat">
        <div class="lb">本月销售 / 毛利 <span class="chip c-gray">{{ gmSku?.month ?? '—' }}</span></div>
        <div class="vv num">
          {{ money(gmSku?.totalSalesAmt) }}
          <span style="font-size: 16px; color: var(--green)">/ {{ money(gmSku?.totalGrossProfit) }}</span>
        </div>
        <span class="mini" v-if="gmSku?.totalMarginPct != null">毛利率 {{ gmSku.totalMarginPct }}%</span>
        <span class="mini" v-else>毛利率 —</span>
        <div class="human">人话:每卖 100 块赚 {{ gmSku?.totalMarginPct != null ? Math.round(Number(gmSku.totalMarginPct)) : '—' }} 块;明细去报表页。</div>
      </div>
      <div class="ledger-card stat">
        <div class="lb">压在货上的钱 <span class="chip c-gray">📁 两级库存</span></div>
        <div class="vv num">{{ money(stock?.totalAmount) }}</div>
        <span class="mini">仓库 {{ money(stock?.warehouseAmount) }} + 机器里 {{ money(stock?.machineAmount) }}</span>
        <div class="human">人话:这是家底,与库存页/资产家底同一个数(单一真相源)。</div>
      </div>
      <div class="ledger-card stat milestone" @click="router.push('/reports')">
        <div class="lb">🤖 AI 补货建议</div>
        <div class="vv num" style="color: #b9b2a0">里程碑 2</div>
        <span class="mini">(R,S) 公式算数字 · AI 讲人话 · 配货单 pre-kit</span>
      </div>
      <div class="ledger-card stat milestone">
        <div class="lb">💰 钱账 · 待办</div>
        <div class="vv num" style="color: #b9b2a0">里程碑 3</div>
        <span class="mini">应付逾期 / 结算核对 / 业财单据链红灯,先核实结算模式</span>
      </div>
    </div>

    <div class="two-col">
      <!-- 机器卡(当月机器维,点进机器详情) -->
      <div class="ledger-card">
        <h3>机器 · 谁最能卖 <span class="hint">{{ gmMachine?.month ?? '' }} 销售额 · 点卡看机器详情</span></h3>
        <div class="mgrid">
          <div
            v-for="m in machineRows"
            :key="m.key!"
            class="mcard"
            @click="router.push(`/machines/${m.key}`)"
          >
            <h4>{{ m.name }} ↗</h4>
            <div class="id num">{{ m.code }}</div>
            <div class="mrow"><span>本月销售</span><b class="num">{{ money(m.salesAmt) }}</b></div>
            <div class="mrow">
              <span>毛利</span>
              <b class="num">{{ m.grossProfit != null ? money(m.grossProfit) : '—' }}</b>
            </div>
            <div class="mrow mini" v-if="m.noCostSkuCount">
              <span style="color: var(--amber)">⚠️ {{ m.noCostSkuCount }} 个 SKU 成本待补</span>
            </div>
          </div>
        </div>
        <el-empty v-if="!machineRows.length" description="尚无机器销售数据(先在导入中心导后台出货明细)" :image-size="60" />
      </div>

      <!-- 热销 TOP(当月 SKU 维) -->
      <div class="ledger-card">
        <h3>本月热销 TOP 5 <span class="hint">按销售额 · 点商品名进单品页</span></h3>
        <table class="ltab">
          <tr><th>#</th><th>商品</th><th class="num">销售额</th><th class="num">毛利率</th></tr>
          <tr v-for="(r, i) in (gmSku?.rows ?? []).filter((x) => x.key != null).slice(0, 5)" :key="r.key!">
            <td class="num">{{ i + 1 }}</td>
            <td><a class="plink" @click="router.push(`/products/${r.key}`)">{{ r.name }} ↗</a></td>
            <td class="num">{{ money(r.salesAmt) }}</td>
            <td class="num">{{ r.marginPct != null ? r.marginPct + '%' : '—' }}</td>
          </tr>
        </table>
        <el-empty v-if="!gmSku?.rows?.length" description="尚无销售数据" :image-size="60" />
        <p class="mini" style="margin-top: 8px">
          📈 BI 经营分析(月报 / 问数 / 改进循环 PDCA)· <b>里程碑 4 开放</b>
        </p>
      </div>
    </div>

    <p class="ledger-foot-note">
      — 驾驶舱只读汇总,每个数字都能点进出处;红灯清零是每天的第一目标 —
    </p>
  </div>
</template>

<style scoped>
.work-strip {
  background: linear-gradient(135deg, #1c3d2f, #2a5741);
  color: #fff;
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 20px;
}
.work-strip h3 {
  font-family: var(--serif);
}
.alerts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}
.alert {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 16px;
  border-radius: 10px;
  font-size: 13px;
  border: 1px solid;
  cursor: pointer;
}
.a-red {
  background: var(--red-soft);
  border-color: #eac6bf;
  color: #8c2b20;
}
.a-amber {
  background: var(--amber-soft);
  border-color: #ecd8b8;
  color: #8a5a1d;
}
.a-blue {
  background: var(--blue-soft);
  border-color: #c8dae8;
  color: #2b5375;
  cursor: default;
}
.alert .go {
  margin-left: auto;
  font-size: 12px;
  font-weight: 600;
  text-decoration: underline;
  white-space: nowrap;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}
.stat {
  margin-bottom: 0;
}
.stat .lb {
  font-size: 12.5px;
  color: var(--ink2);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.stat .vv {
  font-size: 28px;
  font-weight: 700;
  margin: 6px 0 2px;
}
.stat .human {
  font-size: 12px;
  color: var(--ink2);
  border-top: 1px dashed var(--line2);
  margin-top: 10px;
  padding-top: 8px;
  line-height: 1.6;
}
.milestone {
  border-style: dashed;
  cursor: pointer;
}
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.mgrid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-top: 10px;
}
.mcard {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 14px 16px;
  background: var(--card);
  position: relative;
  overflow: hidden;
  cursor: pointer;
}
.mcard::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: var(--green);
}
.mcard h4 {
  font-size: 15px;
  font-weight: 700;
  margin: 0 0 2px;
  color: var(--green);
}
.mcard .id {
  font-size: 10.5px;
  color: #a89f8a;
  letter-spacing: 0.5px;
}
.mrow {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--ink2);
  margin-top: 9px;
  align-items: center;
}
.mrow b {
  font-size: 15px;
  color: var(--ink);
}
.ltab {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin-top: 10px;
}
.ltab th {
  font-size: 12px;
  color: var(--ink2);
  font-weight: 600;
  text-align: left;
  padding: 8px 10px;
  border-bottom: 2px solid var(--line2);
  background: #faf7ef;
}
.ltab th.num,
.ltab td.num {
  text-align: right;
  font-family: var(--num);
}
.ltab td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--line);
}
.plink {
  color: var(--green);
  cursor: pointer;
  font-weight: 600;
}
</style>
