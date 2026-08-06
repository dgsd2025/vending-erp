<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  EQUIPMENT_STATUS,
  EXPENSE_CATEGORIES,
  confirmExpense,
  createExpense,
  listEquipment,
  listExpenses,
  updateEquipment,
  type EquipmentRow,
  type ExpenseRow,
} from '@/api/expense'
import { listAccounts, uploadAttachment, type AccountRow } from '@/api/money'

/**
 * 支出面板(M3-4,§9.3 场景8 杂费/设备):
 * 支出录入(电费/维修/杂支/设备购置)→ 传凭证(付款截图/发票,硬门禁)→ 确认落流水(杂费行);
 * 设备购置确认时同步进设备台账(购入价/日期/折余=购入价,展示回本用,不进流水)。
 */

const rows = ref<ExpenseRow[]>([])
const equipment = ref<EquipmentRow[]>([])
const loading = ref(false)
const realAccounts = ref<AccountRow[]>([])

async function load() {
  loading.value = true
  try {
    ;[rows.value, equipment.value] = await Promise.all([listExpenses(), listEquipment()])
  } finally {
    loading.value = false
  }
}

async function loadAccounts() {
  const all = await listAccounts()
  realAccounts.value = all.filter((a) => !a.isVirtual && a.status === 1)
}

// ============ 录入 ============

const creating = ref(false)
const form = reactive({
  category: '电费' as string,
  amount: null as number | null,
  accountId: null as number | null,
  bizDate: '' as string,
  equipName: '',
  remark: '',
})

async function doCreate() {
  if (!form.amount || form.amount <= 0) {
    ElMessage.warning('金额必须为正数')
    return
  }
  if (!form.accountId) {
    ElMessage.warning('请选支出账户')
    return
  }
  if (form.category === '设备购置' && !form.equipName.trim()) {
    ElMessage.warning('设备购置必须填设备名称(确认后进台账)')
    return
  }
  creating.value = true
  try {
    const id = await createExpense({
      category: form.category,
      amount: form.amount,
      accountId: form.accountId,
      bizDate: form.bizDate || undefined,
      equipName: form.equipName.trim() || undefined,
      remark: form.remark || undefined,
    })
    ElMessage.success(`支出单已录入(待确认)#${id}——传凭证后点「确认落流水」`)
    form.amount = null
    form.equipName = ''
    form.remark = ''
    load()
  } finally {
    creating.value = false
  }
}

// ============ 凭证 + 确认 ============

const uploadingId = ref<number | null>(null)
const confirmingId = ref<number | null>(null)

async function onVoucherPick(row: ExpenseRow, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploadingId.value = row.id
  try {
    await uploadAttachment('expense', row.id, file.name.toLowerCase().endsWith('.pdf') ? '发票' : '转账截图', file)
    ElMessage.success('凭证已上传(可确认落流水)')
    load()
  } finally {
    uploadingId.value = null
  }
}

async function doConfirm(row: ExpenseRow) {
  if (!row.attachmentCount) {
    ElMessage.warning(`「${row.expNo}」还没传凭证——付款截图/发票必传(硬门禁,后端也会拒)`)
    return
  }
  confirmingId.value = row.id
  try {
    const after = await confirmExpense(row.id)
    ElMessage.success(
      after.isEquipment
        ? `已确认:流水已落(杂费行)+ 设备「${after.equipName}」已进台账 #${after.equipmentId}`
        : '已确认:流水已落(杂费行)',
    )
    load()
  } finally {
    confirmingId.value = null
  }
}

// ============ 设备台账编辑 ============

const equipVisible = ref(false)
const equipSaving = ref(false)
const equipRow = ref<EquipmentRow | null>(null)
const equipForm = reactive({ equipName: '', residualValue: null as number | null, equipStatus: '在用' as string })

function openEquipEdit(row: EquipmentRow) {
  equipRow.value = row
  equipForm.equipName = row.equipName
  equipForm.residualValue = row.residualValue == null ? null : Number(row.residualValue)
  equipForm.equipStatus = row.equipStatus
  equipVisible.value = true
}

async function saveEquip() {
  if (!equipRow.value) return
  if (!equipForm.equipName.trim()) {
    ElMessage.warning('设备名称必填')
    return
  }
  equipSaving.value = true
  try {
    await updateEquipment(equipRow.value.id, {
      equipName: equipForm.equipName.trim(),
      machineId: equipRow.value.machineId ?? undefined,
      residualValue: equipForm.residualValue,
      equipStatus: equipForm.equipStatus,
    })
    ElMessage.success('台账已更新(op_log 留痕)')
    equipVisible.value = false
    load()
  } finally {
    equipSaving.value = false
  }
}

const statusChip = (s: string) => (s === '待确认' ? 'c-amber' : 'c-green')

onMounted(() => {
  load()
  loadAccounts()
})

defineExpose({ reload: load })
</script>

