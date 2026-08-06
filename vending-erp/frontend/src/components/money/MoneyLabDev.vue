<script setup lang="ts">
import { ref } from 'vue'
import ClaimPanel from './ClaimPanel.vue'
import ExpensePanel from './ExpensePanel.vue'
import OfflineSaleDialog from './OfflineSaleDialog.vue'
import SettlementPanel from './SettlementPanel.vue'

/**
 * ⚠️ M3-4/M3-3 临时验证台(仅 dev 路由 /money-lab,生产构建不注册):
 * 组件(ClaimPanel / ExpensePanel / OfflineSaleDialog / SettlementPanel)在 p7 钱账页装配前的
 * 真浏览器验证入口。M3-7 装配 p7 后删除本文件与 router 里的 dev-only 路由。
 */

const offlineVisible = ref(false)
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / dev 验证台(M3-4 临时,M3-7 装配 p7 后删)</div>
    <div class="ledger-title"><h2>M3-4 组件验证台</h2><span class="sub">索赔 · 杂费/设备 · 线下收入复合单</span></div>

    <div class="ledger-card">
      <h3>🏦 平台结算双模式(SettlementPanel · M3-3)</h3>
      <SettlementPanel />
    </div>

    <div class="ledger-card">
      <h3>🧾 索赔(ClaimPanel)</h3>
      <ClaimPanel />
    </div>

    <div class="ledger-card">
      <h3>💸 支出/设备台账(ExpensePanel)</h3>
      <ExpensePanel />
    </div>

    <div class="ledger-card">
      <h3>🛒 线下收入复合单(OfflineSaleDialog)</h3>
      <el-button type="primary" @click="offlineVisible = true">打开复合单弹窗</el-button>
      <OfflineSaleDialog v-model="offlineVisible" />
    </div>
  </div>
</template>
