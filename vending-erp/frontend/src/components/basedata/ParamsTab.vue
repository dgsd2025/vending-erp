<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteReplenishConfig,
  getGlobalReplenishConfig,
  listReplenishConfigs,
  pageMachines,
  pageOpLogs,
  saveReplenishConfig,
  type Machine,
  type OpLog,
  type ReplenishConfig,
} from '@/api/basedata'
import ProductSelect from './ProductSelect.vue'

/**
 * 参数与阈值 Tab:全局 (R,S) 参数 + 按 SKU/机器覆盖行。
 * 铁律:每次改动写 op_log(旧值→新值→改动人),底部直接展示最近参数改动留痕。
 */
const globalForm = reactive<ReplenishConfig>({
  machineId: 0,
  productId: 0,
  cycleDays: 15,
  serviceLevel: 0.95,
  leadTimeDays: 1,
  expireWarnDays: 10,
})
const overrides = ref<ReplenishConfig[]>([])
const logs = ref<OpLog[]>([])
const machines = ref<Machine[]>([])
const saving = ref(false)

// 覆盖行编辑弹窗
const ovVisible = ref(false)
const ovForm = reactive<ReplenishConfig>({ machineId: 0, productId: 0 })

async function load() {
  const [global, all, logPage, machinePage] = await Promise.all([
    getGlobalReplenishConfig(),
    listReplenishConfigs(),
    pageOpLogs({ current: 1, size: 8, targetType: 'replenish_config' }),
    pageMachines({ current: 1, size: 100 }),
  ])
  Object.assign(globalForm, global)
  overrides.value = all.filter((c) => !(c.machineId === 0 && c.productId === 0))
  logs.value = logPage.records
  machines.value = machinePage.records
}

async function saveGlobal() {
  saving.value = true
  try {
    await saveReplenishConfig({ ...globalForm, machineId: 0, productId: 0 })
    ElMessage.success('全局参数已保存(旧值→新值已记 op_log)')
    load()
  } finally {
    saving.value = false
  }
}

function openOverride(row?: ReplenishConfig) {
  Object.assign(ovForm, row ?? {
    machineId: 0,
    productId: 0,
    cycleDays: globalForm.cycleDays,
    serviceLevel: globalForm.serviceLevel,
    leadTimeDays: globalForm.leadTimeDays,
    expireWarnDays: globalForm.expireWarnDays,
  })
  ovVisible.value = true
}

async function saveOverride() {
  if (!ovForm.machineId && !ovForm.productId) {
    ElMessage.warning('覆盖行必须选 机器 或 商品(否则就是全局参数)')
    return
  }
  await saveReplenishConfig({ ...ovForm })
  ElMessage.success('覆盖参数已保存(op_log 已留痕)')
  ovVisible.value = false
  load()
}

async function removeOverride(row: ReplenishConfig) {
  await ElMessageBox.confirm('删除该覆盖行?此范围将回落到全局参数。', '确认删除', { type: 'warning' })
  await deleteReplenishConfig(row.id!)
  ElMessage.success('已删除,回落全局')
  load()
}

function machineName(id?: number) {
  if (!id) return '全部机器'
  return machines.value.find((m) => m.id === id)?.machineName ?? `机器#${id}`
}

onMounted(load)
</script>

