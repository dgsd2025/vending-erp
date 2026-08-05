<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getLocks, getPriorAdjust, lockPeriod, unlockPeriod, type LocksResp, type PriorAdjustResp } from '@/api/period'

/**
 * 锁账小节(M1-7,P0-2):设置中心「参数与阈值」Tab 内的一个卡片小节。
 * 当前锁账线 / 锁账按钮(锁定某月及之前)/ 解锁(限老板占位·强制备注)/ 本月上期调整一览。
 */

const data = ref<LocksResp | null>(null)
const prior = ref<PriorAdjustResp | null>(null)
const lockMonth = ref('')
const lockNote = ref('')
const saving = ref(false)

function prevMonth(): string {
  const d = new Date()
  d.setDate(1)
  d.setMonth(d.getMonth() - 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function thisMonth(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

async function load() {
  data.value = await getLocks()
  prior.value = await getPriorAdjust(thisMonth())
}

async function doLock() {
  if (!lockMonth.value) return ElMessage.warning('请选择要锁定的月份')
  await ElMessageBox.confirm(
    `锁定 ${lockMonth.value} 及之前所有月份?锁后这些月的单据禁改/禁红冲(老板可越权);` +
    `补导的旧数据自动入当月,由「上期调整」行承接,旧报表永不重算。`,
    '锁账确认', { type: 'warning' },
  )
  saving.value = true
  try {
    await lockPeriod(lockMonth.value, lockNote.value.trim() || undefined)
    ElMessage.success(`已锁账:${lockMonth.value} 及之前(操作已记 op_log)`)
    lockMonth.value = ''
    lockNote.value = ''
    load()
  } finally {
    saving.value = false
  }
}

async function doUnlock() {
  if (!data.value?.lockLine) return
  const { value } = await ElMessageBox.prompt(
    `解锁当前锁账线 ${data.value.lockLine}(限老板角色·占位)。必须填写备注:为什么解锁、解锁后要改什么。`,
    '解锁(需备注)',
    { inputPlaceholder: '如:7月漏了一张报损单要补录', inputValidator: (v) => !!v?.trim() || '备注必填', type: 'warning' },
  )
  await unlockPeriod(data.value.lockLine, String(value).trim())
  ElMessage.success('已解锁,锁账线回落(操作与备注已记 op_log)')
  load()
}

onMounted(() => {
  lockMonth.value = prevMonth()
  load()
})
</script>

<template>
  <div class="ledger-card">
    <h3>🔒 锁账 <span class="hint">每月出完报表后锁上月(默认5日);锁账只管改单不管补导,补导走 book_period 入当月</span></h3>

    <div style="display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-bottom: 10px">
      <span class="mini">当前锁账线:</span>
      <span v-if="data?.lockLine" class="chip c-red" style="font-size: 13px">🔒 {{ data.lockLine }} 及之前已锁</span>
      <span v-else class="chip c-gray">未锁账(全部月份可改)</span>
      <el-button v-if="data?.lockLine" size="small" @click="doUnlock">🔓 解锁(限老板·需备注)</el-button>
    </div>

    <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
      <el-date-picker v-model="lockMonth" type="month" value-format="YYYY-MM" placeholder="选择要锁到的月份" style="width: 150px" />
      <el-input v-model="lockNote" placeholder="锁账说明(可空,如:7月月报已出)" style="width: 230px" />
      <el-button type="primary" size="small" :loading="saving" @click="doLock">🔒 锁账(锁定该月及之前)</el-button>
    </div>

    <p class="mini" style="margin: 8px 0 0">
      规则(P0-2):锁账线之前的单据<b>禁改/禁红冲</b>(老板角色+强制备注可越权);
      锁后补导的销售/单据自动入<b>当月</b>,旧报表永不重算,当月利润表「上期调整」行承接。
    </p>

    <!-- 本月上期调整一览(报表行取数口预览) -->
    <div v-if="prior && (prior.saleRows > 0 || prior.docLines.length > 0)" style="margin-top: 12px">
      <p class="mini" style="margin: 0 0 6px">
        📥 本月({{ prior.bookPeriod }})上期调整:补导销售 <b class="num">{{ prior.saleRows }}</b> 行
        · 金额 <b class="num">¥{{ Number(prior.saleAmount).toFixed(2) }}</b>
      </p>
      <el-table :data="prior.saleLines" size="small" max-height="180">
        <el-table-column label="业务月" width="90">
          <template #default="{ row }"><span class="num">{{ row.bizPeriod }}</span></template>
        </el-table-column>
        <el-table-column label="来源批次" width="100">
          <template #default="{ row }"><span class="num">{{ row.importBatchId ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="行数" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ row.rowCount }}</span></template>
        </el-table-column>
        <el-table-column label="数量" width="80" align="right">
          <template #default="{ row }"><span class="num">{{ Number(row.qty) }}</span></template>
        </el-table-column>
        <el-table-column label="实收金额" align="right">
          <template #default="{ row }"><span class="num">¥{{ Number(row.amount).toFixed(2) }}</span></template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 锁账记录 -->
    <el-table v-if="data?.locks?.length" :data="data.locks" size="small" style="margin-top: 12px" max-height="160">
      <el-table-column label="锁定月" width="90">
        <template #default="{ row }"><span class="num">{{ row.period }}</span></template>
      </el-table-column>
      <el-table-column label="锁账时间" width="170">
        <template #default="{ row }"><span class="num mini">{{ row.lockedAt }}</span></template>
      </el-table-column>
      <el-table-column label="说明" min-width="160">
        <template #default="{ row }"><span class="mini">{{ row.lockNote || '—' }}</span></template>
      </el-table-column>
    </el-table>
  </div>
</template>
