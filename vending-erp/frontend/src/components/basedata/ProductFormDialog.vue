<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createProduct, updateProduct, type Product } from '@/api/basedata'

/**
 * 商品新建/编辑弹窗。编辑时改参考售价,后端会自动写 price_log 留痕。
 */
const props = defineProps<{
  visible: boolean
  /** 传入 = 编辑;不传 = 新建 */
  product?: Product | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'saved'): void
}>()

const CATEGORIES = ['饮料', '泡面', '面包', '卤味', '槟榔', '零食', '其他']

const blank = (): Product => ({
  skuCode: '',
  productName: '',
  barcode: '',
  category: '',
  unit: '瓶',
  boxSpec: 1,
  shelfLifeDays: null,
  refCost: null,
  refPrice: null,
  minDisplayQty: null,
  remark: '',
})

const form = reactive<Product>(blank())
const saving = ref(false)
const isEdit = ref(false)

watch(
  () => props.visible,
  (show) => {
    if (!show) return
    isEdit.value = !!props.product?.id
    Object.assign(form, blank(), props.product ?? {})
  },
)

async function save() {
  if (!form.skuCode || !form.productName) {
    ElMessage.warning('SKU 编码与商品名必填')
    return
  }
  saving.value = true
  try {
    // type=number 的 el-input 给的是字符串,落库前统一转成数字/空
    const num = (v: unknown): number | null =>
      v === '' || v === null || v === undefined || Number.isNaN(Number(v)) ? null : Number(v)
    const payload: Product = {
      ...form,
      boxSpec: num(form.boxSpec) ?? 1,
      shelfLifeDays: num(form.shelfLifeDays),
      refCost: num(form.refCost),
      refPrice: num(form.refPrice),
      minDisplayQty: num(form.minDisplayQty),
    }
    if (isEdit.value && props.product?.id) {
      await updateProduct(props.product.id, payload)
      ElMessage.success('已保存(改动已记操作日志;售价变化另记改价留痕)')
    } else {
      await createProduct(payload)
      ElMessage.success('商品已建档')
    }
    emit('update:visible', false)
    emit('saved')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.visible"
    :title="isEdit ? `编辑商品 · ${form.skuCode}` : '新建商品'"
    width="620px"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <el-form label-width="110px">
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="SKU 编码" required>
            <el-input v-model="form.skuCode" :disabled="isEdit" placeholder="如 SP101" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="商品名" required>
            <el-input v-model="form.productName" placeholder="采购商品名(主名称)" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="条码">
            <el-input v-model="form.barcode" placeholder="条形码,可空" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="分类">
            <el-select v-model="form.category" filterable allow-create clearable style="width: 100%">
              <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="基本单位">
            <el-input v-model="form.unit" placeholder="瓶/袋/盒" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="箱规">
            <el-input v-model="form.boxSpec" type="number" min="1" step="1" placeholder="1" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="保质期(天)">
            <el-input v-model="form.shelfLifeDays" type="number" min="0" step="1" placeholder="天数" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="参考成本 ¥">
            <el-input v-model="form.refCost" type="number" min="0" step="0.1" placeholder="0.00" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="参考售价 ¥">
            <el-input v-model="form.refPrice" type="number" min="0" step="0.1" placeholder="0.00" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="机内上限">
            <el-input v-model="form.minDisplayQty" type="number" min="0" step="1" placeholder="上限" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="备注">
            <el-input v-model="form.remark" type="textarea" :rows="2" />
          </el-form-item>
        </el-col>
      </el-row>
      <p class="mini" style="margin: 0 0 0 110px">
        售价仅为<b>参考价</b>,真实毛利按后台实收算;无采购史时成本用参考成本兜底,毛利显示「—」。
      </p>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
