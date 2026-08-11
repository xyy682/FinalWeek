<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'
import { getOutline } from '@/api/outline'
import { generatePlan, getPlan, setPlanTaskCompleted, type MasteryLevel, type PlanInput, type StudyPlan } from '@/api/plan'

const props = defineProps<{ courseId: string }>()
const plan = ref<StudyPlan | null>(null)
const hasOutline = ref(true)
const loading = ref(false)
const generating = ref(false)
const retryKey = ref<string | null>(null)
const retryBaseVersion = ref<number | null>(null)
const errorMessage = ref('')
const tomorrow = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
const form = reactive<PlanInput>({ examDate: tomorrow, dailyMinutes: 60, masteryLevel: 'MEDIUM', targetScore: 85 })
const masteryLabels: Record<MasteryLevel, string> = { LOW: '掌握较少', MEDIUM: '掌握一般', HIGH: '掌握较好' }
const groups = computed(() => {
  const result = new Map<string, StudyPlan['tasks']>()
  for (const task of plan.value?.tasks ?? []) {
    const values = result.get(task.plannedDate) ?? []
    values.push(task); result.set(task.plannedDate, values)
  }
  return [...result.entries()]
})

async function load() {
  loading.value = true; errorMessage.value = ''
  try {
    const [current, outlinePage] = await Promise.all([getPlan(props.courseId), getOutline(props.courseId)])
    plan.value = current; hasOutline.value = !!outlinePage.outline
    if (current) Object.assign(form, { examDate: current.examDate, dailyMinutes: current.dailyMinutes,
      masteryLevel: current.masteryLevel, targetScore: current.targetScore })
  } catch (error) { showError(error, '计划加载失败') }
  finally { loading.value = false }
}

async function confirmGenerate() {
  if (!hasOutline.value) { ElMessage.warning('请先到“知识提纲”生成提纲'); return }
  if (plan.value) {
    try { await ElMessageBox.confirm('重新生成将覆盖当前计划和完成状态，是否继续？', '覆盖当前计划', { type: 'warning' }) }
    catch { return }
  }
  retryKey.value = crypto.randomUUID(); retryBaseVersion.value = plan.value?.version ?? null
  await submit(retryKey.value)
}

async function retrySameRequest() {
  if (!retryKey.value) return
  generating.value = true; errorMessage.value = ''
  try {
    const current = await getPlan(props.courseId)
    if ((current?.version ?? null) !== retryBaseVersion.value) {
      plan.value = current; retryKey.value = null; retryBaseVersion.value = null
      ElMessage.success('已获取到刚才生成的计划'); return
    }
  } catch (error) { showError(error, '刷新当前计划失败'); generating.value = false; return }
  generating.value = false
  await submit(retryKey.value)
}

async function submit(key: string) {
  generating.value = true; errorMessage.value = ''
  try {
    const result = await generatePlan(props.courseId, { ...form }, key)
    plan.value = result.plan; retryKey.value = null; retryBaseVersion.value = null
    ElMessage.success(result.idempotentReplay ? '已恢复此前生成结果' : '复习计划已生成')
  } catch (error) { showError(error, '计划生成失败'); retryKey.value = key }
  finally { generating.value = false }
}

async function toggle(task: StudyPlan['tasks'][number], value: boolean) {
  const previous = task.completed; task.completed = value
  try { Object.assign(task, await setPlanTaskCompleted(task.id, value)) }
  catch (error) { task.completed = previous; showError(error, '完成状态保存失败') }
}
function showError(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    errorMessage.value = error.status === 429 ? '生成过于频繁，请稍后使用同一请求重试' : `${error.body.message}（${error.body.requestId}）`
  } else errorMessage.value = fallback
}
onMounted(load)
</script>

<template>
  <section class="plan-panel" v-loading="loading">
    <div class="section-heading"><div><h2>复习计划</h2><p>根据当前提纲安排考试前的每日知识点任务</p></div></div>
    <el-alert v-if="!hasOutline" title="还没有知识提纲，请先生成提纲后再制定计划。" type="info" show-icon :closable="false" />
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
    <el-form class="plan-form" label-position="top" :model="form">
      <el-form-item label="考试日期"><el-date-picker v-model="form.examDate" type="date" value-format="YYYY-MM-DD" :disabled-date="(date: Date) => date.getTime() < Date.now()" /></el-form-item>
      <el-form-item label="每日可用分钟数"><el-input-number v-model="form.dailyMinutes" :min="1" :max="1440" /></el-form-item>
      <el-form-item label="整体掌握程度"><el-select v-model="form.masteryLevel"><el-option v-for="(label, value) in masteryLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
      <el-form-item label="目标成绩"><el-input-number v-model="form.targetScore" :min="1" :max="100" /></el-form-item>
      <div class="actions"><el-button type="primary" :loading="generating" :disabled="!hasOutline" @click="confirmGenerate">{{ plan ? '重新生成' : '生成计划' }}</el-button><el-button v-if="retryKey" :loading="generating" @click="retrySameRequest">刷新并重试原请求</el-button></div>
    </el-form>
    <div v-if="plan" class="summary">版本 {{ plan.version }} · 考试 {{ plan.examDate }} · 每日 {{ plan.dailyMinutes }} 分钟 · 目标 {{ plan.targetScore }} 分</div>
    <div v-if="plan" class="days">
      <article v-for="[date, dayTasks] in groups" :key="date" class="day-card"><h3>{{ date }}</h3>
        <label v-for="task in dayTasks" :key="task.id" class="plan-task" :class="{ done: task.completed }">
          <el-checkbox :model-value="task.completed" @change="(value: string | number | boolean) => toggle(task, Boolean(value))" />
          <span class="task-title">{{ task.knowledgeTitle }}</span><span>{{ task.estimatedMinutes }} 分钟</span>
        </label>
      </article>
    </div>
    <el-empty v-else-if="hasOutline && !loading" description="填写参数后生成简单每日计划" />
  </section>
</template>

<style scoped>
.plan-panel { margin-top: 24px; }.section-heading h2 { font-size: 20px; margin: 0 0 4px; }.section-heading p { color: var(--fw-text-secondary); margin: 0 0 20px; }
.plan-form { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; display: grid; gap: 0 20px; grid-template-columns: repeat(4, minmax(0, 1fr)); padding: 20px; }
.plan-form :deep(.el-date-editor), .plan-form :deep(.el-select), .plan-form :deep(.el-input-number) { width: 100%; }.actions { align-items: center; display: flex; grid-column: 1 / -1; gap: 8px; }
.summary { color: var(--fw-text-secondary); margin: 20px 0 12px; }.days { display: grid; gap: 12px; }.day-card { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; padding: 16px; }.day-card h3 { font-size: 16px; margin: 0 0 10px; }
.plan-task { align-items: center; border-top: 1px solid var(--fw-border); display: grid; gap: 10px; grid-template-columns: auto 1fr auto; min-height: 44px; }.task-title { font-weight: 500; }.plan-task.done .task-title { color: var(--fw-text-secondary); text-decoration: line-through; }
@media (max-width: 767px) { .plan-form { grid-template-columns: 1fr; }.actions { grid-column: auto; flex-direction: column; align-items: stretch; }.plan-task { grid-template-columns: auto 1fr; }.plan-task > span:last-child { grid-column: 2; color: var(--fw-text-secondary); } }
</style>
