<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { getOutline, updateOutlineImportance, type Outline, type OutlineImportance, type OutlineNode, type OutlineSource } from '@/api/outline'
import { getMaterialPreview, getSourceSegment, listMaterials, type Material, type MaterialPreview, type SourceSegment } from '@/api/materials'
import { getTask, republishTask, type TaskEvent, type TaskProgress } from '@/api/tasks'

const props = defineProps<{ courseId: string }>()
const outline = ref<Outline | null>(null)
const activeTask = ref<TaskProgress | null>(null)
const materials = ref<Material[]>([])
const loading = ref(true)
const connection = ref<'connected' | 'reconnecting'>('reconnecting')
const drawerOpen = ref(false)
const selectedSegment = ref<SourceSegment | null>(null)
const selectedPreview = ref<MaterialPreview | null>(null)
const sourceLoading = ref(false)
const media = ref<HTMLMediaElement | null>(null)
let events: EventSource | null = null
let pollTimer: number | null = null

const importanceLabels: Record<OutlineImportance, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const stageLabels: Record<string, string> = {
  CONTEXT_RETRIEVED: '已检索课程上下文', OUTLINE_GENERATED: '已生成并校验提纲', COMPLETED: '提纲生成完成',
}
const defaultExpanded = computed(() => outline.value?.nodes.flatMap(node => [node.id, ...node.children.map(child => child.id)]) ?? [])
const selectedMaterial = computed(() => materials.value.find(item => item.id === selectedSegment.value?.materialId) ?? null)

function describe(error: unknown) {
  return error instanceof ApiError ? `${error.body.message}（${error.body.code} · ${error.body.requestId}）` : '操作失败，请稍后重试'
}
function taskProgress(task: TaskProgress | null) {
  if (!task) return 0
  if (task.currentStage === 'CONTEXT_RETRIEVED') return 35
  if (task.currentStage === 'OUTLINE_GENERATED') return 80
  if (task.currentStage === 'COMPLETED') return 100
  return 5
}
function sourceLabel(source: OutlineSource | SourceSegment) {
  if (source.pageNumber) return `第 ${source.pageNumber} 页`
  if (source.slideNumber) return `第 ${source.slideNumber} 张幻灯片`
  if (source.paragraphNumber) return `第 ${source.paragraphNumber} 段`
  if (source.startTimeMs != null) return `${Math.floor(source.startTimeMs / 60000)}:${String(Math.floor(source.startTimeMs / 1000) % 60).padStart(2, '0')}`
  return '原文位置'
}

async function load() {
  loading.value = true
  try {
    const [page, materialList] = await Promise.all([getOutline(props.courseId), listMaterials(props.courseId)])
    outline.value = page.outline; activeTask.value = page.activeTask; materials.value = materialList
    if (activeTask.value) startPolling()
  } catch (error) { ElMessage.error(describe(error)) }
  finally { loading.value = false }
}
async function republish() {
  if (!activeTask.value) return
  try { activeTask.value = await republishTask(activeTask.value.id); startPolling(); ElMessage.success('提纲任务已重新投递') }
  catch (error) { ElMessage.error(describe(error)) }
}
async function updateImportance(node: OutlineNode, value: OutlineImportance) {
  const previous = node.importance
  node.importance = value
  try {
    const saved = await updateOutlineImportance(node.id, value)
    node.importanceManuallyAdjusted = saved.importanceManuallyAdjusted
    ElMessage.success('重要度已保存')
  } catch (error) { node.importance = previous; ElMessage.error(describe(error)) }
}
async function openSource(source: OutlineSource) {
  drawerOpen.value = true; sourceLoading.value = true; selectedPreview.value = null
  try {
    selectedSegment.value = await getSourceSegment(source.segmentId)
    selectedPreview.value = await getMaterialPreview(selectedSegment.value.materialId)
    await nextTick()
    if (media.value && selectedSegment.value.startTimeMs != null) media.value.currentTime = selectedSegment.value.startTimeMs / 1000
  } catch (error) { ElMessage.error(describe(error)) }
  finally { sourceLoading.value = false }
}
function connect() {
  events = new EventSource('/api/v1/tasks/events', { withCredentials: true })
  events.onopen = () => { connection.value = 'connected' }
  events.onerror = () => { connection.value = 'reconnecting' }
  events.addEventListener('task-progress', (event) => {
    const value = JSON.parse((event as MessageEvent).data) as TaskEvent
    if (!activeTask.value || value.taskId !== activeTask.value.id) return
    activeTask.value.status = value.status; activeTask.value.currentStage = value.stage
    activeTask.value.errorMessage = value.message
    if (value.status === 'SUCCEEDED') void load()
  })
}
function startPolling() {
  if (pollTimer != null) window.clearInterval(pollTimer)
  pollTimer = window.setInterval(async () => {
    if (!activeTask.value) return
    try {
      const task = await getTask(activeTask.value.id); activeTask.value = task
      if (task.status === 'SUCCEEDED') { window.clearInterval(pollTimer!); pollTimer = null; await load() }
      if (task.status === 'FAILED') { window.clearInterval(pollTimer!); pollTimer = null }
    } catch { /* SSE or manual refresh remains available. */ }
  }, 2000)
}
onMounted(() => { void load(); connect() })
onBeforeUnmount(() => { events?.close(); if (pollTimer != null) window.clearInterval(pollTimer) })
</script>

