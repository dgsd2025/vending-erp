import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from '@/utils/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', name: 'login', meta: { title: '登录 / 注册', public: true }, component: () => import('@/views/Login.vue') },
    // 平台门户 SSO 中转页：必须 public，不套登录守卫（ole-portal-sso checklist 第 8 步）
    { path: '/sso/callback', name: 'sso-callback', meta: { title: '平台免登', public: true }, component: () => import('@/views/SsoCallback.vue') },
    { path: '/dashboard', name: 'dashboard', meta: { title: '工作台' }, component: () => import('@/views/Dashboard.vue') },
    { path: '/import', name: 'import', meta: { title: '导入中心' }, component: () => import('@/views/Imports.vue') },
    { path: '/outbound', name: 'outbound', meta: { title: '出库上架' }, component: () => import('@/views/Outbound.vue') },
    { path: '/products', name: 'products', meta: { title: '商品档案' }, component: () => import('@/views/Products.vue') },
    { path: '/products/:id', name: 'product-detail', meta: { title: '单品详情' }, component: () => import('@/views/ProductDetail.vue') },
    { path: '/machines/:id', name: 'machine-detail', meta: { title: '机器详情' }, component: () => import('@/views/MachineDetail.vue') },
    { path: '/purchase', name: 'purchase', meta: { title: '采购' }, component: () => import('@/views/Purchase.vue') },
    { path: '/inventory', name: 'inventory', meta: { title: '库存' }, component: () => import('@/views/Inventory.vue') },
    { path: '/stocktake', name: 'stocktake', meta: { title: '盘点' }, component: () => import('@/views/Stocktake.vue') },
    { path: '/reports', name: 'reports', meta: { title: '报表' }, component: () => import('@/views/Reports.vue') },
    { path: '/settings', name: 'settings', meta: { title: '设置中心' }, component: () => import('@/views/Settings.vue') },
    { path: '/tasks', name: 'tasks', meta: { title: '任务日历' }, component: () => import('@/views/TaskCalendar.vue') },
    { path: '/replenish', name: 'replenish', meta: { title: 'AI 补货' }, component: () => import('@/views/Replenish.vue') },
    { path: '/suppliers', name: 'suppliers', meta: { title: '供应商往来' }, component: () => import('@/views/Suppliers.vue') },
    { path: '/assets', name: 'assets', meta: { title: '资产家底' }, component: () => import('@/views/Assets.vue') },
    { path: '/staff/:name', name: 'staff-detail', meta: { title: '员工详情' }, component: () => import('@/views/StaffDetail.vue') },
    { path: '/money', name: 'money', meta: { title: '资金与对账' }, component: () => import('@/views/Money.vue') },
    { path: '/pdca', name: 'pdca', meta: { title: '改进循环' }, component: () => import('@/views/Pdca.vue') },
    { path: '/bi', name: 'bi', meta: { title: 'BI 经营分析' }, component: () => import('@/views/Bi.vue') },
    { path: '/monthly-report', name: 'monthly-report', meta: { title: '月度报表' }, component: () => import('@/views/MonthlyReport.vue') },
    { path: '/guide', name: 'guide', meta: { title: '新手指引' }, component: () => import('@/views/Guide.vue') },
  ],
})

// 2026-08-19 邀请码注册上线:未登录一律去 /login
router.beforeEach((to) => {
  if (!to.meta.public && !isLoggedIn()) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && isLoggedIn()) return '/dashboard'
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 售卖机 ERP` : '智慧园区售卖机 ERP'
})

export default router
