<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { cancelTask, republishTask, retryTask, type TaskProgress } from '@/api/tasks'
import { useAuthStore } from '@/stores/auth'
import { useTaskStore } from '@/stores/tasks'

const auth = useAuthStore(); const tasks = useTaskStore(); const router = useRouter()
const drawer = ref(false); const operating = ref<string | null>(null)
const labels: Record<TaskProgress['type'], string> = {
  PARSE_MATERIAL: '解析资料', GENERATE_OUTLINE: '生成提纲', GENERATE_PLAN: '生成计划',
  GENERATE_MOCK_EXAM: '生成模拟卷', ANSWER_CHAT: '生成回答',
}
const statusLabels: Record<string, string> = { PENDING_PUBLISH: '待投递', PUBLISH_FAILED: '投递失败', QUEUED: '排队中', PROCESSING: '处理中', RETRYING: '重试中' }
const stageLabels: Record<string, string> = { UPLOADED: '已上传', CONTENT_EXTRACTED: '已提取内容', CHUNKED: '已切片', EMBEDDING_COMPLETED: '已建立索引', CONTEXT_RETRIEVED: '已检索上下文', OUTLINE_GENERATED: '已生成提纲', PLAN_CONTEXT_RETRIEVED: '已分析计划上下文', PLAN_GENERATED: '已生成计划', REQUIREMENTS_ANALYZED: '已分析要求与资料', QUESTIONS_GENERATED: '已生成试题', PAPER_VALIDATED: '已校验试卷', PDFS_GENERATED: '已生成两个 PDF' }
const progress: Record<string, number> = { UPLOADED: 5, CONTENT_EXTRACTED: 35, CHUNKED: 60, EMBEDDING_COMPLETED: 90, CONTEXT_RETRIEVED: 35, OUTLINE_GENERATED: 80, PLAN_CONTEXT_RETRIEVED: 35, PLAN_GENERATED: 80, REQUIREMENTS_ANALYZED: 20, QUESTIONS_GENERATED: 50, PAPER_VALIDATED: 70, PDFS_GENERATED: 90 }
async function retry(task: TaskProgress) {
  operating.value = task.id
  try {
    const republishing = task.status === 'PUBLISH_FAILED'
    const updated = republishing ? await republishTask(task.id) : await retryTask(task.id)
    Object.assign(task, updated); ElMessage.success(republishing ? '任务已重新投递' : '任务已重新排队')
  }
  catch { ElMessage.error('任务重试失败') } finally { operating.value = null }
}
async function cancel(task: TaskProgress) {
  operating.value = task.id
  try { await cancelTask(task.id); ElMessage.success('已取消排队任务') }
  catch { ElMessage.error('当前任务不能取消') } finally { operating.value = null }
}
function jump(task: TaskProgress) { drawer.value = false; void router.push(task.jumpTarget) }
onMounted(() => void tasks.connect())
</script>

<template>
  <header class="app-header">
    <RouterLink class="brand" to="/courses">FinalWeek</RouterLink>
    <nav aria-label="全局导航">
      <el-badge :value="tasks.visible.length" :hidden="!tasks.visible.length">
        <el-button text @click="drawer = true">后台任务</el-button>
      </el-badge>
      <span v-if="!tasks.connected" class="reconnect">正在重新连接</span>
      <RouterLink to="/courses">课程</RouterLink><RouterLink to="/settings">设置</RouterLink>
      <span v-if="auth.user">{{ auth.user.email }}</span>
    </nav>
  </header>
  <el-drawer v-model="drawer" title="后台任务" size="min(440px, 92vw)">
    <el-empty v-if="!tasks.visible.length" description="当前没有进行中的任务" />
    <article v-for="task in tasks.visible" :key="task.id" class="task-card">
      <div><strong>{{ labels[task.type] }}</strong><span>{{ task.courseName || '课程' }}</span></div>
      <p>{{ statusLabels[task.status] || task.status }}<template v-if="task.currentStage"> · {{ stageLabels[task.currentStage] || task.currentStage }}</template></p>
      <el-progress :percentage="task.currentStage ? (progress[task.currentStage] || 5) : 5" :show-text="false" />
      <div class="task-actions">
        <el-button link type="primary" @click="jump(task)">查看</el-button>
        <el-button v-if="task.status === 'QUEUED'" link :loading="operating === task.id" @click="cancel(task)">取消</el-button>
        <el-button v-if="task.status === 'FAILED' || task.status === 'PUBLISH_FAILED'" link type="primary" :loading="operating === task.id" @click="retry(task)">{{ task.status === 'PUBLISH_FAILED' ? '重新投递' : '重试' }}</el-button>
      </div>
    </article>
  </el-drawer>
</template>

<style scoped>
.app-header{align-items:center;background:var(--fw-surface);border-bottom:1px solid var(--fw-border);display:flex;justify-content:space-between;min-height:64px;padding:0 max(16px,calc((100vw - 1280px)/2))}.brand{color:var(--fw-text);font-size:18px;font-weight:700;text-decoration:none}nav{align-items:center;display:flex;gap:20px}nav a{color:var(--fw-text-secondary);text-decoration:none}nav a.router-link-active{color:var(--fw-primary)}nav span{color:var(--fw-text-secondary);font-size:13px}.reconnect{color:var(--fw-warning)}.task-card{border:1px solid var(--fw-border);border-radius:10px;margin-bottom:12px;padding:14px}.task-card>div:first-child{display:flex;justify-content:space-between}.task-card span,.task-card p{color:var(--fw-text-secondary);font-size:13px}.task-actions{display:flex;justify-content:flex-end}@media(max-width:767px){nav>span:not(.reconnect){display:none}nav{gap:8px}}
</style>
