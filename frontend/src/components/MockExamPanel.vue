<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'
import { getOutline, type OutlineNode } from '@/api/outline'
import { createMockExam, deleteMockExam, getMockExam, getMockExamFile, listMockExams, mockExamDownloadUrl, retryMockExam,
  type MockExamInput, type MockExamSummary, type QuestionType, type ScoreMode } from '@/api/mockExams'
import type { TaskEvent } from '@/api/tasks'
import { cancelTask } from '@/api/tasks'
import { useTaskStore } from '@/stores/tasks'
import { getKnowledgeVersion } from '@/api/knowledgeVersions'

const props = defineProps<{ courseId: string }>()
const route = useRoute(); const router = useRouter(); const taskStore = useTaskStore()
const loading = ref(true); const submitting = ref(false); const errorMessage = ref('')
const cancelling = ref<string | null>(null)
const outlineNodes = ref<OutlineNode[]>([]); const tree = ref<{ getCheckedKeys: (leafOnly?: boolean) => unknown[]; setCheckedKeys: (keys: string[]) => void } | null>(null)
const history = ref<MockExamSummary[]>([]); const total = ref(0); const pageSize = 10
const retryOfId = ref<string | null>(null); const lastTaskId = ref<string | null>(null)
const hasUnconfirmedMaterials = ref(false)
const expandedDetails = reactive(new Set<string>())
const page = computed(() => Math.max(1, Number(route.query.examPage) || 1))
const activeTask = computed(() => taskStore.active.find(task => task.courseId === props.courseId && task.type === 'GENERATE_MOCK_EXAM') ?? null)
const types: { type: QuestionType; label: string; defaultScore: number }[] = [
  { type: 'SINGLE_CHOICE', label: '单选题', defaultScore: 2 }, { type: 'MULTIPLE_CHOICE', label: '多选题', defaultScore: 4 },
  { type: 'TRUE_FALSE', label: '判断题', defaultScore: 2 }, { type: 'FILL_BLANK', label: '填空题', defaultScore: 3 },
  { type: 'SHORT_ANSWER', label: '简答题', defaultScore: 8 }, { type: 'CALCULATION', label: '计算题', defaultScore: 10 },
  { type: 'ESSAY', label: '论述题', defaultScore: 15 },
]
const selections = reactive(Object.fromEntries(types.map(value => [value.type, { enabled: value.type === 'SINGLE_CHOICE', count: value.type === 'SINGLE_CHOICE' ? 10 : 0, score: value.defaultScore }])) as Record<QuestionType, { enabled: boolean; count: number; score: number }>)
const form = reactive({ displayName: '', scope: 'WHOLE_COURSE' as MockExamInput['scope'], scoreMode: 'AUTO' as ScoreMode,
  totalScore: null as number | null, durationMinutes: null as number | null, allowGeneralKnowledge: true, instructions: '' })
