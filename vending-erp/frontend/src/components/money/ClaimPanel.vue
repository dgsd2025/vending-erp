<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CLAIM_TARGETS,
  abandonClaim,
  claimReceivable,
  createClaim,
  listClaims,
  netShrinkage,
  receiveClaim,
  type ClaimRow,
  type NetShrinkageResp,
} from '@/api/claim'
import { listAccounts, uploadAttachment, type AccountRow } from '@/api/money'

/**
 * 索赔面板(M3-4,§9.3 场景4):索赔列表 + 发起申请 + 到账登记(赔付凭证必传)+ 放弃(备注必填)。
 * 顶部两口径:索赔应收(=Σ申请中,资产快照第4项)· 净损耗(=损耗−已获赔)。
 * 入口:盘点五步向导「索赔」按钮(带盘亏行预填)/ p7 钱账页装配(M3-7)。
 */

const props = defineProps<{
  /** 盘亏发起预填:来源盘点单 */
  prefillSourceId?: number | null
  /** 盘亏发起预填:可索赔差异行 ID(归因=吞货掉货/被盗) */
  prefillItemIds?: number[]
  /** 盘亏发起预填:金额默认=盘亏成本额 */
  prefillAmount?: number | null
  /** 打开面板即弹发起表单(盘点入口用) */
  autoOpenCreate?: boolean
}>()

const emit = defineEmits<{ (e: 'created', claimId: number): void }>()

const rows = ref<ClaimRow[]>([])
const loading = ref(false)
const statusFilter = ref('')
const receivable = ref<number>(0)
const shrinkage = ref<NetShrinkageResp | null>(null)

const realAccounts = ref<AccountRow[]>([])

async function load() {
  loading.value = true
  try {
    ;[rows.value, receivable.value, shrinkage.value] = await Promise.all([
      listClaims(statusFilter.value || undefined),
      claimReceivable(),
      netShrinkage(),
    ])
  } finally {
    loading.value = false
  }
}

async function loadAccounts() {
  const all = await listAccounts()
  realAccounts.value = all.filter((a) => !a.isVirtual && a.status === 1)
}

// ============ 发起申请 ============

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  claimTarget: '厂家' as string,
  amount: null as number | null,
  remark: '',
})

function openCreate() {
  createForm.claimTarget = '厂家'
  createForm.amount = props.prefillAmount ?? null
  createForm.remark = ''
  createVisible.value = true
}

async function doCreate() {
  if (!createForm.amount || createForm.amount <= 0) {
    ElMessage.warning('索赔金额必须为正数(默认=盘亏成本额)')
    return
  }
  creating.value = true
  try {
    const id = await createClaim({
      claimTarget: createForm.claimTarget,
      amount: createForm.amount,
      sourceId: props.prefillSourceId ?? undefined,
      stocktakeItemIds: props.prefillItemIds ?? [],
      remark: createForm.remark || undefined,
    })
    ElMessage.success(`索赔单已发起(申请中,已计入索赔应收)#${id}`)
    createVisible.value = false
    emit('created', id)
    load()
  } finally {
    creating.value = false
  }
}

// ============ 凭证上传 ============

const uploadingId = ref<number | null>(null)

async function onVoucherPick(row: ClaimRow, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploadingId.value = row.id
  try {
    await uploadAttachment('claim', row.id, '赔付凭证', file)
    ElMessage.success('赔付凭证已上传(可登记到账)')
    load()
  } finally {
    uploadingId.value = null
  }
}

// ============ 到账登记 ============

const receiveVisible = ref(false)
const receiving = ref(false)
const receiveRow = ref<ClaimRow | null>(null)
const receiveForm = reactive({ accountId: null as number | null, receivedAmount: null as number | null })

function openReceive(row: ClaimRow) {
  if (!row.attachmentCount) {
    ElMessage.warning(`「${row.claimNo}」还没传赔付凭证——先点「传凭证」(硬门禁,后端也会拒)`)
    return
  }
  receiveRow.value = row
  receiveForm.accountId = null
  receiveForm.receivedAmount = Number(row.amount)
  receiveVisible.value = true
}

