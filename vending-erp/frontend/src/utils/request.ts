import axios from 'axios'
import { ElMessage } from 'element-plus'
import { clearSession } from '@/utils/session'

/**
 * 统一 axios 实例:
 * - baseURL = /api(dev 由 vite proxy 转发到 8081,后端 context-path 也是 /api)
 * - 后端统一返回 R{code,message,data},仅 code===200 时 resolve 出 data
 * - 预留 Authorization 注入(接 SSO 后取 token)
 */
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
})

/**
 * 不注入 Authorization 的白名单(无登录态入口):平台门户 SSO 回调 /api/v1/sso/*、注册/登录。
 * ole-portal-sso 硬约束 9:SSO 端点漏白名单会带上旧/空 token 干扰兑换重试。
 * (本系统没有 X-Current-Company-Id 头,无需公司头排除表)
 */
const AUTH_WHITELIST_PREFIXES = ['/v1/sso/', '/auth/login', '/auth/register']
function isAuthWhitelisted(url: string): boolean {
  const path = url.replace(/^https?:\/\/[^/]+/, '').replace(/^\/api(?=\/)/, '')
  return AUTH_WHITELIST_PREFIXES.some((p) => path.startsWith(p))
}

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('vend_token')
  if (token && !isAuthWhitelisted(config.url || '')) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    // 文件下载(responseType: 'blob')没有 R{code} 外壳,直接把二进制交出去。
    // 走 axios 实例而不是 <a href> 是因为 AuthGateFilter 要 Bearer 令牌,裸链接会 401。
    if (response.config.responseType === 'blob') {
      return response.data
    }
    const res = response.data
    if (res && res.code === 200) {
      return res.data
    }
    const message = res?.message || '请求失败'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  },
  (error) => {
    // 2026-08-19 邀请码注册上线:401 → 回登录页
    if (error?.response?.status === 401) {
      clearSession()
      if (!location.pathname.startsWith('/login') && !location.pathname.startsWith('/sso/')) location.href = `/login?redirect=${encodeURIComponent(location.pathname)}`
      return Promise.reject(error)
    }
    ElMessage.error(error?.response?.data?.message || error?.message || '网络异常')
    return Promise.reject(error)
  },
)

export default request
