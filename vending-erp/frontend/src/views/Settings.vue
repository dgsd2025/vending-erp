<script setup lang="ts">
import { ref } from 'vue'
import MachineSlotTab from '@/components/basedata/MachineSlotTab.vue'
import ProductPanel from '@/components/basedata/ProductPanel.vue'
import SupplierTab from '@/components/basedata/SupplierTab.vue'
import ParamsTab from '@/components/basedata/ParamsTab.vue'
import { currentUserName } from '@/api/basedata'

/**
 * 设置中心(对照 mockup p12):机器与货道 / 商品 / 供应商 / 资金账户(M3 占位)/ 参数与阈值。
 * 每一次改动都记 op_log:谁改的、改了什么,永远可查。
 */
const activeTab = ref('machine')
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
        <div class="ledger-card" style="text-align: center; padding: 46px 20px">
          <p style="font-size: 15px; margin: 0 0 8px">💳 资金账户 · <b>里程碑 3(钱账)开放</b></p>
          <p class="mini" style="max-width: 520px; margin: 0 auto">
            真实 4 类账户 + 虚拟 2 类;期初余额只能设一次,改动走调整单;余额 = 期初 + Σ流水实时推算,
            每分钱都由单据产生。等 M3 核实平台结算模式后点亮。
          </p>
        </div>
      </el-tab-pane>
      <el-tab-pane label="🎛 参数与阈值" name="params">
        <ParamsTab v-if="activeTab === 'params'" />
      </el-tab-pane>
    </el-tabs>

    <p class="ledger-foot-note">
      — 有历史流水的档案<b>永不删,只标停用</b>;人员与角色 / 操作日志全览 / AI 模型配置随对应里程碑加入本页 —
    </p>
  </div>
</template>