async function doReceive() {
  if (!receiveRow.value || !receiveForm.accountId) {
    ElMessage.warning('请选入账账户')
    return
  }
  receiving.value = true
  try {
    await receiveClaim(receiveRow.value.id, {
      accountId: receiveForm.accountId,
      receivedAmount: receiveForm.receivedAmount,
    })
    ElMessage.success('已到账:流水已落(其他收入-赔付),索赔应收已退出')
    receiveVisible.value = false
    load()
  } finally {
    receiving.value = false
  }
}

// ============ 放弃 ============

const abandonVisible = ref(false)
const abandoning = ref(false)
const abandonRow = ref<ClaimRow | null>(null)
const abandonRemark = ref('')

function openAbandon(row: ClaimRow) {
  abandonRow.value = row
  abandonRemark.value = ''
  abandonVisible.value = true
}

async function doAbandon() {
  if (!abandonRow.value) return
  if (!abandonRemark.value.trim()) {
    ElMessage.warning('放弃必须填写原因备注(留痕)')
    return
  }
  abandoning.value = true
  try {
    await abandonClaim(abandonRow.value.id, abandonRemark.value.trim())
    ElMessage.success('已放弃(退出索赔应收,原因已留痕)')
    abandonVisible.value = false
    load()
  } finally {
    abandoning.value = false
  }
}

const statusChip = (s: string) =>
  s === '申请中' ? 'c-amber' : s === '已到账' ? 'c-green' : 'c-gray'

const netText = computed(() => {
  if (!shrinkage.value) return '—'
  return `¥${Number(shrinkage.value.lossAmount).toFixed(2)} − ¥${Number(
    shrinkage.value.compensatedAmount,
  ).toFixed(2)} = ¥${Number(shrinkage.value.netAmount).toFixed(2)}`
})

onMounted(async () => {
  await Promise.all([load(), loadAccounts()])
  if (props.autoOpenCreate) openCreate()
})

defineExpose({ openCreate, reload: load })
</script>

