<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand-row"><span class="brand-mark">卖</span><div><h1>园区小卖账房 · 售卖机 ERP</h1><p>内部使用 · 凭邀请码注册后登录</p></div></div>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit">
        <el-form-item prop="username"><el-input v-model="form.username" placeholder="账号（3-32 位字母数字）" autocomplete="username" /></el-form-item>
        <el-form-item prop="password"><el-input v-model="form.password" type="password" show-password :placeholder="mode === 'register' ? '设置密码（至少 8 位）' : '密码'" :autocomplete="mode === 'register' ? 'new-password' : 'current-password'" /></el-form-item>
        <template v-if="mode === 'register'">
          <el-form-item prop="displayName"><el-input v-model="form.displayName" placeholder="姓名（显示名，如：小洪）" maxlength="30" /></el-form-item>
          <el-form-item prop="inviteCode"><el-input v-model="form.inviteCode" placeholder="邀请码（内部使用，请向管理员索取）" autocomplete="off" maxlength="64" /></el-form-item>
        </template>
        <el-button type="primary" class="login-btn" :loading="loading" @click="submit">{{ mode === 'login' ? '登 录' : '注 册' }}</el-button>
      </el-form>
      <div class="hint">
        <a v-if="mode === 'login'" href="#" @click.prevent="switchMode('register')">没有账号？凭邀请码注册</a>
        <a v-else href="#" @click.prevent="switchMode('login')">已有账号？去登录</a>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { setSession } from '@/utils/session'

// 登录 / 注册双模式（2026-08-19 邀请码注册 · 全系统统一契约，服务端 /api/auth/register 校验邀请码）
const router = useRouter()
const route = useRoute()
const formRef = ref()
const loading = ref(false)
const mode = ref<'login' | 'register'>('login')
const form = reactive({ username: '', password: '', displayName: '', inviteCode: '' })
const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  inviteCode: [{ required: true, message: '请输入邀请码', trigger: 'blur' }],
}
function switchMode(m: 'login' | 'register') { mode.value = m; formRef.value?.clearValidate() }
async function submit() {
  await formRef.value.validate()
  loading.value = true
  try {
    const data: any = mode.value === 'register'
      ? await request.post('/auth/register', { username: form.username.trim(), password: form.password, displayName: form.displayName.trim(), inviteCode: form.inviteCode.trim() })
      : await request.post('/auth/login', { username: form.username.trim(), password: form.password })
    setSession(data.token, data.displayName, data.role)
    ElMessage.success((mode.value === 'register' ? '注册成功，欢迎 ' : '欢迎回来，') + data.displayName)
    router.replace((route.query.redirect as string) || '/workbench')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #0f172a; }
.login-card { width: 400px; background: #fff; border-radius: 14px; padding: 36px 36px 28px; box-shadow: 0 20px 60px rgba(0,0,0,.35); }
.brand-row { display: flex; gap: 14px; align-items: center; margin-bottom: 26px; }
.brand-mark { width: 48px; height: 48px; border-radius: 12px; background: #409eff; color: #fff; font-size: 24px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.brand-row h1 { margin: 0; font-size: 18px; color: #0f172a; }
.brand-row p { margin: 2px 0 0; font-size: 12px; color: #909399; }
.login-btn { width: 100%; height: 42px; margin-top: 4px; }
.hint { margin-top: 16px; text-align: center; font-size: 12px; }
.hint a { color: #409eff; text-decoration: none; }
</style>
