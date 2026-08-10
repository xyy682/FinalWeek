import { createRouter, createWebHistory } from 'vue-router'
import LandingPage from '@/views/LandingPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'landing', component: LandingPage },
    { path: '/login', name: 'login', component: () => import('@/views/PlaceholderPage.vue'), meta: { title: '邮箱登录' } },
    { path: '/courses', name: 'courses', component: () => import('@/views/PlaceholderPage.vue'), meta: { title: '课程列表' } },
    { path: '/courses/:id/materials', name: 'materials', component: () => import('@/views/PlaceholderPage.vue'), meta: { title: '课程资料' } },
    { path: '/courses/:id/outline', name: 'outline', component: () => import('@/views/PlaceholderPage.vue'), meta: { title: '知识提纲' } },
    { path: '/courses/:id/plan', name: 'plan', component: () => import('@/views/PlaceholderPage.vue'), meta: { title: '复习计划' } },
    { path: '/courses/:id/chat', name: 'chat', component: () => import('@/views/PlaceholderPage.vue'), meta: { title: '课程问答' } },
    { path: '/settings', name: 'settings', component: () => import('@/views/PlaceholderPage.vue'), meta: { title: '账号设置' } },
  ],
})

router.afterEach((to) => {
  document.title = typeof to.meta.title === 'string' ? `${to.meta.title} · FinalWeek` : 'FinalWeek'
})

export default router

