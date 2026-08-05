<script setup lang="ts">
import { useRoute } from 'vue-router'
import DataFreshnessBar from '@/components/DataFreshnessBar.vue'

const route = useRoute()

const menus = [
  { path: '/dashboard', title: '工作台' },
  { path: '/import', title: '导入中心' },
  { path: '/products', title: '商品档案' },
  { path: '/purchase', title: '采购' },
  { path: '/inventory', title: '库存' },
  { path: '/reports', title: '报表' },
  { path: '/settings', title: '设置中心' },
]
</script>

<template>
  <el-container class="h-screen">
    <el-aside width="200px" class="border-r border-gray-200">
      <div class="h-56px flex items-center justify-center font-bold text-16px">
        售卖机 ERP
      </div>
      <el-menu router :default-active="route.path">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          {{ m.title }}
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="flex items-center justify-between border-b border-gray-200">
        <span class="text-14px text-gray-500">{{ route.meta.title || '' }}</span>
        <DataFreshnessBar />
      </el-header>
      <el-main class="bg-gray-50">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
}
</style>
