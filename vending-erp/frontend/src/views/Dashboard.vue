<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getHealth } from '@/api/health'

/** 后端连通状态:pending 检测中 / ok 已连通 / fail 连不上 */
const status = ref<'pending' | 'ok' | 'fail'>('pending')
const detail = ref('')

onMounted(async () => {
  try {
    detail.value = await getHealth()
    status.value = 'ok'
  } catch (e: any) {
    status.value = 'fail'
    detail.value = e?.message || '无法连接后端'
  }
})
</script>

<template>
  <div class="flex flex-col items-center justify-center pt-100px gap-16px">
    <h1 class="text-28px font-bold">售卖机 ERP · 开发中</h1>
    <el-tag v-if="status === 'pending'" type="info" size="large">正在检测后端连通…</el-tag>
    <el-tag v-else-if="status === 'ok'" type="success" size="large">
      后端已连通:{{ detail }}
    </el-tag>
    <el-tag v-else type="danger" size="large">后端未连通:{{ detail }}</el-tag>
    <p class="text-gray-400 text-13px">GET /api/v1/health</p>
  </div>
</template>
