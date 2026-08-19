<template>
  <div class="sso-page">
    <div class="sso-card">
      <template v-if="errorMsg">
        <el-result icon="error" title="平台账号登录失败" :sub-title="errorMsg">
          <template #extra>
            <el-button type="primary" @click="goPortal">回平台重新进入</el-button>
            <el-button @click="goLogin">用本系统账号登录</el-button>
          </template>
        </el-result>
      </template>
      <template v-else>
        <div class="spinner" />
        <p>正在从平台免登进入售卖机 ERP…</p>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { setSession } from '@/utils/session'

/**
 * 平台门户 SSO 中转页（ole-portal-sso · 硬约束 11：拿到 token 后必须 window.location.href 强刷整页，不能 router.push）
 *
 * 两种到达方式：
 *  1) 后端 /api/v1/sso/callback 兑换成功后 302 到这里：#token=..&displayName=..&role=..（token 在 fragment，不进日志/Referer）
 *     失败：#error=..&code=..
 *  2) 门户误把浏览器直接打到前端 /sso/callback?auth_code=..&app_id=..&tenantId=..：原样转给后端 GET 端点（整页跳转，不走 axios）
 */
const PORTAL_URL = 'https://eco.vvaix.com'
const errorMsg = ref<string | null>(null)
let fired = false

function goPortal() { window.location.href = PORTAL_URL }
function goLogin() { window.location.href = '/login' }

onMounted(() => {
  if (fired) return
  fired = true // auth_code 一次性消费，onMounted 重跑也不能二次转发

  const hash = new URLSearchParams(window.location.hash.replace(/^#/, ''))
  const token = hash.get('token')
  if (token) {
    setSession(token, hash.get('displayName') || '', hash.get('role') || '')
    // 清掉 fragment 里的 token 再强刷，避免留在历史记录
    window.history.replaceState(null, '', '/sso/callback')
    window.location.href = '/'
    return
  }
  const err = hash.get('error')
  if (err) {
    errorMsg.value = err
    return
  }
  const query = new URLSearchParams(window.location.search)
  if (query.get('auth_code') || query.get('authCode')) {
    const apiBase = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/+$/, '')
    window.location.replace(`${apiBase}/v1/sso/callback?${query.toString()}`)
    return
  }
  errorMsg.value = '缺少授权参数（auth_code），请从平台工作台重新点击进入'
})
</script>

<style scoped>
.sso-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #0f172a; }
.sso-card { min-width: 360px; max-width: 520px; background: #fff; border-radius: 14px; padding: 40px 36px; text-align: center; box-shadow: 0 20px 60px rgba(0,0,0,.35); }
.sso-card p { margin-top: 14px; color: #606266; }
.spinner { width: 36px; height: 36px; margin: 0 auto; border: 3px solid #e4e7ed; border-top-color: #409eff; border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
