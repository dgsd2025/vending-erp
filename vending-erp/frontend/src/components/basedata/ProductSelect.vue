<script setup lang="ts">
import { ref } from 'vue'
import { pageProducts, type Product } from '@/api/basedata'

/**
 * 商品远程搜索下拉:输入 名称/编码/条码 搜索,选中 emit 商品 id。
 * 多处复用:货道绑SKU / 别名绑定 / 参数覆盖行。
 */
const props = defineProps<{
  modelValue?: number | null
  placeholder?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: number | null): void
}>()

const options = ref<Product[]>([])
const loading = ref(false)

async function search(keyword: string) {
  loading.value = true
  try {
    const page = await pageProducts({ current: 1, size: 20, keyword: keyword || undefined })
    options.value = page.records
  } finally {
    loading.value = false
  }
}

function onChange(value: number | null) {
  emit('update:modelValue', value)
}

// 初始载入一页,便于直接下拉选
search('')
</script>

<template>
  <el-select
    :model-value="props.modelValue"
    filterable
    remote
    clearable
    :remote-method="search"
    :loading="loading"
    :placeholder="props.placeholder || '搜商品名/编码/条码'"
    style="width: 100%"
    @update:model-value="onChange"
  >
    <el-option
      v-for="p in options"
      :key="p.id"
      :value="p.id!"
      :label="`${p.skuCode} · ${p.productName}`"
    >
      <span class="num" style="margin-right: 8px">{{ p.skuCode }}</span>
      <span>{{ p.productName }}</span>
      <span class="mini" style="margin-left: 8px">{{ p.productStatus }}</span>
    </el-option>
  </el-select>
</template>
