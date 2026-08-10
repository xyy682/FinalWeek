<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getSystemStatus } from '@/api/system'

const backendState = ref<'checking' | 'online' | 'offline'>('checking')

onMounted(async () => {
  try {
    await getSystemStatus()
    backendState.value = 'online'
  } catch {
    backendState.value = 'offline'
  }
})
</script>

<template>
  <main class="landing-shell">
    <section class="hero" aria-labelledby="page-title">
      <p class="eyebrow">课程资料理解与复习工具</p>
      <h1 id="page-title">把分散的课程资料，整理成能核验的复习路径。</h1>
      <p class="summary">
        上传 PDF、PPTX、笔记或录课，FinalWeek 会保留原资料位置，生成知识提纲，并支持复习计划与课程问答。
      </p>
      <div class="actions">
        <RouterLink class="primary-action" to="/login">开始使用</RouterLink>
        <span class="service-state" role="status">
          后端状态：{{ backendState === 'checking' ? '检查中' : backendState === 'online' ? '可用' : '暂不可用' }}
        </span>
      </div>
    </section>
    <section class="feature-grid" aria-label="核心能力">
      <article><strong>可靠上传</strong><span>大文件分片与断点续传</span></article>
      <article><strong>来源可核验</strong><span>页码、幻灯片与时间点定位</span></article>
      <article><strong>围绕课程复习</strong><span>提纲、计划与带引用问答</span></article>
    </section>
  </main>
</template>

<style scoped>
.landing-shell { max-width: 1280px; margin: 0 auto; padding: 96px 32px 48px; }
.hero { max-width: 820px; }
.eyebrow { color: var(--fw-primary); font-weight: 600; margin: 0 0 16px; }
h1 { color: var(--fw-text); font-size: clamp(36px, 6vw, 64px); line-height: 1.12; letter-spacing: -0.035em; margin: 0; }
.summary { color: var(--fw-text-secondary); font-size: 18px; line-height: 30px; max-width: 680px; margin: 24px 0 0; }
.actions { align-items: center; display: flex; gap: 16px; margin-top: 32px; }
.primary-action { background: var(--fw-primary); border-radius: 8px; color: white; font-weight: 600; padding: 12px 20px; text-decoration: none; }
.primary-action:hover { background: var(--fw-primary-hover); }
.primary-action:focus-visible { outline: 3px solid #93c5fd; outline-offset: 3px; }
.service-state { color: var(--fw-text-secondary); font-size: 14px; }
.feature-grid { display: grid; gap: 16px; grid-template-columns: repeat(3, 1fr); margin-top: 80px; }
.feature-grid article { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; display: grid; gap: 8px; padding: 24px; }
.feature-grid span { color: var(--fw-text-secondary); }
@media (max-width: 767px) {
  .landing-shell { padding: 64px 16px 32px; }
  .actions { align-items: flex-start; flex-direction: column; }
  .feature-grid { grid-template-columns: 1fr; margin-top: 48px; }
}
</style>

