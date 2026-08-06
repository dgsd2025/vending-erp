<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import MachineSlotTab from '@/components/basedata/MachineSlotTab.vue'
import ProductPanel from '@/components/basedata/ProductPanel.vue'
import SupplierTab from '@/components/basedata/SupplierTab.vue'
import ParamsTab from '@/components/basedata/ParamsTab.vue'
import PeriodLockSection from '@/components/doc/PeriodLockSection.vue'
import AccountTab from '@/components/money/AccountTab.vue'
import { currentUserName } from '@/api/basedata'

/**
 * 设置中心(对照 mockup p12):机器与货道 / 商品 / 供应商 / 资金账户(M3-1 点亮)/ 参数与阈值。
 * 每一次改动都记 op_log:谁改的、改了什么,永远可查。
 */
// 支持 /settings?tab=account 直达(M3-3 结算面板「去定型结算模式」入口)
const route = useRoute()
const validTabs = ['machine', 'product', 'supplier', 'account', 'params']
const initTab = typeof route.query.tab === 'string' && validTabs.includes(route.query.tab) ? route.query.tab : 'machine'
const activeTab = ref(initTab)
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / 系统 / 设置中心</div>
    <div class="ledger-title">
      <h2>设置中心</h2>
      <span class="sub">机器 · 商品 · 供应商 · 账户 · 参数 —— 所有档案在这里增改</span>
    </div>
    <p class="ledger-note">
      身份来自智慧园区主系统(SSO 接入前占位),当前经手人:<b>{{ currentUserName() }}</b>。每一次改动都记日志,谁改的、改了什么,永远可查。
    </p>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="🏭 机器与货道" name="machine">
        <MachineSlotTab v-if="activeTab === 'machine'" />
      </el-tab-pane>
      <el-tab-pane label="🧃 商品" name="product">
        <ProductPanel v-if="activeTab === 'product'" compact />
      </el-tab-pane>
      <el-tab-pane label="🚛 供应商" name="supplier">
        <SupplierTab v-if="activeTab === 'supplier'" />
      </el-tab-pane>
      <el-tab-pane label="💳 资金账户" name="account">
        <AccountTab v-if="activeTab === 'account'" />
      </el-tab-pane>
      <el-tab-pane label="🎛 参数与阈值" name="params">
        <ParamsTab v-if="activeTab === 'params'" />
        <!-- M1-7 锁账小节(P0-2):当前锁账线 / 锁账 / 解锁(老板+备注) / 上期调整一览 -->
        <PeriodLockSection v-if="activeTab === 'params'" />
      </el-tab-pane>
    </el-tabs>

    <p class="ledger-foot-note">
      — 有历史流水的档案<b>永不删,只标停用</b>;人员与角色 / 操作日志全览 / AI 模型配置随对应里程碑加入本页 —
    </p>
  </div>
</template>
