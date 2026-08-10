<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => String(route.meta.title ?? 'FinalWeek'))
</script>

<template>
  <main class="page-shell">
    <RouterLink to="/">FinalWeek</RouterLink>
    <section>
      <p class="eyebrow">功能将按实施计划逐阶段交付</p>
      <h1>{{ title }}</h1>
      <p>当前已建立独立 URL 和页面边界。</p>
    </section>
  </main>
</template>

<style scoped>
.page-shell { max-width: 1280px; margin: 0 auto; padding: 32px; }
.page-shell > a { color: var(--fw-primary); font-weight: 700; text-decoration: none; }
section { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; margin-top: 32px; padding: 32px; }
.eyebrow, p { color: var(--fw-text-secondary); }
h1 { font-size: 28px; line-height: 36px; margin: 8px 0; }
@media (max-width: 767px) { .page-shell { padding: 24px 16px; } }
</style>