<template>
  <div class="claim-panel">
    <div class="flex items-center gap-12px flex-wrap" style="margin-bottom: 10px">
      <span class="chip c-amber">📌 索赔应收 ¥{{ Number(receivable).toFixed(2) }}(=Σ申请中·进资产快照)</span>
      <span class="chip c-blue">净损耗 = 损耗 − 已获赔:{{ netText }}</span>
      <el-select v-model="statusFilter" placeholder="全部状态" clearable size="small" style="width: 120px" @change="load">
        <el-option label="申请中" value="申请中" />
        <el-option label="已到账" value="已到账" />
        <el-option label="放弃" value="放弃" />
      </el-select>
      <el-button type="primary" size="small" @click="openCreate">🧾 发起索赔</el-button>
    </div>

    <el-table :data="rows" size="small" v-loading="loading">
      <el-table-column prop="claimNo" label="单号" width="150">
        <template #default="{ row }"><span class="num">{{ row.claimNo }}</span></template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          {{ row.sourceType }}<span v-if="row.sourceId" class="mini"> #{{ row.sourceId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="claimTarget" label="对象" width="60" />
      <el-table-column label="索赔金额" width="90" align="right">
        <template #default="{ row }"><span class="num">¥{{ Number(row.amount).toFixed(2) }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <span class="chip" :class="statusChip(row.claimStatus)">{{ row.claimStatus }}</span>
        </template>
      </el-table-column>
      <el-table-column label="到账" width="120" align="right">
        <template #default="{ row }">
          <template v-if="row.claimStatus === '已到账'">
            <span class="num">¥{{ Number(row.receivedAmount).toFixed(2) }}</span>
            <div class="mini">流水 #{{ row.cashFlowId }}</div>
          </template>
          <span v-else class="mini">—</span>
        </template>
      </el-table-column>
      <el-table-column label="凭证" width="70" align="center">
        <template #default="{ row }">
          <span class="mini">{{ row.attachmentCount }} 件</span>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="120">
        <template #default="{ row }"><span class="mini">{{ row.remark || '—' }}</span></template>
      </el-table-column>
      <el-table-column label="操作" width="230">
        <template #default="{ row }">
          <template v-if="row.claimStatus === '申请中'">
            <label class="voucher-btn">
              {{ uploadingId === row.id ? '上传中…' : '传凭证' }}
              <input type="file" accept=".jpg,.jpeg,.png,.webp,.gif,.bmp,.pdf" hidden @change="onVoucherPick(row, $event)" />
            </label>
            <el-button size="small" type="success" :disabled="!row.attachmentCount" @click="openReceive(row)">
              到账登记
            </el-button>
            <el-button size="small" type="danger" plain @click="openAbandon(row)">放弃</el-button>
          </template>
          <span v-else class="mini">已关闭</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 发起申请 -->
    <el-dialog v-model="createVisible" title="🧾 发起索赔申请" width="460px" append-to-body>
      <div v-if="props.prefillItemIds?.length" class="mini" style="margin-bottom: 8px">
        来源:盘点单 #{{ props.prefillSourceId }} 的 {{ props.prefillItemIds.length }} 行可索赔盘亏
        (归因=吞货掉货/被盗),金额已按盘亏成本额预填,可改
      </div>
      <el-form label-width="90px">
        <el-form-item label="索赔对象">
          <el-radio-group v-model="createForm.claimTarget">
            <el-radio-button v-for="t in CLAIM_TARGETS" :key="t" :value="t">{{ t }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="索赔金额">
          <el-input-number v-model="createForm.amount" :min="0.01" :precision="2" style="width: 180px" />
          <span class="mini" style="margin-left: 8px">默认=盘亏成本额</span>
        </el-form-item>
        <el-form-item label="申请说明">
          <el-input v-model="createForm.remark" placeholder="可选,如:3瓶红牛吞货,已联系厂家售后" />
        </el-form-item>
      </el-form>
      <div class="mini">发起后进「申请中」= 计入资产快照「索赔应收」;到账需先传赔付凭证(硬门禁)</div>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">发起申请</el-button>
      </template>
    </el-dialog>

    <!-- 到账登记 -->
    <el-dialog v-model="receiveVisible" title="💰 到账登记" width="440px" append-to-body>
      <div v-if="receiveRow" class="mini" style="margin-bottom: 8px">
        {{ receiveRow.claimNo }} · 对象 {{ receiveRow.claimTarget }} · 索赔 ¥{{ Number(receiveRow.amount).toFixed(2) }}
        · 凭证 {{ receiveRow.attachmentCount }} 件 ✓
      </div>
      <el-form label-width="90px">
        <el-form-item label="入账账户">
          <el-select v-model="receiveForm.accountId" placeholder="选真实账户" style="width: 220px">
            <el-option v-for="a in realAccounts" :key="a.id" :value="a.id" :label="`${a.accountName}(${a.accountType})`" />
          </el-select>
        </el-form-item>
        <el-form-item label="到账金额">
          <el-input-number v-model="receiveForm.receivedAmount" :min="0.01" :precision="2" style="width: 180px" />
          <span class="mini" style="margin-left: 8px">厂家打折赔可改</span>
        </el-form-item>
      </el-form>
      <div class="mini">确认后:流水落「其他收入-赔付」行 + 回填流水号,索赔应收同步退出</div>
      <template #footer>
        <el-button @click="receiveVisible = false">取消</el-button>
        <el-button type="success" :loading="receiving" @click="doReceive">确认到账</el-button>
      </template>
    </el-dialog>

    <!-- 放弃 -->
    <el-dialog v-model="abandonVisible" title="🚫 放弃索赔" width="420px" append-to-body>
      <div v-if="abandonRow" class="mini" style="margin-bottom: 8px">
        {{ abandonRow.claimNo }} · ¥{{ Number(abandonRow.amount).toFixed(2) }} 将退出「索赔应收」
      </div>
      <el-input v-model="abandonRemark" type="textarea" :rows="2" placeholder="放弃原因必填(留痕),如:平台拒赔,金额太小不追了" />
      <template #footer>
        <el-button @click="abandonVisible = false">取消</el-button>
        <el-button type="danger" :loading="abandoning" @click="doAbandon">确认放弃</el-button>
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
