import { createRouter, createWebHistory } from 'vue-router'
import LandingPage from '@/views/LandingPage.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'landing', component: LandingPage, meta: { public: true } },
    { path: '/login', name: 'login', component: () => import('@/views/LoginPage.vue'), meta: { title: '邮箱登录', public: true } },
    { path: '/courses', name: 'courses', component: () => import('@/views/CourseListPage.vue'), meta: { title: '课程列表' } },
    { path: '/courses/:id/materials', name: 'materials', component: () => import('@/views/CourseSectionPage.vue'), meta: { title: '课程资料', section: 'materials' } },
    { path: '/courses/:id/outline', name: 'outline', component: () => import('@/views/CourseSectionPage.vue'), meta: { title: '知识提纲', section: 'outline' } },
    { path: '/courses/:id/plan', name: 'plan', component: () => import('@/views/CourseSectionPage.vue'), meta: { title: '复习计划', section: 'plan' } },
    { path: '/courses/:id/chat', name: 'chat', component: () => import('@/views/CourseSectionPage.vue'), meta: { title: '课程问答', section: 'chat' } },
    { path: '/courses/:id/mock-exams', name: 'mock-exams', component: () => import('@/views/CourseSectionPage.vue'), meta: { title: '模拟卷', section: 'mock-exams' } },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsPage.vue'), meta: { title: '账号设置' } },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const authenticated = await useAuthStore().load()
  return authenticated ? true : { name: 'login', query: { redirect: to.fullPath } }
})

router.afterEach((to) => {
  document.title = typeof to.meta.title === 'string' ? `${to.meta.title} · FinalWeek` : 'FinalWeek'
})

export default router