<template>
  <div>
    <div class="ledger-card">
      <h3>🎛 全局补货参数 <span class="hint">(R,S) 模型输入 · 改动留痕可追</span></h3>
      <el-form inline label-width="auto">
        <el-form-item label="补货周期 R(天)">
          <el-input-number v-model="globalForm.cycleDays as number" :min="1" :precision="0" />
        </el-form-item>
        <el-form-item label="服务水平">
          <el-input-number v-model="globalForm.serviceLevel as number" :min="0.5" :max="0.99" :step="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="提前期 L(天)">
          <el-input-number v-model="globalForm.leadTimeDays as number" :min="0" :precision="1" :step="0.5" />
        </el-form-item>
        <el-form-item label="临期预警(天)">
          <el-input-number v-model="globalForm.expireWarnDays as number" :min="1" :precision="0" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="saveGlobal">保存全局参数</el-button>
        </el-form-item>
      </el-form>
      <p class="mini" style="margin: 4px 0 0">
        S = 日均 d̄ × (R+L) + 安全库存;服务水平参考:爆款 0.98 / 长尾 0.90 / 新品试销 0.85。R 默认 15 天仅用于采购口径。
      </p>
    </div>

    <div class="ledger-card">
      <h3>按 SKU / 机器覆盖 <span class="hint">细覆盖粗:机器×SKU > SKU > 机器 > 全局</span>
        <el-button type="primary" size="small" style="margin-left: auto" @click="openOverride()">＋ 新增覆盖</el-button>
      </h3>
      <el-table :data="overrides" size="small">
        <el-table-column prop="scopeType" label="作用域" width="90">
          <template #default="{ row }"><span class="chip c-blue">{{ row.scopeType }}</span></template>
        </el-table-column>
        <el-table-column label="机器" width="120">
          <template #default="{ row }">{{ machineName(row.machineId) }}</template>
        </el-table-column>
        <el-table-column label="商品" width="110">
          <template #default="{ row }">{{ row.productId ? `SKU#${row.productId}` : '全部商品' }}</template>
        </el-table-column>
        <el-table-column label="R(天)" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ row.cycleDays }}</span></template>
        </el-table-column>
        <el-table-column label="服务水平" width="90" align="right">
          <template #default="{ row }"><span class="num">{{ Number(row.serviceLevel).toFixed(2) }}</span></template>
        </el-table-column>
        <el-table-column label="L(天)" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ Number(row.leadTimeDays) }}</span></template>
        </el-table-column>
        <el-table-column label="临期预警" width="80" align="right">
          <template #default="{ row }"><span class="num">{{ row.expireWarnDays ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openOverride(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeOverride(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无覆盖行,全部按全局参数" :image-size="50" />
        </template>
      </el-table>
    </div>

    <div class="ledger-card">
      <h3>📜 参数改动留痕 <span class="hint">谁改的、旧值→新值,永远可查</span></h3>
      <el-table :data="logs" size="small">
        <el-table-column prop="opTime" label="时间" width="160">
          <template #default="{ row }"><span class="num mini">{{ row.opTime }}</span></template>
        </el-table-column>
        <el-table-column prop="userName" label="改动人" width="100" />
        <el-table-column prop="action" label="动作" width="90" />
        <el-table-column label="旧值 → 新值" min-width="260">
          <template #default="{ row }">
            <span class="mini">{{ row.beforeJson ?? '(新增)' }} → {{ row.afterJson ?? '(删除)' }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有参数改动记录" :image-size="50" />
        </template>
      </el-table>
    </div>

    <el-dialog v-model="ovVisible" title="参数覆盖行" width="500px">
      <el-form label-width="110px">
        <el-form-item label="机器">
          <el-select v-model="ovForm.machineId" style="width: 100%">
            <el-option label="全部机器(不限)" :value="0" />
            <el-option v-for="m in machines" :key="m.id" :label="m.machineName" :value="m.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品">
          <ProductSelect
            :model-value="ovForm.productId || null"
            placeholder="全部商品(不限)"
            @update:model-value="(v: number | null) => (ovForm.productId = v ?? 0)"
          />
        </el-form-item>
        <el-form-item label="补货周期 R">
          <el-input-number v-model="ovForm.cycleDays as number" :min="1" :precision="0" />
        </el-form-item>
        <el-form-item label="服务水平">
          <el-input-number v-model="ovForm.serviceLevel as number" :min="0.5" :max="0.99" :step="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="提前期 L">
          <el-input-number v-model="ovForm.leadTimeDays as number" :min="0" :precision="1" :step="0.5" />
        </el-form-item>
        <el-form-item label="临期预警(天)">
          <el-input-number v-model="ovForm.expireWarnDays as number" :min="1" :precision="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ovVisible = false">取消</el-button>
        <el-button type="primary" @click="saveOverride">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
