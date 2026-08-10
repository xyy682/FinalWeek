<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
</script>

<template>
  <header class="app-header">
    <RouterLink class="brand" to="/courses">FinalWeek</RouterLink>
    <nav aria-label="全局导航">
      <RouterLink to="/courses">课程</RouterLink>
      <RouterLink to="/settings">设置</RouterLink>
      <span v-if="auth.user">{{ auth.user.email }}</span>
    </nav>
  </header>
</template>

<style scoped>
.app-header { align-items: center; background: var(--fw-surface); border-bottom: 1px solid var(--fw-border); display: flex; justify-content: space-between; min-height: 64px; padding: 0 max(16px, calc((100vw - 1280px) / 2)); }
.brand { color: var(--fw-text); font-size: 18px; font-weight: 700; text-decoration: none; }
nav { align-items: center; display: flex; gap: 20px; }
nav a { color: var(--fw-text-secondary); text-decoration: none; }
nav a.router-link-active { color: var(--fw-primary); }
nav span { color: var(--fw-text-secondary); font-size: 13px; }
@media (max-width: 767px) { nav span { display: none; } }
</style>

