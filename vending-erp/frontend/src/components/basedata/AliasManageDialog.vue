<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bindAlias, pageAliases, unbindAlias, type Product, type SkuAlias } from '@/api/basedata'

/**
 * 单个商品的别名管理:已绑列表 + 解绑 + 手工新增绑定。
 * 绑定键 = 后台商品编号 + 条码(不绑名称,冲刺 0 拍板)。
 */
const props = defineProps<{
  visible: boolean
  product: Product | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'changed'): void
}>()

const aliases = ref<SkuAlias[]>([])
const loading = ref(false)
const addForm = reactive({ aliasCode: '', aliasBarcode: '', aliasName: '' })

async function load() {
  if (!props.product?.id) return
  loading.value = true
  try {
    const page = await pageAliases({ current: 1, size: 100, productId: props.product.id })
    aliases.value = page.records
  } finally {
    loading.value = false
  }
}

watch(
  () => props.visible,
  (show) => {
    if (show) {
      addForm.aliasCode = ''
      addForm.aliasBarcode = ''
      addForm.aliasName = ''
      load()
    }
  },
)

async function addBinding() {
  if (!props.product?.id) return
  if (!addForm.aliasName) {
    ElMessage.warning('后台商品名必填(仅留痕展示)')
    return
  }
  if (!addForm.aliasCode && !addForm.aliasBarcode) {
    ElMessage.warning('后台商品编号与条码至少填一个(不允许按名称绑定)')
    return
  }
  await bindAlias({ ...addForm, productId: props.product.id })
  ElMessage.success('已绑定,终身生效')
  addForm.aliasCode = ''
  addForm.aliasBarcode = ''
  addForm.aliasName = ''
  await load()
  emit('changed')
}

async function removeBinding(row: SkuAlias) {
  await ElMessageBox.confirm(
    `解绑「${row.aliasName}」?之后该后台商品的销售会重新进待绑定队列。解绑动作会记操作日志。`,
    '确认解绑',
    { type: 'warning' },
  )
  await unbindAlias(row.id!)
  ElMessage.success('已解绑(op_log 已留痕)')
  await load()
  emit('changed')
}
</script>

<template>
  <el-dialog
    :model-value="props.visible"
    :title="`别名管理 · ${props.product?.skuCode ?? ''} ${props.product?.productName ?? ''}`"
    width="640px"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <p class="mini" style="margin-top: 0">
      绑定键 = <b>后台商品编号 + 条码</b>(后台同名可挂不同商品,名称不可靠);绑一次终身生效。
    </p>
    <el-table :data="aliases" v-loading="loading" size="small" border>
      <el-table-column prop="aliasCode" label="后台编号" width="110" />
      <el-table-column prop="aliasBarcode" label="条码" width="130" />
      <el-table-column prop="aliasName" label="后台商品名" min-width="150" />
      <el-table-column prop="bindSource" label="来源" width="100" />
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="removeBinding(row)">解绑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="ledger-card" style="margin: 14px 0 0; padding: 12px 14px">
      <h3 style="font-size: 14px">＋ 手工绑定一个后台别名</h3>
      <div style="display: flex; gap: 8px">
        <el-input v-model="addForm.aliasCode" placeholder="后台商品编号" style="width: 150px" />
        <el-input v-model="addForm.aliasBarcode" placeholder="条码" style="width: 150px" />
        <el-input v-model="addForm.aliasName" placeholder="后台商品名(留痕)" style="flex: 1" />
        <el-button type="primary" @click="addBinding">绑定</el-button>
      </div>
    </div>
  </el-dialog>
</template>
