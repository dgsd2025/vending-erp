<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import DataFreshnessBar from '@/components/DataFreshnessBar.vue'

/**
 * 应用骨架(对照 mockup V15 sidebar):深绿账房侧栏 + 米纸色主区。
 * 菜单分组与 mockup 对齐;未到里程碑的入口画出来标灰(卡位不可点),防"做没做"分不清。
 */
const route = useRoute()
const router = useRouter()

const groups: { label?: string; items: { path?: string; ico: string; title: string; milestone?: string }[] }[] = [
  {
    items: [
      { path: '/dashboard', ico: '🏠', title: '经营驾驶舱' },
      { ico: '🤖', title: 'AI 补货提示', milestone: 'M2' },
    ],
  },
  {
    label: '日常台账',
    items: [
      { path: '/import', ico: '📥', title: '导入中心' },
      { path: '/purchase', ico: '🚛', title: '采购入库' },
      { path: '/inventory', ico: '📦', title: '库存管理' },
      { ico: '📋', title: '盘点', milestone: 'M2' },
    ],
  },
  {
    label: '钱账',
    items: [
      { ico: '💰', title: '资金与对账', milestone: 'M3' },
      { ico: '🏭', title: '供应商往来', milestone: 'M3' },
    ],
  },
  {
    label: 'BI 经营分析',
    items: [
      { path: '/reports', ico: '📈', title: '报表' },
      { path: '/products', ico: '🧃', title: '商品 · 单品分析' },
      { ico: '🔄', title: '改进循环 PDCA', milestone: 'M4' },
    ],
  },
  {
    label: '系统',
    items: [{ path: '/settings', ico: '⚙️', title: '设置中心' }],
  },
]

const isActive = (path?: string) =>
  !!path && (route.path === path || route.path.startsWith(path + '/')
    || (path === '/products' && route.name === 'product-detail')
    || (path === '/settings' && route.name === 'machine-detail'))
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="logo">
        <h1>园区小卖 · 账房</h1>
        <p>VENDING ERP · M1</p>
      </div>
      <nav class="nav">
        <template v-for="(g, gi) in groups" :key="gi">
          <div v-if="g.label" class="grp">{{ g.label }}</div>
          <a
            v-for="it in g.items"
            :key="it.title"
            :class="{ on: isActive(it.path), dim: !it.path }"
            @click="it.path && router.push(it.path)"
          >
            <span class="ico">{{ it.ico }}</span>{{ it.title }}
            <span v-if="it.milestone" class="ms">{{ it.milestone }}</span>
          </a>
        </template>
      </nav>
      <div class="side-foot">
        <DataFreshnessBar />
        <span class="foot-line">无登录(SSO 接入前占位)· 嵌入智慧园区</span>
      </div>
    </aside>
    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
}
body {
  font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: var(--ink);
  font-size: 14px;
}
.app-shell {
  display: flex;
  min-height: 100vh;
  background: var(--paper);
  background-image: radial-gradient(rgba(34, 49, 42, 0.035) 1px, transparent 1px);
  background-size: 22px 22px;
}
.sidebar {
  width: 216px;
  background: var(--green-deep);
  color: #e8efe9;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
}
.sidebar .logo {
  padding: 22px 20px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
}
.sidebar .logo h1 {
  font-family: var(--serif);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #fff;
  margin: 0;
}
.sidebar .logo p {
  font-size: 11px;
  color: #9db8a8;
  margin: 4px 0 0;
  letter-spacing: 2px;
}
.sidebar .nav {
  flex: 1;
  padding: 14px 10px;
  overflow-y: auto;
}
.sidebar .nav a {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 8px;
  color: #c3d4c8;
  cursor: pointer;
  margin-bottom: 3px;
  font-size: 14px;
  transition: all 0.18s;
  text-decoration: none;
}
.sidebar .nav a .ico {
  font-size: 16px;
  width: 20px;
  text-align: center;
}
.sidebar .nav a:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}
.sidebar .nav a.on {
  background: var(--green);
  color: #fff;
  font-weight: 600;
  box-shadow: inset 3px 0 0 #8fd3ae;
}
.sidebar .nav a.dim {
  color: #7f987f;
  cursor: default;
}
.sidebar .nav a.dim:hover {
  background: transparent;
  color: #7f987f;
}
.sidebar .nav a .ms {
  margin-left: auto;
  font-size: 10px;
  background: rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  padding: 1px 7px;
  letter-spacing: 1px;
}
.sidebar .nav .grp {
  font-size: 10px;
  color: #7f987f;
  letter-spacing: 3px;
  padding: 16px 14px 6px;
}
.side-foot {
  padding: 14px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.12);
  font-size: 11px;
  color: #88a191;
  line-height: 1.7;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.side-foot .foot-line {
  color: #5f7a6b;
}
.main {
  flex: 1;
  padding: 26px 34px 60px;
  max-width: 1220px;
  min-width: 0;
}
</style>
