<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ACCOUNT_TYPES,
  REAL_ACCOUNT_TYPES,
  changeAccountStatus,
  createAccount,
  getSettleMode,
  listAccounts,
  renameAccount,
  setOpening,
  setSettleMode,
  type AccountRow,
  type SettleModeResp,
} from '@/api/money'

/**
 * 资金账户 Tab(M3-1,对照 mockup p12):账户列表(带实时余额)/新建/期初一次性设置/停用。
 * 口径:余额 = 期初 + Σ流水,实时推算不落表;期初只能设一次,再改走"资金调整单"(M3-5)。
 * settle.mode 未核实时顶部亮"待核实"横幅(附录D 双模式)。
 */

const rows = ref<AccountRow[]>([])
const loading = ref(false)
const settleMode = ref<SettleModeResp | null>(null)
const modeSaving = ref(false)

const formVisible = ref(false)
const saving = ref(false)
const form = reactive({ accountName: '', accountType: '微信' as string, openingBalance: null as number | null })

const openingVisible = ref(false)
const openingRow = ref<AccountRow | null>(null)
const openingValue = ref<number>(0)

const renameVisible = ref(false)
const renameRow = ref<AccountRow | null>(null)
const renameValue = ref('')

async function load() {
  loading.value = true
  try {
    rows.value = await listAccounts()
  } finally {
    loading.value = false
  }
}

async function loadMode() {
  settleMode.value = await getSettleMode()
}

function openCreate() {
  form.accountName = ''
  form.accountType = '微信'
  form.openingBalance = null
  formVisible.value = true
}