<template>
  <section class="outline-panel" aria-labelledby="outline-title" v-loading="loading">
    <header>
      <div><h2 id="outline-title">知识提纲</h2><p>按课程资料生成，所有知识点都可回到原文核验。</p></div>
    </header>

    <article v-if="activeTask" class="task-card" aria-live="polite">
      <div><strong>提纲生成任务</strong><span>{{ stageLabels[activeTask.currentStage ?? ''] ?? '排队中' }} · {{ connection === 'connected' ? '实时连接' : '正在重新连接' }}</span></div>
      <el-progress :percentage="taskProgress(activeTask)" />
      <p v-if="activeTask.errorMessage" class="error">{{ activeTask.errorMessage }}</p>
      <el-button v-if="activeTask.status === 'PUBLISH_FAILED'" type="primary" link @click="republish">重新投递</el-button>
    </article>

    <el-empty v-if="!outline && !activeTask" description="请先在资料页确认“资料已上传完毕”，系统会自动生成带来源的知识提纲">
      <RouterLink :to="`/courses/${courseId}/materials`"><el-button type="primary">前往确认资料</el-button></RouterLink>
    </el-empty>
    <template v-else-if="outline">
      <p class="version">第 {{ outline.generationVersion }} 版 · 更新于 {{ new Date(outline.updatedAt).toLocaleString() }}</p>
      <el-tree :data="outline.nodes" node-key="id" :props="{ label: 'title', children: 'children' }" :default-expanded-keys="defaultExpanded">
        <template #default="{ data }">
          <div class="tree-node">
            <span class="node-title">{{ (data as OutlineNode).title }}</span>
            <el-select :model-value="(data as OutlineNode).importance" class="importance" aria-label="重要度" @change="updateImportance(data as OutlineNode, $event as OutlineImportance)">
              <el-option v-for="(label, value) in importanceLabels" :key="value" :label="`重要度：${label}`" :value="value" />
            </el-select>
            <span v-if="(data as OutlineNode).importanceManuallyAdjusted" class="manual">已人工调整</span>
            <el-button v-for="source in (data as OutlineNode).sources" :key="source.segmentId" link type="primary" @click.stop="openSource(source)">
              来源 · {{ sourceLabel(source) }}
            </el-button>
          </div>
        </template>
      </el-tree>
    </template>
  </section>

  <el-drawer v-model="drawerOpen" title="资料来源" size="min(560px, 92vw)">
    <div v-loading="sourceLoading" class="source-drawer">
      <template v-if="selectedSegment">
        <h3>{{ selectedMaterial?.originalFilename ?? '课程资料' }}</h3>
        <p class="location">{{ sourceLabel(selectedSegment) }}</p>
        <blockquote>{{ selectedSegment.content }}</blockquote>
        <iframe v-if="selectedPreview?.normalizedPdf" :src="`${selectedPreview.url}#page=${selectedSegment.pageNumber ?? selectedSegment.slideNumber ?? 1}`" title="来源 PDF 预览" />
        <audio v-else-if="selectedPreview?.mediaType === 'audio/mpeg'" ref="media" controls :src="selectedPreview.url" />
        <video v-else-if="selectedPreview?.mediaType === 'video/mp4'" ref="media" controls :src="selectedPreview.url" />
        <el-button v-else-if="selectedPreview" tag="a" :href="selectedPreview.url" target="_blank" rel="noopener noreferrer">打开原资料</el-button>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.outline-panel{background:var(--fw-surface);border:1px solid var(--fw-border);border-radius:12px;margin-top:24px;padding:24px}.outline-panel>header{align-items:flex-start;display:flex;gap:24px;justify-content:space-between}.outline-panel h2{font-size:20px;line-height:28px;margin:0 0 4px}.outline-panel header p,.version,.task-card span,.location{color:var(--fw-text-secondary);margin:0}.task-card{background:var(--fw-background);border:1px solid var(--fw-border);border-radius:8px;display:grid;gap:10px;margin:20px 0;padding:16px}.task-card>div{display:flex;justify-content:space-between}.error{color:var(--fw-danger);margin:0}.version{margin:20px 0 12px}.tree-node{align-items:center;display:flex;gap:8px;min-height:44px;width:100%}.node-title{font-weight:600;min-width:180px}.importance{width:116px}.manual{color:var(--fw-text-secondary);font-size:12px}.source-drawer{display:grid;gap:16px}.source-drawer h3{margin:0}.source-drawer blockquote{background:var(--fw-background);border-left:3px solid var(--fw-primary);margin:0;padding:16px;white-space:pre-wrap}.source-drawer iframe{border:1px solid var(--fw-border);height:58vh;width:100%}.source-drawer audio,.source-drawer video{max-height:58vh;width:100%}@media(max-width:767px){.outline-panel{padding:20px}.outline-panel>header{align-items:stretch;flex-direction:column}.tree-node{align-items:flex-start;flex-wrap:wrap;height:auto;padding:6px 0}.node-title{flex-basis:100%;min-width:0}.task-card>div{flex-direction:column;gap:4px}}
</style>
