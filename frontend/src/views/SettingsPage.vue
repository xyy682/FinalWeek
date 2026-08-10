<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppHeader from '@/components/AppHeader.vue'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
async function logout() {
  try { await auth.logout(); await router.replace('/login') }
  catch (error) { ElMessage.error(error instanceof ApiError ? `${error.body.message}（${error.body.requestId}）` : '退出失败') }
}
</script>

<template>
  <AppHeader />
  <main class="page-shell"><h1>账号设置</h1><section><h2>当前账号</h2><p>{{ auth.user?.email }}</p><el-button @click="logout">退出登录</el-button></section></main>
</template>

<style scoped>
.page-shell { margin: 0 auto; max-width: 1280px; padding: 48px 32px; }h1 { font-size: 28px; line-height: 36px; }section { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; max-width: 560px; padding: 24px; }h2 { font-size: 20px; margin: 0 0 8px; }p { color: var(--fw-text-secondary); margin: 0 0 24px; }@media (max-width: 767px) { .page-shell { padding: 32px 16px; } }
</style>