async function save() {
  if (!form.accountName.trim()) {
    ElMessage.warning('账户名必填')
    return
  }
  saving.value = true
  try {
    await createAccount({
      accountName: form.accountName.trim(),
      accountType: form.accountType,
      openingBalance: form.openingBalance,
    })
    ElMessage.success('账户已建(op_log 已留痕)')
    formVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

function openOpening(row: AccountRow) {
  openingRow.value = row
  openingValue.value = 0
  openingVisible.value = true
}

async function saveOpening() {
  if (!openingRow.value) return
  await setOpening(openingRow.value.id, openingValue.value)
  ElMessage.success('期初已设定并锁定(只能设这一次)')
  openingVisible.value = false
  load()
}

function openRename(row: AccountRow) {
  renameRow.value = row
  renameValue.value = row.accountName
  renameVisible.value = true
}

async function saveRename() {
  if (!renameRow.value || !renameValue.value.trim()) return
  await renameAccount(renameRow.value.id, renameValue.value.trim())
  ElMessage.success('已改名')
  renameVisible.value = false
  load()
}

async function flip(row: AccountRow) {
  const disable = row.status === 1
  if (disable) {
    await ElMessageBox.confirm(
      `停用「${row.accountName}」?历史流水全部保留,只是不能再走新流水。有流水的账户永不删,只停用。`,
      '确认停用',
      { type: 'warning' },
    )
  }
  await changeAccountStatus(row.id, disable ? 0 : 1)
  ElMessage.success(disable ? '已停用' : '已启用')
  load()
}

async function chooseMode(mode: string) {
  if (!settleMode.value || settleMode.value.mode === mode) return
  const opt = settleMode.value.options.find((o) => o.mode === mode)
  await ElMessageBox.confirm(
    `定型为「${opt?.label}」?${opt?.description}。已录数据自动按所选模式重述,改动写 op_log。`,
    '设置结算模式',
    { type: 'warning' },
  )
  modeSaving.value = true
  try {
    settleMode.value = await setSettleMode(mode)
    ElMessage.success('结算模式已更新')
  } finally {
    modeSaving.value = false
  }
}

function money(v: number | string) {
  return Number(v ?? 0).toFixed(2)
}

onMounted(() => {
  load()
  loadMode()
})
</script>

<template>
  <div>
    <!-- 附录D:settle.mode 未核实横幅 -->
    <el-alert
      v-if="settleMode?.banner"
      :title="settleMode.banner"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 10px"
    />

    <div class="ledger-card" style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
      <span class="mini">
        💳 真实 4 类 + 虚拟 2 类;<b>余额 = 期初 + Σ流水</b>实时推算,每分钱都由单据产生;
        期初<b>只能设一次</b>,改动走"资金调整单"留痕(M3-5)。
      </span>
      <span style="margin-left: auto" class="mini">
        结算模式:
        <el-radio-group
          :model-value="settleMode?.mode"
          size="small"
          :disabled="modeSaving"
          @change="(v: any) => chooseMode(v)"
        >
          <el-radio-button v-for="o in settleMode?.options ?? []" :key="o.mode" :label="o.mode">
            {{ o.label }}
          </el-radio-button>
        </el-radio-group>
      </span>
      <el-button type="primary" @click="openCreate">＋ 新建账户</el-button>
    </div>

    <div class="ledger-card" style="padding: 0; overflow: hidden">
      <el-table :data="rows" v-loading="loading">
        <el-table-column label="账户" min-width="150">
          <template #default="{ row }">
            <b :style="row.status === 0 ? 'color:#999' : ''">{{ row.accountName }}</b>
            <span v-if="row.isVirtual" class="chip c-gray" style="margin-left: 6px">虚拟</span>
          </template>
        </el-table-column>
        <el-table-column prop="accountType" label="类型" width="110" />
        <el-table-column label="期初余额" width="130" align="right">
          <template #default="{ row }">
            <span class="num">¥{{ money(row.openingBalance) }}</span>
            <span v-if="row.openingLocked" class="mini" style="margin-left: 4px" title="期初已锁定,改动走资金调整单">🔒</span>
          </template>
        </el-table-column>
        <el-table-column label="实时余额" width="140" align="right">
          <template #default="{ row }">
            <b class="num" :style="Number(row.balance) < 0 ? 'color:#c0392b' : ''">¥{{ money(row.balance) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <span class="chip" :class="row.status === 1 ? 'c-green' : 'c-gray'">{{ row.status === 1 ? '启用' : '停用' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRename(row)">改名</el-button>
            <el-button
              v-if="!row.openingLocked"
              link
              type="warning"
              size="small"
              @click="openOpening(row)"
            >设期初</el-button>
            <el-tooltip v-else content="期初只能设一次,已锁定;修正请走资金调整单(M3-5)" placement="top">
              <span class="mini" style="margin: 0 8px; color: #bbb">期初已锁</span>
            </el-tooltip>
            <el-button link :type="row.status === 1 ? 'danger' : 'success'" size="small" @click="flip(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!loading && rows.length === 0" class="mini" style="text-align: center; padding: 18px">
        还没有账户。先建「微信 / 银行卡 / 现金」等真实账户;「平台待结算 / 老板垫付」虚拟账户按结算模式需要再建。
      </p>
    </div>

    <!-- 新建账户 -->
    <el-dialog v-model="formVisible" title="新建资金账户" width="480px">
      <el-form label-width="110px">
        <el-form-item label="账户名" required>
          <el-input v-model="form.accountName" placeholder="如 微信-老板 / 建行卡-尾号1234" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.accountType" style="width: 100%">
            <el-option-group label="真实账户(参与钱盘核对)">
              <el-option v-for="t in REAL_ACCOUNT_TYPES" :key="t" :label="t" :value="t" />
            </el-option-group>
            <el-option-group label="虚拟账户(余额由业务单据推算,不可手工收支)">
              <el-option v-for="t in ACCOUNT_TYPES.slice(4)" :key="t" :label="t" :value="t" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="期初余额 ¥">
          <el-input-number v-model="form.openingBalance as number" :precision="2" style="width: 180px" />
          <span class="mini" style="margin-left: 8px">可留空以后设;<b>只能设一次</b></span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 期初一次性设置 -->
    <el-dialog v-model="openingVisible" :title="`设置期初余额 · ${openingRow?.accountName ?? ''}`" width="440px">
      <el-alert
        title="期初余额只能设这一次,确认后锁定;之后的任何修正都必须走「资金调整单」留痕(M3-5)。"
        type="warning"
        :closable="false"
        style="margin-bottom: 12px"
      />
      <el-form label-width="110px">
        <el-form-item label="期初余额 ¥" required>
          <el-input-number v-model="openingValue" :precision="2" style="width: 200px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="openingVisible = false">取消</el-button>
        <el-button type="warning" @click="saveOpening">确认设定并锁定</el-button>
      </template>
    </el-dialog>

    <!-- 改名 -->
    <el-dialog v-model="renameVisible" title="账户改名" width="420px">
      <el-form label-width="90px">
        <el-form-item label="账户名" required>
          <el-input v-model="renameValue" />
        </el-form-item>
      </el-form>
      <p class="mini">类型与期初不可改:类型错了请停用重建;期初修正走资金调整单。</p>
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRename">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
