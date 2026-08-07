<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DataFreshnessBar from '@/components/DataFreshnessBar.vue'
import { isOffline, pendingTasks, replayQueue, replaying } from '@/utils/offline-queue'

/**
 * 应用骨架(对照 mockup V15 sidebar):深绿账房侧栏 + 米纸色主区。
 * 菜单分组与 mockup 对齐;未到里程碑的入口画出来标灰(卡位不可点),防"做没做"分不清。
 * M2-5:≤768px 视口自动进手机模式——侧栏收成汉堡抽屉,顶部常驻离线黄条(有暂存才出现)。
 */
const route = useRoute()
const router = useRouter()

/** 手机模式:汉堡抽屉开合;切路由自动收起 */
const menuOpen = ref(false)
watch(() => route.path, () => { menuOpen.value = false })

const groups: { label?: string; items: { path?: string; ico: string; title: string; milestone?: string }[] }[] = [
  {
    items: [
      { path: '/dashboard', ico: '🏠', title: '经营驾驶舱' },
      { path: '/tasks', ico: '📅', title: '任务日历' },
      { path: '/replenish', ico: '🤖', title: 'AI 补货提示' },
    ],
  },
  {
    label: '日常台账',
    items: [
      { path: '/import', ico: '📥', title: '导入中心' },
      { path: '/outbound', ico: '🚚', title: '出库上架' },
      { path: '/purchase', ico: '🚛', title: '采购入库' },
      { path: '/inventory', ico: '📦', title: '库存管理' },
      { path: '/stocktake', ico: '📋', title: '盘点' },
    ],
  },
  {
    label: '钱账',
    items: [
      { path: '/money', ico: '💰', title: '资金与对账' },
      { path: '/suppliers', ico: '🏭', title: '供应商往来' },
      { path: '/assets', ico: '🏦', title: '资产家底' },
    ],
  },
  {
    label: 'BI 经营分析',
    items: [
      { path: '/bi', ico: '📊', title: 'BI 经营分析' },
      { path: '/reports', ico: '📈', title: '报表' },
      { path: '/products', ico: '🧃', title: '商品 · 单品分析' },
      { path: '/pdca', ico: '🔄', title: '改进循环 PDCA' },
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
    || (path === '/settings' && route.name === 'machine-detail')
    || (path === '/tasks' && route.name === 'staff-detail'))
</script>

<template>
  <div class="app-shell">
    <!-- 手机顶栏(≤768px 才显示):汉堡 + 标题 -->
    <header class="m-topbar">
      <button class="burger" aria-label="菜单" @click="menuOpen = !menuOpen">☰</button>
      <span class="m-title">园区小卖 · 账房</span>
    </header>
    <div v-if="menuOpen" class="side-mask" @click="menuOpen = false"></div>
    <aside class="sidebar" :class="{ open: menuOpen }">
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
        <DataFreshnessBar variant="badge" />
        <span class="foot-line">无登录(SSO 接入前占位)· 嵌入智慧园区</span>
      </div>
    </aside>
    <main class="main">
      <!-- 离线回传黄条(M2-5):断网或有暂存任务时常显,恢复自动重传 -->
      <div v-if="isOffline || pendingTasks.length" class="offline-bar" data-block="offline-bar">
        <template v-if="isOffline">📴 当前离线:录入存本机不丢,提交会离线暂存</template>
        <template v-else>🟡 网络已恢复</template>
        <template v-if="pendingTasks.length">
          · 暂存 <b>{{ pendingTasks.length }}</b> 条待回传,恢复后自动重传
          <button v-if="!isOffline" class="retry-btn" :disabled="replaying" @click="replayQueue()">
            {{ replaying ? '重传中…' : '立即重传' }}
          </button>
        </template>
      </div>
      <!-- 全站统一数据新鲜度水印(铁律1):每页顶部,超 3 天灰化 + 建议先导数据 -->
      <DataFreshnessBar variant="bar" />
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

/* 离线回传黄条(M2-5,全局:盘点/配货单共用) */
.offline-bar {
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--amber-soft);
  color: var(--amber);
  border: 1px solid var(--amber);
  border-radius: 10px;
  padding: 9px 14px;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 12px;
}
.offline-bar .retry-btn {
  margin-left: 8px;
  border: none;
  border-radius: 8px;
  background: var(--amber);
  color: #fff;
  font-weight: 700;
  padding: 4px 12px;
  cursor: pointer;
}

/* ============ M2-5 手机模式(≤768px):汉堡抽屉侧栏 ============ */
.m-topbar {
  display: none;
}
@media (max-width: 768px) {
  .m-topbar {
    display: flex;
    align-items: center;
    gap: 10px;
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    height: 52px;
    z-index: 40;
    background: var(--green-deep);
    color: #fff;
    padding: 0 12px;
  }
  .m-topbar .burger {
    font-size: 22px;
    background: none;
    border: none;
    color: #fff;
    padding: 6px 10px;
  }
  .m-topbar .m-title {
    font-family: var(--serif);
    font-size: 16px;
    font-weight: 700;
    letter-spacing: 1px;
  }
  .app-shell {
    padding-top: 52px;
  }
  .sidebar {
    position: fixed;
    top: 52px;
    left: 0;
    bottom: 0;
    height: auto;
    z-index: 40;
    transform: translateX(-100%);
    transition: transform 0.22s ease;
    box-shadow: 4px 0 18px rgba(0, 0, 0, 0.25);
  }
  .sidebar.open {
    transform: translateX(0);
  }
  .sidebar .logo {
    display: none; /* 顶栏已有标题,抽屉省一截高度 */
  }
  .side-mask {
    position: fixed;
    inset: 52px 0 0 0;
    z-index: 35;
    background: rgba(0, 0, 0, 0.4);
  }
  .main {
    padding: 14px 12px 40px;
  }
  .offline-bar {
    top: 56px; /* 贴在 52px 固定顶栏下面,不被盖住 */
    border-radius: 8px;
  }
}
</style>
