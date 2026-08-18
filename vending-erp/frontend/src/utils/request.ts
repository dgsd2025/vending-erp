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

request.interceptors.request.use((config) => {
  // 预留:SSO 接入后从 store/localStorage 取 token
  const token = localStorage.getItem('vend_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
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
      if (!location.pathname.startsWith('/login')) location.href = `/login?redirect=${encodeURIComponent(location.pathname)}`
      return Promise.reject(error)
    }
    ElMessage.error(error?.response?.data?.message || error?.message || '网络异常')
    return Promise.reject(error)
  },
)

export default request
