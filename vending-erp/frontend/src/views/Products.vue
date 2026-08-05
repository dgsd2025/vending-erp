<script setup lang="ts">
import ProductPanel from '@/components/basedata/ProductPanel.vue'

/**
 * 商品档案列表页(对照 mockup p3 的列表部分)。
 * 单品分析下钻页(体检报告式)属 M1-6,列表行已留「详情 ▸」跳转钩子。
 */
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / 商品 · 单品分析</div>
    <div class="ledger-title">
      <h2>商品档案</h2>
      <span class="sub">谁在赚钱、谁在占地方,一目了然(经营数字随 M1-6 接入销售数据点亮)</span>
    </div>
    <p class="ledger-note">
      点商品行可<b>编辑档案 + 管理别名</b>;后台名字对不上的,「待绑别名」里绑一次终身生效。停售 ≠ 删除,有流水的商品永不删。
    </p>
    <ProductPanel />
  </div>
</template>
