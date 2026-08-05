<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '@/stores/app'

/**
 * 顶部「数据截至 YYYY-MM-DD」水印(全局铁律:数据新鲜度一等公民)。
 * 占位实现:日期由 app store 提供,后续导入完成后自动更新。
 */
const appStore = useAppStore()
const text = computed(() =>
  appStore.dataAsOf ? `数据截至 ${appStore.dataAsOf}` : '数据截至 ——(尚未导入数据)',
)
</script>

<template>
  <el-tag :type="appStore.dataAsOf ? 'success' : 'info'" effect="plain" size="large">
    {{ text }}
  </el-tag>
</template>
