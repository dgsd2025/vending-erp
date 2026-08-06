import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', name: 'dashboard', meta: { title: '工作台' }, component: () => import('@/views/Dashboard.vue') },
    { path: '/import', name: 'import', meta: { title: '导入中心' }, component: () => import('@/views/Imports.vue') },
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
    { path: '/staff/:name', name: 'staff-detail', meta: { title: '员工详情' }, component: () => import('@/views/StaffDetail.vue') },
  ],
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 售卖机 ERP` : '智慧园区售卖机 ERP'
})

export default router