<template>
  <div class="expense-panel">
    <!-- 录入 -->
    <div class="flex items-center gap-8px flex-wrap" style="margin-bottom: 10px">
      <el-radio-group v-model="form.category" size="small">
        <el-radio-button v-for="c in EXPENSE_CATEGORIES" :key="c" :value="c">{{ c }}</el-radio-button>
      </el-radio-group>
      <el-input-number v-model="form.amount" :min="0.01" :precision="2" placeholder="金额" size="small" style="width: 130px" />
      <el-select v-model="form.accountId" placeholder="支出账户" size="small" style="width: 160px">
        <el-option v-for="a in realAccounts" :key="a.id" :value="a.id" :label="`${a.accountName}(${a.accountType})`" />
      </el-select>
      <el-date-picker v-model="form.bizDate" type="date" placeholder="支出日期(默认今天)" value-format="YYYY-MM-DD" size="small" style="width: 160px" />
      <el-input v-if="form.category === '设备购置'" v-model="form.equipName" placeholder="设备名称(必填,进台账)" size="small" style="width: 170px" />
      <el-input v-model="form.remark" placeholder="摘要(可选)" size="small" style="width: 150px" />
      <el-button type="primary" size="small" :loading="creating" @click="doCreate">录入(待确认)</el-button>
    </div>
    <div class="mini" style="margin-bottom: 8px">
      流程:录入 → 传凭证(付款截图/发票,<b>必传</b>)→ 确认落流水(利润表「杂费」行);设备购置确认时同步进台账
    </div>

    <!-- 支出列表 -->
    <el-table :data="rows" size="small" v-loading="loading">
      <el-table-column prop="expNo" label="单号" width="150">
        <template #default="{ row }"><span class="num">{{ row.expNo }}</span></template>
      </el-table-column>
      <el-table-column prop="category" label="类别" width="80" />
      <el-table-column label="金额" width="90" align="right">
        <template #default="{ row }"><span class="num">¥{{ Number(row.amount).toFixed(2) }}</span></template>
      </el-table-column>
      <el-table-column prop="accountName" label="账户" width="140" />
      <el-table-column prop="bizDate" label="日期" width="100" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <span class="chip" :class="statusChip(row.expStatus)">{{ row.expStatus }}</span>
        </template>
      </el-table-column>
      <el-table-column label="凭证" width="70" align="center">
        <template #default="{ row }"><span class="mini">{{ row.attachmentCount }} 件</span></template>
      </el-table-column>
      <el-table-column label="设备" min-width="110">
        <template #default="{ row }">
          <span v-if="row.equipName" class="mini">{{ row.equipName }}<template v-if="row.equipmentId"> · 台账 #{{ row.equipmentId }}</template></span>
          <span v-else class="mini">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210">
        <template #default="{ row }">
          <template v-if="row.expStatus === '待确认'">
            <label class="voucher-btn">
              {{ uploadingId === row.id ? '上传中…' : '传凭证' }}
              <input type="file" accept=".jpg,.jpeg,.png,.webp,.gif,.bmp,.pdf" hidden @change="onVoucherPick(row, $event)" />
            </label>
            <el-button size="small" type="success" :disabled="!row.attachmentCount" :loading="confirmingId === row.id" @click="doConfirm(row)">
              确认落流水
            </el-button>
          </template>
          <span v-else class="mini">已入账 {{ row.bookPeriod }}</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 设备台账 -->
    <h4 style="margin: 14px 0 6px">🧊 设备台账 <span class="mini">回本进度展示,不进利润表与流水(单台&lt;5000 一次性费用化)</span></h4>
    <el-table :data="equipment" size="small">
      <el-table-column prop="equipName" label="设备" min-width="130" />
      <el-table-column label="购入价" width="100" align="right">
        <template #default="{ row }"><span class="num">¥{{ Number(row.buyPrice).toFixed(2) }}</span></template>
      </el-table-column>
      <el-table-column prop="buyDate" label="购入日期" width="105" />
      <el-table-column label="折余(展示)" width="110" align="right">
        <template #default="{ row }">
          <span v-if="row.residualValue != null" class="num">¥{{ Number(row.residualValue).toFixed(2) }}</span>
          <span v-else class="mini">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="70">
        <template #default="{ row }">
          <span class="chip" :class="row.equipStatus === '在用' ? 'c-green' : 'c-gray'">{{ row.equipStatus }}</span>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="100">
        <template #default="{ row }"><span class="mini">支出单 #{{ row.expenseId || '—' }}</span></template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button size="small" @click="openEquipEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 台账编辑 -->
    <el-dialog v-model="equipVisible" title="✏️ 编辑设备台账" width="420px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="设备名称">
          <el-input v-model="equipForm.equipName" />
        </el-form-item>
        <el-form-item label="折余价值">
          <el-input-number v-model="equipForm.residualValue" :min="0" :precision="2" style="width: 160px" />
          <span class="mini" style="margin-left: 8px">展示用,不进流水</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="equipForm.equipStatus">
            <el-radio-button v-for="s in EQUIPMENT_STATUS" :key="s" :value="s">{{ s }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="equipVisible = false">取消</el-button>
        <el-button type="primary" :loading="equipSaving" @click="saveEquip">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.voucher-btn {
  display: inline-block;
  padding: 3px 8px;
  margin-right: 8px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
  user-select: none;
}
.voucher-btn:hover {
  border-color: var(--el-color-primary, #409eff);
  color: var(--el-color-primary, #409eff);
}
</style>
