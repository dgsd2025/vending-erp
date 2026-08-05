import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', name: 'dashboard', meta: { title: '工作台' }, component: () => import('@/views/Dashboard.vue') },
    { path: '/import', name: 'import', meta: { title: '导入中心' }, component: () => import('@/views/Imports.vue') },
    { path: '/products', name: 'products', meta: { title: '商品档案' }, component: () => import('@/views/Products.vue') },
    { path: '/purchase', name: 'purchase', meta: { title: '采购' }, component: () => import('@/views/Purchase.vue') },
    { path: '/inventory', name: 'inventory', meta: { title: '库存' }, component: () => import('@/views/Inventory.vue') },
    { path: '/reports', name: 'reports', meta: { title: '报表' }, component: () => import('@/views/Reports.vue') },
    { path: '/settings', name: 'settings', meta: { title: '设置中心' }, component: () => import('@/views/Settings.vue') },
  ],
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 售卖机 ERP` : '智慧园区售卖机 ERP'
})

export default router
