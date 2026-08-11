<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import { getCourse, type Course } from '@/api/courses'
import { ApiError } from '@/api/http'
import MaterialUploadPanel from '@/components/MaterialUploadPanel.vue'
import OutlineTree from '@/components/OutlineTree.vue'
import StudyPlanPanel from '@/components/StudyPlanPanel.vue'

const route = useRoute()
const course = ref<Course | null>(null)
const errorMessage = ref('')
const section = computed(() => String(route.meta.section ?? 'materials'))
const labels: Record<string, string> = { materials: '资料', outline: '知识提纲', plan: '复习计划', chat: '课程问答' }

async function load() {
  try { course.value = await getCourse(String(route.params.id)) }
  catch (error) { errorMessage.value = error instanceof ApiError ? `${error.body.message}（${error.body.requestId}）` : '课程加载失败' }
}
onMounted(load)
watch(() => route.params.id, load)
</script>

<template>
  <AppHeader />
  <main class="page-shell">
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
    <template v-else-if="course">
      <RouterLink class="back" to="/courses">← 返回课程</RouterLink>
      <h1>{{ course.name }}</h1>
      <nav class="course-tabs" aria-label="课程区域">
        <RouterLink v-for="(label, key) in labels" :key="key" :to="`/courses/${course.id}/${key}`">{{ label }}</RouterLink>
      </nav>
      <MaterialUploadPanel v-if="section === 'materials'" :course-id="course.id" />
      <OutlineTree v-else-if="section === 'outline'" :course-id="course.id" />
      <StudyPlanPanel v-else-if="section === 'plan'" :course-id="course.id" />
      <section v-else class="phase-placeholder"><h2>{{ labels[section] }}</h2><p>课程框架已就绪，此区域将在后续实施阶段按计划接入完整功能。</p></section>
    </template>
  </main>
</template>

<style scoped>
.page-shell { margin: 0 auto; max-width: 1280px; padding: 48px 32px; }
.back { color: var(--fw-primary); text-decoration: none; }
h1 { font-size: 28px; line-height: 36px; margin: 16px 0 20px; }
.course-tabs { border-bottom: 1px solid var(--fw-border); display: flex; gap: 24px; overflow-x: auto; }
.course-tabs a { color: var(--fw-text-secondary); flex: none; padding: 10px 0; text-decoration: none; }
.course-tabs a.router-link-active { border-bottom: 2px solid var(--fw-primary); color: var(--fw-primary); font-weight: 600; }
.phase-placeholder { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; margin-top: 24px; padding: 32px; }
.phase-placeholder h2 { font-size: 20px; margin: 0 0 8px; }.phase-placeholder p { color: var(--fw-text-secondary); margin: 0; }
@media (max-width: 767px) { .page-shell { padding: 32px 16px; } }
</style>
