<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listAccounts, type AccountRow } from '@/api/money'
import {
  ADJUST_REASONS, confirmCashAdjust, createCashAdjust,
} from '@/api/cashcheck'

/**
 * 资金调整单弹窗(M3-5,审计 P1-7:钱盘差异的唯一出口;期初修正也走这里)。
 *
 * 两步在一个弹窗里走完:
 * ① 建单:账户(仅真实账户)/ 调整金额 ±(+实际多于系统收 / −实际少于系统支)/
 *    原因枚举(盘盈·盘亏·手续费漏记·期初错·其他,其他必备注)→ 提交(待老板确认);
 * ② 老板确认:勾"我是老板"(X-User-Role 角色头,同红冲一把尺子)→ 确认过账 →
 *    后端 DocStatusGuard 防双击 → cash_flow 落流水 → 余额=期初+Σ流水自然修正。
 */

const props = defineProps<{
  modelValue: boolean
  /** 预填(钱盘核对差异行带入):账户/带符号金额/来源核对 id */
  presetAccountId?: number | null
  presetAmount?: number | null
  presetCheckId?: number | null
  /** 已有待确认单据(核对页"去确认"入口):跳过建单直接走确认步 */
  pendingDocId?: number | null
  pendingSummary?: string | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 调整单已确认过账(docId) */
  (e: 'done', docId: number): void
  /** 调整单已建单待确认(docId) */
  (e: 'created', docId: number): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const accounts = ref<AccountRow[]>([])
const realAccounts = computed(() => accounts.value.filter((a) => !a.isVirtual && a.status === 1))

const form = reactive({
  accountId: null as number | null,
  adjustAmount: null as number | null,
  reason: '盘亏',
  remark: '',
})

/** 步骤:form 建单 / confirm 待老板确认 */
const step = ref<'form' | 'confirm'>('form')
const createdDocId = ref<number | null>(null)
const asBoss = ref(false)
const saving = ref(false)
const confirming = ref(false)

watch(visible, async (v) => {
  if (!v) return
  accounts.value = await listAccounts()
  asBoss.value = false
  if (props.pendingDocId) {
    createdDocId.value = props.pendingDocId
    step.value = 'confirm'
    return
  }
  step.value = 'form'
  createdDocId.value = null
  form.accountId = props.presetAccountId ?? null
  form.adjustAmount = props.presetAmount ?? null
  form.reason = props.presetAmount != null ? (props.presetAmount > 0 ? '盘盈' : '盘亏') : '盘亏'
  form.remark = ''
})

const selectedAccount = computed(() =>
  realAccounts.value.find((a) => a.id === form.accountId) || null)

/** 方向随金额符号(后端同规则校验) */
const direction = computed(() =>
  form.adjustAmount == null || form.adjustAmount === 0 ? null : form.adjustAmount > 0 ? '收' : '支')

const afterBalance = computed(() => {
  if (!selectedAccount.value || form.adjustAmount == null) return null
  return Number(selectedAccount.value.balance) + Number(form.adjustAmount)
})

async function doCreate() {
  if (!form.accountId) {
    ElMessage.warning('先选账户(只有真实账户能调;虚拟账户余额由业务单据推算)')
    return
  }
  if (!form.adjustAmount) {
    ElMessage.warning('调整金额不能为 0(+实际多于系统收 / −实际少于系统支)')
    return
  }
  if (form.reason === '其他' && !form.remark.trim()) {
    ElMessage.warning('原因「其他」必须填写备注说明(留痕才许兜底)')
    return
  }
  saving.value = true
  try {
    createdDocId.value = await createCashAdjust({
      accountId: form.accountId,
      adjustAmount: form.adjustAmount,
      reason: form.reason,
      remark: form.remark.trim() || null,
      cashCheckId: props.presetCheckId ?? null,
    })
    emit('created', createdDocId.value)
    step.value = 'confirm'
    ElMessage.success('调整单已建单并提交——下一步老板确认才落流水')
  } finally {
    saving.value = false
  }
}

async function doConfirm() {
  if (!createdDocId.value) return
  if (!asBoss.value) {
    ElMessage.warning('资金调整确认限老板角色:请勾选「我是老板」')
    return
  }
  confirming.value = true
  try {
    await confirmCashAdjust(createdDocId.value)
    ElMessage.success('已确认过账:流水已落账,账户余额已修正(余额=期初+Σ流水)')
    emit('done', createdDocId.value)
    visible.value = false
  } finally {
    confirming.value = false
  }
}

function money(v: number | string | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}
</script>

<template>
  <el-dialog v-model="visible" title="💱 资金调整单 · 钱盘差异的唯一出口" width="560px">
    <!-- ⚡任务来源 + 流程条(设计思维律②:领着人干活) -->
    <el-alert type="info" :closable="false" style="margin-bottom: 10px">
      <template #title>
        ⚡ 来源:钱盘核对差异 / 期初设错修正 · 流程:<b>① 建单(账户/金额±/原因)→ ② 老板确认 → ③ 自动落流水修余额</b>
      </template>
    </el-alert>

    <!-- 步骤① 建单 -->
    <el-form v-if="step === 'form'" label-width="110px">
      <el-form-item label="账户" required>
        <el-select v-model="form.accountId" placeholder="选真实账户" style="width: 100%">
          <el-option
            v-for="a in realAccounts"
            :key="a.id"
            :value="a.id"
            :label="`${a.accountName}(现余额 ¥${money(a.balance)})`"
          />
        </el-select>
        <span class="mini">虚拟账户(平台待结算/老板垫付)不可手工收支——余额永远由业务单据推算</span>
      </el-form-item>
      <el-form-item label="调整金额 ¥" required>
        <el-input-number v-model="form.adjustAmount" :precision="2" :step="1" style="width: 200px" />
        <span class="mini" style="margin-left: 8px">
          带符号:<b>+ 实际多于系统(收)</b> / <b>− 实际少于系统(支)</b>
          <template v-if="direction">→ 本单方向:<b>{{ direction }}</b></template>
        </span>
      </el-form-item>
      <el-form-item label="原因" required>
        <el-radio-group v-model="form.reason">
          <el-radio-button v-for="r in ADJUST_REASONS" :key="r" :value="r">{{ r }}</el-radio-button>
        </el-radio-group>
        <span class="mini">盘盈只能收;盘亏/手续费漏记只能支;期初错两向都行;其他必备注</span>
      </el-form-item>
      <el-form-item :label="form.reason === '其他' ? '备注(必填)' : '备注'">
        <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="差异怎么来的,一句话讲清(op_log 留痕)" />
      </el-form-item>
      <el-form-item v-if="afterBalance != null" label="调整后余额">
        <b class="num" :style="afterBalance < 0 ? 'color:#c0392b' : ''">¥{{ money(afterBalance) }}</b>
        <span class="mini" style="margin-left: 6px">= 现余额 {{ money(selectedAccount?.balance) }} {{ direction === '收' ? '+' : '−' }} {{ money(Math.abs(form.adjustAmount || 0)) }}</span>
      </el-form-item>
    </el-form>

    <!-- 步骤② 老板确认 -->
    <div v-else>
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>
          调整单 #{{ createdDocId }} 已提交,<b>老板确认后才落流水改余额</b>(钱只能被单据改)。
        </template>
      </el-alert>
      <p v-if="pendingSummary" class="mini" style="margin: 0 0 10px">{{ pendingSummary }}</p>
      <el-checkbox v-model="asBoss">我是老板(资金调整确认限老板角色)</el-checkbox>
    </div>

    <template #footer>
      <el-button @click="visible = false">先关上</el-button>
      <el-button v-if="step === 'form'" type="primary" :loading="saving" @click="doCreate">
        建单并提交 →
      </el-button>
      <el-button v-else type="warning" :loading="confirming" @click="doConfirm">
        ✓ 老板确认(过账落流水)
      </el-button>
    </template>
  </el-dialog>
</template>
