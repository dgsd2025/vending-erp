<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createOfflineSale, listOfflineSales, reverseOfflineSale, type OfflineSaleRow } from '@/api/expense'
import { listAccounts, type AccountRow } from '@/api/money'
import { pageMachines, type Machine } from '@/api/basedata'
import ProductSelect from '@/components/basedata/ProductSelect.vue'

/**
 * 线下收入复合单弹窗(M3-4,P2-13 穿行场景5:机器故障,顾客微信直转老板):
 * 一次录入(机器/SKU/数量/金额/收款账户)→ 后端同事务三件套:
 * ① sale_record(线下补录,OFFLINE- 单号,不入待结算/销售额,不扣机器账)
 * ② cash_flow(其他收入-平台外) ③ 豁免标记(下次盘点差异行勾「线下豁免」不算损耗)。
 */

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ (e: 'created', saleRecordId: number): void }>()

const machines = ref<Machine[]>([])
const realAccounts = ref<AccountRow[]>([])
const recent = ref<OfflineSaleRow[]>([])

const saving = ref(false)
const form = reactive({
  machineId: null as number | null,
  productId: null as number | null,
  qty: 1 as number | null,
  amount: null as number | null,
  accountId: null as number | null,
  remark: '',
})

async function loadDicts() {
  const [machinePage, accounts, offline] = await Promise.all([
    pageMachines({ current: 1, size: 100 }),
    listAccounts(),
    listOfflineSales(10),
  ])
  machines.value = machinePage.records
  realAccounts.value = accounts.filter((a) => !a.isVirtual && a.status === 1)
  recent.value = offline
}

async function doCreate() {
  if (!form.machineId || !form.productId) {
    ElMessage.warning('请选机器和商品')
    return
  }
  if (!form.qty || form.qty <= 0 || !form.amount || form.amount <= 0) {
    ElMessage.warning('数量和实收金额必须为正数')
    return
  }
  if (!form.accountId) {
    ElMessage.warning('请选收款账户(老板微信/现金等真实账户)')
    return
  }
  saving.value = true
  try {
    const resp = await createOfflineSale({
      machineId: form.machineId,
      productId: form.productId,
      qty: form.qty,
      amount: form.amount,
      accountId: form.accountId,
      remark: form.remark || undefined,
    })
    ElMessage.success(`复合单已落地:${resp.orderNo} · 流水 #${resp.cashFlowId}(其他收入-平台外)`)
    ElMessage.info(resp.exemptHint)
    emit('created', resp.saleRecordId)
    visible.value = false
  } finally {
    saving.value = false
  }
}

/** 一键冲销(M3-9 逆向出口:三件套整体反向;老板守卫+备注强制) */
const reversingId = ref<number | null>(null)
async function doReverse(row: OfflineSaleRow) {
  try {
    const { value } = await ElMessageBox.prompt(
      `冲销「${row.orderNo}」(¥${Number(row.amount).toFixed(2)})?三件套整体反向:` +
        '销售红冲行 + 反向流水(钱原路退出账户);历史不删。老板操作,原因备注必填。',
      '一键冲销(老板)', { confirmButtonText: '确认冲销', cancelButtonText: '取消', inputPlaceholder: '如:顾客没转账,录错了' },
    )
    if (!value) {
      ElMessage.warning('冲销必须填写原因备注(留痕)')
      return
    }
    reversingId.value = row.saleRecordId
    const resp = await reverseOfflineSale(row.saleRecordId, value)
    ElMessage.success(`已冲销:${resp.orderNo} · 反向流水 #${resp.cashFlowId}`)
    emit('created', resp.saleRecordId) // 让父页刷新流水与账户余额
    await loadDicts()
  } catch {
    /* 取消/后端已弹错(非老板/已冲销过) */
  } finally {
    reversingId.value = null
  }
}

watch(visible, (v) => {
  if (v) {
    form.qty = 1
    form.amount = null
    form.remark = ''
    loadDicts()
  }
})

onMounted(() => {
  if (visible.value) loadDicts()
})
</script>

<template>
  <el-dialog v-model="visible" title="🛒 线下收入复合单(机器故障直收)" width="520px" append-to-body>
    <div class="mini" style="margin-bottom: 10px">
      一次录入,系统同事务生成三件套:<b>销售记录(线下补录·不入待结算)+ 流水(其他收入-平台外)+ 盘点豁免标记</b>
      ——钱货口径都对,不污染平台结算,也不冤枉损耗
    </div>
    <el-form label-width="90px">
      <el-form-item label="机器">
        <el-select v-model="form.machineId" placeholder="哪台机器故障" style="width: 100%">
          <el-option v-for="m in machines" :key="m.id" :value="m.id!" :label="m.machineName" />
        </el-select>
      </el-form-item>
      <el-form-item label="商品">
        <ProductSelect v-model="form.productId" placeholder="卖的哪个 SKU" />
      </el-form-item>
      <el-form-item label="数量">
        <el-input-number v-model="form.qty" :min="1" style="width: 140px" />
      </el-form-item>
      <el-form-item label="实收金额">
        <el-input-number v-model="form.amount" :min="0.01" :precision="2" style="width: 140px" />
        <span class="mini" style="margin-left: 8px">如微信直转 13.2</span>
      </el-form-item>
      <el-form-item label="收款账户">
        <el-select v-model="form.accountId" placeholder="老板微信/现金(真实账户)" style="width: 100%">
          <el-option v-for="a in realAccounts" :key="a.id" :value="a.id" :label="`${a.accountName}(${a.accountType})`" />
        </el-select>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" placeholder="可选,如:4楼机卡货,现场扫码卖" />
      </el-form-item>
    </el-form>

    <div v-if="recent.length" class="mini" style="margin-top: 4px">
      近期线下补录:
      <div v-for="r in recent" :key="r.saleRecordId" class="mini num recent-row">
        {{ r.orderNo }} · {{ Number(r.qty) }} 件 · ¥{{ Number(r.amount).toFixed(2) }} · {{ r.bizTime }}
        <span v-if="r.reversal" class="mini">(冲销行)</span>
        <span v-else-if="r.reversed" class="mini">(已冲销)</span>
        <el-button
          v-else
          size="small"
          link
          type="danger"
          :loading="reversingId === r.saleRecordId"
          @click="doReverse(r)"
        >
          冲销
        </el-button>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="doCreate">一次录入(三件套落地)</el-button>
    </template>
  </el-dialog>
</template>
