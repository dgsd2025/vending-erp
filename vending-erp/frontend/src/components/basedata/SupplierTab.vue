<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeSupplierStatus,
  createSupplier,
  pageSuppliers,
  updateSupplier,
  type Supplier,
} from '@/api/basedata'

/**
 * 供应商 Tab:列表 + 新建/编辑(结算方式/账期/期初应付)+ 停用(保留历史往来)。
 */
const rows = ref<Supplier[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ current: 1, size: 20, keyword: '' })

const formVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const editingId = ref<number | null>(null)

const blank = (): Supplier => ({
  supplierCode: '',
  supplierName: '',
  contact: '',
  settleMethod: '现结',
  accountDays: 0,
  openingPayable: 0,
  remark: '',
})
const form = reactive<Supplier>(blank())

async function load() {
  loading.value = true
  try {
    const page = await pageSuppliers({
      current: query.current,
      size: query.size,
      keyword: query.keyword || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  isEdit.value = false
  editingId.value = null
  Object.assign(form, blank())
  formVisible.value = true
}

function openEdit(row: Supplier) {
  isEdit.value = true
  editingId.value = row.id!
  Object.assign(form, blank(), row)
  formVisible.value = true
}

async function save() {
  if (!form.supplierCode || !form.supplierName) {
    ElMessage.warning('供应商编码与名称必填')
    return
  }
  saving.value = true
  try {
    if (isEdit.value && editingId.value) {
      await updateSupplier(editingId.value, { ...form })
    } else {
      await createSupplier({ ...form })
    }
    ElMessage.success('已保存(op_log 已留痕)')
    formVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function flip(row: Supplier) {
  const target = row.coopStatus === '合作中' ? '停用' : '合作中'
  if (target === '停用') {
    await ElMessageBox.confirm(
      `停用「${row.supplierName}」?历史往来与应付全部保留,只是不再出现在新单据的选择里。`,
      '确认停用',
      { type: 'warning' },
    )
  }
  await changeSupplierStatus(row.id!, target)
  ElMessage.success(`已${target === '停用' ? '停用' : '恢复合作'}`)
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="ledger-card" style="display: flex; gap: 10px; align-items: center">
      <span class="mini">🚛 结算方式/账期/期初应付都在档案里;<b>应付余额=期初+Σ采购−Σ退货−Σ抵扣−Σ付款</b>,实时算不落表(M3 点亮)。</span>
      <el-input
        v-model="query.keyword"
        placeholder="搜名称 / 编码"
        clearable
        style="margin-left: auto; width: 200px"
        @keyup.enter="query.current = 1; load()"
        @clear="query.current = 1; load()"
      />
      <el-button type="primary" @click="openCreate">＋ 新增供应商</el-button>
    </div>

    <div class="ledger-card" style="padding: 0; overflow: hidden">
      <el-table :data="rows" v-loading="loading" @row-dblclick="openEdit">
        <el-table-column prop="supplierCode" label="编码" width="100">
          <template #default="{ row }"><span class="num mini">{{ row.supplierCode }}</span></template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="140">
          <template #default="{ row }"><b>{{ row.supplierName }}</b></template>
        </el-table-column>
        <el-table-column prop="contact" label="联系方式" width="140" />
        <el-table-column prop="settleMethod" label="结算方式" width="90" />
        <el-table-column label="账期" width="80" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.settleMethod === '月结' ? `${row.accountDays} 天` : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="期初应付" width="100" align="right">
          <template #default="{ row }">
            <span class="num">¥{{ Number(row.openingPayable ?? 0).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="chip" :class="row.coopStatus === '合作中' ? 'c-green' : 'c-gray'">{{ row.coopStatus }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button
              link
              :type="row.coopStatus === '合作中' ? 'danger' : 'success'"
              size="small"
              @click="flip(row)"
            >
              {{ row.coopStatus === '合作中' ? '停用' : '恢复' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="display: flex; justify-content: flex-end; padding: 10px 14px">
        <el-pagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>

    <el-dialog
      v-model="formVisible"
      :title="isEdit ? `编辑供应商 · ${form.supplierCode}` : '新增供应商'"
      width="540px"
    >
      <el-form label-width="110px">
        <el-form-item label="编码" required>
          <el-input v-model="form.supplierCode" :disabled="isEdit" placeholder="如 GYS01" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.supplierName" placeholder="蔡彩云 / 陈老板 / 拼多多自购" />
        </el-form-item>
        <el-form-item label="联系方式">
          <el-input v-model="form.contact" placeholder="微信 / 电话" />
        </el-form-item>
        <el-form-item label="结算方式">
          <el-radio-group v-model="form.settleMethod">
            <el-radio-button label="现结" />
            <el-radio-button label="月结" />
            <el-radio-button label="预付" />
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.settleMethod === '月结'" label="账期(天)">
          <el-input-number v-model="form.accountDays as number" :min="0" :precision="0" />
          <span class="mini" style="margin-left: 8px">逾期未付首页亮黄灯</span>
        </el-form-item>
        <el-form-item label="期初应付 ¥">
          <el-input-number v-model="form.openingPayable as number" :min="0" :precision="2" :disabled="isEdit" />
          <span class="mini" style="margin-left: 8px">{{ isEdit ? '上线后改动走调整单留痕(M3)' : '上线向导录入' }}</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