const questionCount = computed(() => types.reduce((sum, value) => sum + (selections[value.type].enabled ? selections[value.type].count || 0 : 0), 0))
const calculatedScore = computed(() => form.scoreMode === 'AUTO' && form.totalScore != null ? form.totalScore : types.reduce((sum, value) => {
  const selection = selections[value.type]; return sum + (selection.enabled ? (selection.count || 0) * (form.scoreMode === 'CUSTOM' ? selection.score || 0 : value.defaultScore) : 0)
}, 0))
const statusLabels: Record<string, string> = { PENDING_PUBLISH: '待投递', PUBLISH_FAILED: '投递失败', QUEUED: '排队中', PROCESSING: '生成中', RETRYING: '重试中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
const stageLabels: Record<string, string> = { REQUIREMENTS_ANALYZED: '已分析要求与资料', QUESTIONS_GENERATED: '已生成试题', PAPER_VALIDATED: '已校验试卷', PDFS_GENERATED: '已生成两个 PDF', COMPLETED: '已完成' }
const stageProgress: Record<string, number> = { REQUIREMENTS_ANALYZED: 20, QUESTIONS_GENERATED: 50, PAPER_VALIDATED: 70, PDFS_GENERATED: 90, COMPLETED: 100 }

async function load() {
  loading.value = true; errorMessage.value = ''
  try {
    const [outline, exams, knowledge] = await Promise.all([getOutline(props.courseId), listMockExams(props.courseId, page.value - 1, pageSize), getKnowledgeVersion(props.courseId)])
    outlineNodes.value = outline.outline?.nodes ?? []; history.value = exams.items; total.value = exams.totalElements
    hasUnconfirmedMaterials.value = knowledge.hasUnconfirmedSuccessfulMaterials
  } catch (error) { showError(error, '模拟卷页面加载失败') } finally { loading.value = false }
}
function toggleType(type: QuestionType, enabled: boolean) {
  const value = selections[type]; value.enabled = enabled
  if (!enabled) { value.count = 0; value.score = types.find(item => item.type === type)!.defaultScore }
  else if (!value.count) value.count = 1
}
function onTypeChange(type: QuestionType, value: string | number | boolean) {
  toggleType(type, Boolean(value))
}
function input(): MockExamInput {
  const questionCounts: Partial<Record<QuestionType, number>> = {}; const scorePerQuestion: Partial<Record<QuestionType, number>> = {}
  types.forEach(({ type }) => { const value = selections[type]; if (value.enabled) { questionCounts[type] = value.count; if (form.scoreMode === 'CUSTOM') scorePerQuestion[type] = value.score } })
  return { displayName: form.displayName || null, scope: form.scope,
    outlineNodeIds: form.scope === 'OUTLINE_NODES' ? tree.value?.getCheckedKeys(false).map(String) ?? [] : [],
    questionCounts, scoreMode: form.scoreMode, scorePerQuestion, totalScore: form.totalScore,
    durationMinutes: form.durationMinutes, allowGeneralKnowledge: form.allowGeneralKnowledge, instructions: form.instructions || null }
}
function validate(value: MockExamInput) {
  if (questionCount.value < 1 || questionCount.value > 50) return '总题数必须在 1–50 之间'
  if (form.scope === 'OUTLINE_NODES' && !value.outlineNodeIds.length) return '请至少选择一个知识点'
  if (calculatedScore.value < questionCount.value || calculatedScore.value > 1000) return '分值汇总必须允许每题至少 1 分且不超过 1000'
  if (form.scoreMode === 'CUSTOM' && form.totalScore != null && form.totalScore !== calculatedScore.value) return '总分必须与自定义分值汇总一致'
  return null
}
async function submit() {
  const value = input(); const invalid = validate(value); if (invalid) { ElMessage.warning(invalid); return }
  submitting.value = true; errorMessage.value = ''
  try {
    const key = crypto.randomUUID(); const result = retryOfId.value
      ? await retryMockExam(retryOfId.value, value, key) : await createMockExam(props.courseId, value, key)
    lastTaskId.value = result.task.id; retryOfId.value = null
    ElMessage.success('模拟卷任务已进入后台队列，可以离开页面'); await load()
  } catch (error) { showError(error, '模拟卷任务提交失败') } finally { submitting.value = false }
}
async function editAndRetry(item: MockExamSummary) {
  try {
    const detail = await getMockExam(item.id); const value = detail.request
    form.displayName = detail.summary.displayName; form.scope = value.scope; form.scoreMode = value.scoreMode
    form.totalScore = value.totalScore ?? null; form.durationMinutes = value.durationMinutes ?? null
    form.allowGeneralKnowledge = value.allowGeneralKnowledge; form.instructions = value.instructions ?? ''
    types.forEach(({ type, defaultScore }) => { const count = value.questionCounts[type] ?? 0
      Object.assign(selections[type], { enabled: count > 0, count, score: value.scorePerQuestion[type] ?? defaultScore }) })
    retryOfId.value = item.id; await nextTick(); tree.value?.setCheckedKeys(value.outlineNodeIds ?? [])
    window.scrollTo({ top: 0, behavior: 'smooth' }); ElMessage.info('已载入原参数，修改后将创建一条新的关联记录')
  } catch (error) { showError(error, '无法载入原模拟卷参数') }
}
async function remove(item: MockExamSummary) {
  try { await ElMessageBox.confirm(`删除“${item.displayName}”及其 PDF？`, '删除模拟卷', { type: 'warning' }); await deleteMockExam(item.id); await load() }
  catch (error) { if (error !== 'cancel' && error !== 'close') showError(error, '删除失败') }
}
async function cancel(item: MockExamSummary) {
  cancelling.value = item.id; errorMessage.value = ''
  try {
    await cancelTask(item.taskId); await taskStore.refresh(); await load(); ElMessage.success('已取消排队中的模拟卷任务')
  } catch (error) { showError(error, '任务已经开始处理，无法取消') }
  finally { cancelling.value = null }
}
async function file(item: MockExamSummary, kind: 'paper' | 'answer', operation: 'preview' | 'download') {
  if (operation === 'download') {
    const anchor = document.createElement('a'); anchor.href = mockExamDownloadUrl(item.id, kind)
    anchor.style.display = 'none'; document.body.appendChild(anchor); anchor.click(); anchor.remove(); return
  }
  const blank = window.open('about:blank', '_blank'); if (blank) blank.opener = null
  try { const result = await getMockExamFile(item.id, kind)
    if (blank) blank.location.href = result.url
    else window.open(result.url, '_blank', 'noopener')
  } catch (error) { blank?.close(); showError(error, '文件访问失败') }
}
function changePage(value: number) { void router.replace({ query: { ...route.query, examPage: value === 1 ? undefined : String(value) } }) }
function toggleDetails(id: string) { if (expandedDetails.has(id)) expandedDetails.delete(id); else expandedDetails.add(id) }
function onTaskUpdated(raw: Event) { const event = (raw as CustomEvent<TaskEvent>).detail
  if (event.courseId === props.courseId && event.type === 'GENERATE_MOCK_EXAM') void load() }
function showError(error: unknown, fallback: string) { errorMessage.value = error instanceof ApiError
  ? `${error.body.message}（${error.body.requestId}）` : fallback }
watch(page, load)
onMounted(() => { window.addEventListener('fw:task-updated', onTaskUpdated); void load() })
onBeforeUnmount(() => window.removeEventListener('fw:task-updated', onTaskUpdated))
</script>

<template>
  <section class="mock-panel" v-loading="loading">
    <header><div><h2>设计模拟卷</h2><p>基于提交时的课程知识版本，后台生成标准试卷和参考答案。</p></div></header>
    <el-alert v-if="!outlineNodes.length" title="尚无已发布知识提纲，请先在资料页确认资料并等待提纲完成。" type="info" show-icon :closable="false" />
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
    <el-alert v-if="hasUnconfirmedMaterials" title="有新增成功资料尚未确认；本次仍使用上一课程知识版本。" type="warning" show-icon :closable="false" />
    <el-alert v-if="activeTask" title="本课程已有模拟卷生成任务，完成前不能再次提交。" type="info" show-icon :closable="false" />
    <el-form class="exam-form" label-position="top" :disabled="!outlineNodes.length || !!activeTask" @submit.prevent>
      <el-form-item label="名称（可选）"><el-input v-model="form.displayName" maxlength="120" placeholder="留空时自动使用课程名和时间" /></el-form-item>
      <el-form-item label="出题范围"><el-radio-group v-model="form.scope"><el-radio value="WHOLE_COURSE">整门课程</el-radio><el-radio value="OUTLINE_NODES">选择知识点</el-radio></el-radio-group></el-form-item>
      <div v-if="form.scope === 'OUTLINE_NODES'" class="scope-tree"><el-tree ref="tree" :data="outlineNodes" node-key="id" :props="{ label: 'title', children: 'children' }" show-checkbox default-expand-all /></div>
      <el-form-item label="题型与题数" class="wide"><div class="type-grid"><article v-for="item in types" :key="item.type"><el-checkbox :model-value="selections[item.type].enabled" @change="onTypeChange(item.type, $event)">{{ item.label }}</el-checkbox><el-input-number v-if="selections[item.type].enabled" v-model="selections[item.type].count" :min="1" :max="50" controls-position="right" /></article></div><p class="hint">当前共 {{ questionCount }} 题（允许 1–50 题）</p></el-form-item>
      <el-form-item label="分值模式"><el-radio-group v-model="form.scoreMode"><el-radio value="AUTO">系统分配</el-radio><el-radio value="CUSTOM">自定义逐题型分值</el-radio></el-radio-group></el-form-item>
      <div v-if="form.scoreMode === 'CUSTOM'" class="score-grid wide"><label v-for="item in types.filter(value => selections[value.type].enabled)" :key="item.type"><span>{{ item.label }}单题分值</span><el-input-number v-model="selections[item.type].score" :min="1" :max="1000" /></label></div>
      <el-form-item label="总分（可选）"><el-input-number v-model="form.totalScore" :min="1" :max="1000" /><p class="hint">当前分值汇总：{{ calculatedScore }}</p></el-form-item>
      <el-form-item label="建议时长（可选）"><el-input-number v-model="form.durationMinutes" :min="1" :max="300" /><span class="suffix">分钟</span></el-form-item>
      <el-form-item class="wide"><el-switch v-model="form.allowGeneralKnowledge" /><span class="switch-label">允许必要时使用模型通用知识补题（不联网；参考答案会逐题标记）</span><p v-if="!form.allowGeneralKnowledge" class="hint warning">关闭后，课程资料不足以满足题型或题量时整套生成会失败，不会静默减题。</p></el-form-item>
      <el-form-item label="补充说明（可选）" class="wide"><el-input v-model="form.instructions" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="可说明难度、语言、风格、重点和知识点权重；结构化字段与能力边界优先。" /></el-form-item>
      <div class="actions wide"><el-button v-if="retryOfId" @click="retryOfId = null">取消重试</el-button><el-button type="primary" :loading="submitting" :disabled="!!activeTask" @click="submit">{{ retryOfId ? '创建关联重试记录' : '生成模拟卷' }}</el-button></div>
    </el-form>
    <section class="history"><h2>生成历史</h2><el-empty v-if="!history.length" description="还没有模拟卷记录" />
      <article v-for="item in history" :key="item.id" class="history-card"><div><strong>{{ item.displayName }}</strong><el-tag :type="item.status === 'SUCCEEDED' ? 'success' : item.status === 'FAILED' ? 'danger' : 'info'">{{ statusLabels[item.status] || item.status }}</el-tag></div><p>{{ new Date(item.createdAt).toLocaleString() }} · {{ item.questionCount }} 题 · {{ item.scoreSum }} 分<template v-if="item.currentStage"> · {{ stageLabels[item.currentStage] || item.currentStage }}</template></p><el-progress v-if="['PENDING_PUBLISH', 'PUBLISH_FAILED', 'QUEUED', 'PROCESSING', 'RETRYING'].includes(item.status)" :percentage="item.currentStage ? (stageProgress[item.currentStage] || 5) : 5" :show-text="false" /><template v-if="expandedDetails.has(item.id)"><el-alert v-if="item.errorCode" :title="`失败原因：${item.errorMessage || item.errorCode}`" type="error" :closable="false" /><ul v-if="item.warnings.length"><li v-for="warning in item.warnings" :key="warning">{{ warning }}</li></ul></template><div class="history-actions"><el-button v-if="item.errorCode || item.warnings.length" link @click="toggleDetails(item.id)">{{ expandedDetails.has(item.id) ? '收起详情' : '查看错误与警告' }}</el-button><template v-if="item.status === 'SUCCEEDED'"><el-button link type="primary" @click="file(item, 'paper', 'preview')">预览试卷</el-button><el-button link type="primary" @click="file(item, 'answer', 'preview')">预览答案</el-button><el-button link @click="file(item, 'paper', 'download')">下载试卷</el-button><el-button link @click="file(item, 'answer', 'download')">下载答案</el-button></template><el-button v-if="item.status === 'QUEUED'" link :loading="cancelling === item.id" @click="cancel(item)">取消</el-button><el-button v-if="['FAILED', 'CANCELLED'].includes(item.status)" link type="primary" @click="editAndRetry(item)">修改后重试</el-button><el-button v-if="['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(item.status)" link type="danger" @click="remove(item)">删除</el-button></div></article>
      <el-pagination v-if="total > pageSize" layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="total" @current-change="changePage" />
    </section>
  </section>
</template>

<style scoped>
.mock-panel{margin-top:24px}.mock-panel>header h2,.history h2{font-size:20px;margin:0 0 4px}.mock-panel>header p{color:var(--fw-text-secondary);margin:0 0 20px}.exam-form{background:var(--fw-surface);border:1px solid var(--fw-border);border-radius:12px;display:grid;gap:0 20px;grid-template-columns:1fr 1fr;margin-top:16px;padding:20px}.wide,.scope-tree{grid-column:1/-1}.scope-tree{border:1px solid var(--fw-border);border-radius:8px;margin-bottom:18px;max-height:280px;overflow:auto;padding:12px}.type-grid{display:grid;gap:10px;grid-template-columns:repeat(4,1fr);width:100%}.type-grid article{align-items:center;border:1px solid var(--fw-border);border-radius:8px;display:flex;justify-content:space-between;padding:10px}.type-grid :deep(.el-input-number){width:100px}.hint{color:var(--fw-text-secondary);font-size:13px;margin:6px 0 0}.warning{color:var(--fw-warning)}.score-grid{display:grid;gap:12px;grid-template-columns:repeat(3,1fr);margin-bottom:18px}.score-grid label{align-items:center;display:flex;justify-content:space-between}.switch-label,.suffix{margin-left:8px}.actions{display:flex;justify-content:flex-end}.history{margin-top:28px}.history-card{background:var(--fw-surface);border:1px solid var(--fw-border);border-radius:12px;margin:12px 0;padding:16px}.history-card>div:first-child{align-items:center;display:flex;justify-content:space-between}.history-card p,.history-card li{color:var(--fw-text-secondary)}.history-actions{display:flex;flex-wrap:wrap;justify-content:flex-end}@media(max-width:767px){.exam-form{grid-template-columns:1fr}.wide,.scope-tree{grid-column:auto}.type-grid,.score-grid{grid-template-columns:1fr}.history-card>div:first-child{align-items:flex-start;gap:8px}.history-actions{justify-content:flex-start}}
</style>
