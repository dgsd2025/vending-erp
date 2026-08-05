<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeMachineStatus,
  createMachine,
  initSlots,
  listSlots,
  pageMachines,
  updateMachine,
  updateSlot,
  type Machine,
  type Slot,
} from '@/api/basedata'
import ProductSelect from './ProductSelect.vue'

/**
 * 机器与货道 Tab(对照 mockup p12 左卡):
 * 机器 CRUD(后台设备ID唯一)+ 停用;货道抽屉:按机器列货道 / 绑SKU / 容量 / 批量初始化。
 */
const router = useRouter()
const rows = ref<Machine[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ current: 1, size: 20 })

// ---- 机器表单 ----
const formVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const editingId = ref<number | null>(null)
const blank = (): Machine => ({
  machineCode: '',
  machineName: '',
  deviceId: '',
  location: '',
  model: '',
  onlineDate: null,
  remark: '',
})
const form = reactive<Machine>(blank())

// ---- 货道抽屉 ----
const slotDrawer = ref(false)
const slotMachine = ref<Machine | null>(null)
const slots = ref<Slot[]>([])
const slotsLoading = ref(false)
const initForm = reactive({ slotCount: 79, capacity: 6 })

async function load() {
  loading.value = true
  try {
    const page = await pageMachines({ current: query.current, size: query.size })
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

function openEdit(row: Machine) {
  isEdit.value = true
  editingId.value = row.id!
  Object.assign(form, blank(), row)
  formVisible.value = true
}

async function save() {
  if (!form.machineCode || !form.machineName || !form.deviceId) {
    ElMessage.warning('机器编号 / 名称 / 后台设备ID 必填')
    return
  }
  saving.value = true
  try {
    if (isEdit.value && editingId.value) {
      await updateMachine(editingId.value, { ...form })
    } else {
      await createMachine({ ...form })
    }
    ElMessage.success('已保存(op_log 已留痕)')
    formVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function flip(row: Machine, target: string) {
  if (target === '停用') {
    await ElMessageBox.confirm(
      `停用「${row.machineName}」?撤点应先做存货退库(M1-4 单据引擎),再停用;历史数据全部保留。`,
      '确认停用',
      { type: 'warning' },
    )
  }
  await changeMachineStatus(row.id!, target)
  ElMessage.success(`已置为「${target}」`)
  load()
}

async function openSlots(row: Machine) {
  slotMachine.value = row
  slotDrawer.value = true
  await reloadSlots()
}

async function reloadSlots() {
  if (!slotMachine.value?.id) return
  slotsLoading.value = true
  try {
    slots.value = await listSlots(slotMachine.value.id)
  } finally {
    slotsLoading.value = false
  }
}

async function doInit() {
  if (!slotMachine.value?.id) return
  const created = await initSlots(slotMachine.value.id, {
    slotCount: initForm.slotCount,
    capacity: initForm.capacity,
  })
  ElMessage.success(`初始化完成:新增 ${created.length} 道(已存在的自动跳过)`)
  await reloadSlots()
  load()
}

async function saveSlot(slot: Slot, patch: { productId?: number; capacity?: number; slotStatus?: string }) {
  await updateSlot(slot.id, patch)
  ElMessage.success(`货道 ${slot.slotNo} 已更新`)
  await reloadSlots()
}

function statusChip(s?: string) {
  if (s === '在线' || s === '正常') return 'c-green'
  if (s === '故障') return 'c-amber'
  return 'c-gray'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="ledger-card" style="display: flex; gap: 10px; align-items: center">
      <span class="mini">🏭 <b>后台设备ID是与厂家后台(fanmaiji.top)对齐的锚点</b>,录错整条数据链对不上;撤点=先退库再停用,历史保留。</span>
      <el-button type="primary" style="margin-left: auto" @click="openCreate">＋ 新增机器</el-button>
    </div>

    <div class="ledger-card" style="padding: 0; overflow: hidden">
      <el-table :data="rows" v-loading="loading" @row-dblclick="openEdit">
        <el-table-column prop="machineCode" label="编号" width="100">
          <template #default="{ row }"><span class="num mini">{{ row.machineCode }}</span></template>
        </el-table-column>
        <el-table-column prop="machineName" label="机器" min-width="120">
          <template #default="{ row }">
            <b class="name-link" @click="router.push(`/machines/${row.id}`)">{{ row.machineName }} ↗</b>
          </template>
        </el-table-column>
        <el-table-column prop="deviceId" label="后台设备ID" min-width="150">
          <template #default="{ row }"><span class="num mini">{{ row.deviceId }}</span></template>
        </el-table-column>
        <el-table-column prop="location" label="点位" width="110" />
        <el-table-column label="货道" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ row.slotCount ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <span class="chip" :class="statusChip(row.machineStatus)">{{ row.machineStatus }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280">
          <template #default="{ row }">
            <el-button link type="success" size="small" @click="router.push(`/machines/${row.id}`)">详情 ▸</el-button>
            <el-button link type="primary" size="small" @click="openSlots(row)">货道配置</el-button>
            <el-button link size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.machineStatus !== '停用'" link type="danger" size="small" @click="flip(row, '停用')">停用</el-button>
            <el-button v-else link type="success" size="small" @click="flip(row, '在线')">恢复</el-button>
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

    <!-- 机器表单 -->
    <el-dialog v-model="formVisible" :title="isEdit ? `编辑机器 · ${form.machineCode}` : '新增机器(上线向导第①步:建档)'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="机器编号" required>
          <el-input v-model="form.machineCode" :disabled="isEdit" placeholder="内部编号,如 M01" />
        </el-form-item>
        <el-form-item label="机器名称" required>
          <el-input v-model="form.machineName" placeholder="如 1楼售卖机" />
        </el-form-item>
        <el-form-item label="后台设备ID" required>
          <el-input v-model="form.deviceId" placeholder="如 8CFCA017C113888(厂家后台设备列表可查)" />
        </el-form-item>
        <el-form-item label="点位">
          <el-input v-model="form.location" placeholder="位置描述" />
        </el-form-item>
        <el-form-item label="机型">
          <el-input v-model="form.model" />
        </el-form-item>
        <el-form-item label="上线日期">
          <el-date-picker v-model="form.onlineDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <p class="mini" style="margin: 0 0 0 110px">
        上线向导:①建档 → ②配货道(货道配置里批量初始化)→ ③首次铺货单(M1-4)→ ④纳入补货计算(M2)。
      </p>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 货道抽屉 -->
    <el-drawer v-model="slotDrawer" :title="`🗄 货道配置 · ${slotMachine?.machineName ?? ''}(planogram)`" size="760px">
      <div class="ledger-card" style="display: flex; gap: 10px; align-items: center; padding: 12px 14px">
        <span class="mini">批量初始化(机器×货道号,自动生成 01..NN,已存在跳过):</span>
        <span class="mini">道数</span>
        <el-input-number v-model="initForm.slotCount" :min="1" :max="200" :precision="0" style="width: 110px" />
        <span class="mini">容量</span>
        <el-input-number v-model="initForm.capacity" :min="0" :precision="0" style="width: 100px" />
        <el-button type="primary" size="small" @click="doInit">批量初始化</el-button>
      </div>
      <el-table :data="slots" v-loading="slotsLoading" size="small" border max-height="560">
        <el-table-column prop="slotNo" label="货道" width="70">
          <template #default="{ row }"><b class="num">{{ row.slotNo }}</b></template>
        </el-table-column>
        <el-table-column label="绑定商品" min-width="220">
          <template #default="{ row }">
            <ProductSelect
              :model-value="row.productId"
              placeholder="空货道 · 选商品绑定"
              @update:model-value="(v: number | null) => saveSlot(row, { productId: v ?? 0 })"
            />
          </template>
        </el-table-column>
        <el-table-column label="容量" width="130">
          <template #default="{ row }">
            <el-input-number
              :model-value="Number(row.capacity)"
              :min="0"
              :precision="0"
              size="small"
              style="width: 100px"
              @change="(v: number) => saveSlot(row, { capacity: v })"
            />
          </template>
        </el-table-column>
        <el-table-column label="现量" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ Number(row.currentQty) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-select
              :model-value="row.slotStatus"
              size="small"
              style="width: 90px"
              @update:model-value="(v: string) => saveSlot(row, { slotStatus: v })"
            >
              <el-option label="正常" value="正常" />
              <el-option label="停用" value="停用" />
              <el-option label="故障" value="故障" />
            </el-select>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有货道,先批量初始化" :image-size="60" />
        </template>
      </el-table>
      <p class="mini" style="margin-top: 10px">
        现量为推算值,权威以后台缺货页/盘点为准;容量是机器层补货水位 S 的硬上限(S ≤ 该SKU货道数×单道容量)。
      </p>
    </el-drawer>
  </div>
</template>
