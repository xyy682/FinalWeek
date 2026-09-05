<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'
import { completeUpload, deleteMaterial, getMaterialPreview, getUploadStatus, initializeUpload, listMaterials, putChunk, type Material, type MaterialStatus, type MaterialType, type UploadSession } from '@/api/materials'
import { missingChunkIndexes } from '@/upload/resume'
import { cancelTask, republishTask, retryMaterial, type TaskEvent } from '@/api/tasks'
import { confirmKnowledgeVersion, getKnowledgeVersion, type KnowledgeVersionState } from '@/api/knowledgeVersions'
import { retryTask } from '@/api/tasks'

const props = defineProps<{ courseId: string }>()
const materials = ref<Material[]>([])
type UploadItemStatus = 'READY' | 'UPLOADING' | 'COMPLETED' | 'FAILED'
interface UploadItem {
  key: string
  file: File
  status: UploadItemStatus
  session: UploadSession | null
  uploadedCount: number
  error: string
  duplicate: boolean
}

const uploadItems = ref<UploadItem[]>([])
const materialType = ref<MaterialType>('COURSEWARE')
const focusNotes = ref('')
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const dragging = ref(false)
const progressConnection = ref<'connected' | 'reconnecting'>('reconnecting')
const taskEvents = ref<Record<string, TaskEvent>>({})
const knowledgeState = ref<KnowledgeVersionState | null>(null)
const confirmingMaterials = ref(false)
let progressEvents: EventSource | null = null

const typeOptions: Array<{ value: MaterialType; label: string }> = [
  { value: 'COURSEWARE', label: '课件' }, { value: 'NOTES', label: '笔记' },
  { value: 'PAST_EXAM', label: '真题' }, { value: 'QUESTION_BANK', label: '题库' },
  { value: 'RECORDING', label: '录音' }, { value: 'VIDEO', label: '视频' }, { value: 'OTHER', label: '其他' },
]
const statusLabels: Record<MaterialStatus, string> = {
  PENDING_PUBLISH: '待投递', PUBLISH_FAILED: '投递失败', QUEUED: '排队中', PROCESSING: '解析中',
  RETRYING: '重试中', SUCCEEDED: '解析成功', FAILED: '解析失败', CANCELLED: '已取消',
}
const deletable = new Set<MaterialStatus>(['PENDING_PUBLISH', 'PUBLISH_FAILED', 'FAILED', 'CANCELLED'])
const activeUploadCount = computed(() => uploadItems.value.filter(item => item.status === 'UPLOADING').length)
const failedUploadCount = computed(() => uploadItems.value.filter(item => item.status === 'FAILED').length)
const completedUploadCount = computed(() => uploadItems.value.filter(item => item.status === 'COMPLETED').length)
const batchPercent = computed(() => {
  if (!uploadItems.value.length) return 0
  const total = uploadItems.value.reduce((sum, item) => sum + itemPercent(item), 0)
  return Math.round(total / uploadItems.value.length)
})
const unfinishedCount = computed(() => materials.value.filter(item =>
  ['PENDING_PUBLISH', 'QUEUED', 'PROCESSING', 'RETRYING'].includes(item.status)).length + activeUploadCount.value)
const successfulCount = computed(() => materials.value.filter(item => item.status === 'SUCCEEDED').length)
const ignoredMaterials = computed(() => materials.value.filter(item =>
  ['PUBLISH_FAILED', 'FAILED', 'CANCELLED'].includes(item.status)).map(item => item.originalFilename))
const canConfirmMaterials = computed(() => unfinishedCount.value === 0 && successfulCount.value > 0
  && (knowledgeState.value?.hasUnconfirmedSuccessfulMaterials ?? true) && !knowledgeState.value?.activeOutlineTask)

function fingerprint(file: File) { return `fw-upload:${props.courseId}:${file.name}:${file.size}:${file.lastModified}` }
function formatSize(bytes: number) {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${Math.ceil(bytes / 1024)} KB`
}
function describe(error: unknown) {
  return error instanceof ApiError ? `${error.body.message}（${error.body.code} · ${error.body.requestId}）` : '上传失败，请检查网络后继续'
}
function itemPercent(item: UploadItem) {
  if (item.status === 'COMPLETED') return 100
  if (!item.session?.totalChunks) return 0
  return Math.round(item.uploadedCount / item.session.totalChunks * 100)
}
function chooseFiles(files?: FileList | File[]) {
  if (!files?.length) return
  const selected = Array.from(files)
  if (selected.length > 50) ElMessage.warning('单批最多选择 50 个文件，已保留前 50 个')
  const existing = new Set(uploadItems.value.map(item => item.key))
  for (const file of selected.slice(0, 50)) {
    const key = fingerprint(file)
    if (!existing.has(key)) {
      uploadItems.value.push({ key, file, status: 'READY', session: null, uploadedCount: 0, error: '', duplicate: false })
      existing.add(key)
    }
  }
  if (fileInput.value) fileInput.value.value = ''
}
function drop(event: DragEvent) { dragging.value = false; chooseFiles(event.dataTransfer?.files) }
function removeUploadItem(item: UploadItem) {
  if (item.status !== 'UPLOADING') uploadItems.value = uploadItems.value.filter(value => value.key !== item.key)
}
function clearFinishedUploads() {
  uploadItems.value = uploadItems.value.filter(item => !['COMPLETED'].includes(item.status))
}

async function loadMaterials() {
  try {
    const [list, version] = await Promise.all([listMaterials(props.courseId), getKnowledgeVersion(props.courseId)])
    materials.value = list; knowledgeState.value = version
  }
  catch (error) { ElMessage.error(describe(error)) }
}

async function finalizeMaterials() {
  try {
    if (ignoredMaterials.value.length) await ElMessageBox.confirm(
      `以下资料不会进入本次知识版本：${ignoredMaterials.value.join('、')}。是否忽略并继续？`,
      '确认资料范围', { type: 'warning', confirmButtonText: '忽略并继续', cancelButtonText: '返回处理' },
    )
    confirmingMaterials.value = true
    const result = await confirmKnowledgeVersion(props.courseId, ignoredMaterials.value.length > 0)
    knowledgeState.value = { ...(knowledgeState.value as KnowledgeVersionState), activeOutlineTask: result.task,
      hasUnconfirmedSuccessfulMaterials: false }
    ElMessage.success('资料已确认，正在后台生成新版知识提纲')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(describe(error)) }
  finally { confirmingMaterials.value = false }
}

async function retryOutline() {
  const failed = knowledgeState.value?.recentFailedOutlineTask
  if (!failed) return
  try {
    const task = await retryTask(failed.id)
    if (knowledgeState.value) knowledgeState.value.activeOutlineTask = task
    ElMessage.success('已按原知识版本重试提纲生成')
  } catch (error) { ElMessage.error(describe(error)) }
}

async function resolveSession(item: UploadItem): Promise<{ session: UploadSession; uploaded: Set<number> } | null> {
  const file = item.file
  const savedId = localStorage.getItem(fingerprint(file))
  if (savedId) {
    try {
      const status = await getUploadStatus(savedId)
      if (status.status === 'COMPLETED') {
        localStorage.removeItem(fingerprint(file)); localStorage.removeItem(`${fingerprint(file)}:meta`)
        item.status = 'COMPLETED'; item.uploadedCount = item.session?.totalChunks ?? 1
        return null
      }
      const initialized = JSON.parse(localStorage.getItem(`${fingerprint(file)}:meta`) ?? 'null') as UploadSession | null
      if (initialized) return { session: initialized, uploaded: new Set(status.uploadedChunks) }
    } catch (error) {
      if (!(error instanceof ApiError) || ![404, 410].includes(error.status)) throw error
      localStorage.removeItem(fingerprint(file)); localStorage.removeItem(`${fingerprint(file)}:meta`)
    }
  }
  const session = await initializeUpload(props.courseId, file, materialType.value, focusNotes.value)
  localStorage.setItem(fingerprint(file), session.uploadId)
  localStorage.setItem(`${fingerprint(file)}:meta`, JSON.stringify(session))
  return { session, uploaded: new Set<number>() }
}

async function uploadOne(item: UploadItem) {
  item.status = 'UPLOADING'; item.error = ''
  try {
    const resolved = await resolveSession(item)
    if (!resolved) return
    item.session = resolved.session; item.uploadedCount = resolved.uploaded.size
    const missing = missingChunkIndexes(resolved.session.totalChunks, resolved.uploaded)
    for (const index of missing) {
      const start = index * resolved.session.chunkSize
      await putChunk(resolved.session.uploadId, index,
        item.file.slice(start, Math.min(item.file.size, start + resolved.session.chunkSize)))
      item.uploadedCount++
    }
    const result = await completeUpload(resolved.session.uploadId)
    localStorage.removeItem(fingerprint(item.file)); localStorage.removeItem(`${fingerprint(item.file)}:meta`)
    item.duplicate = result.duplicate; item.status = 'COMPLETED'
  } catch (error) {
    item.status = 'FAILED'; item.error = describe(error)
  }
}

async function startUpload(retryOnly = false) {
  if (uploading.value) return
  const targets = uploadItems.value.filter(item => retryOnly ? item.status === 'FAILED' : ['READY', 'FAILED'].includes(item.status))
  if (!targets.length) return
  uploading.value = true
  try {
    let cursor = 0
    const worker = async () => {
      while (cursor < targets.length) {
        const item = targets[cursor++]
        if (item) await uploadOne(item)
      }
    }
    await Promise.all(Array.from({ length: Math.min(3, targets.length) }, worker))
    await loadMaterials()
    const failed = targets.filter(item => item.status === 'FAILED').length
    if (failed) ElMessage.warning(`${targets.length - failed} 个文件上传完成，${failed} 个失败，可仅重试失败项`)
    else ElMessage.success(`本批 ${targets.length} 个文件已全部上传并进入解析队列`)
  } finally { uploading.value = false }
}

async function remove(material: Material) {
  try {
    await ElMessageBox.confirm(`删除“${material.originalFilename}”及其文件？`, '确认删除资料', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await deleteMaterial(material.id); await loadMaterials(); ElMessage.success('资料已删除')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(describe(error)) }
}

async function openPreview(material: Material) {
  try {
    const preview = await getMaterialPreview(material.id)
    window.open(preview.url, '_blank', 'noopener,noreferrer')
  } catch (error) { ElMessage.error(describe(error)) }
}

async function taskAction(material: Material, action: 'cancel' | 'republish' | 'retry') {
  if (!material.taskId) return
  try {
    const task = action === 'cancel' ? await cancelTask(material.taskId)
      : action === 'republish' ? await republishTask(material.taskId) : await retryMaterial(material.id)
    material.status = task.status
    ElMessage.success(action === 'cancel' ? '任务已取消' : '任务已重新投递')
  } catch (error) { ElMessage.error(describe(error)) }
}

function connectProgress() {
  progressEvents = new EventSource('/api/v1/tasks/events', { withCredentials: true })
  progressEvents.onopen = () => { progressConnection.value = 'connected' }
  progressEvents.onerror = () => { progressConnection.value = 'reconnecting' }
  progressEvents.addEventListener('task-progress', (event) => {
    const task = JSON.parse((event as MessageEvent).data) as TaskEvent
    taskEvents.value[task.taskId] = task
    const material = materials.value.find((item) => item.taskId === task.taskId)
    if (material) material.status = task.status
    if (knowledgeState.value?.activeOutlineTask?.id === task.taskId && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.status))
      void loadMaterials()
  })
}

onMounted(() => { void loadMaterials(); connectProgress() })
onBeforeUnmount(() => progressEvents?.close())
</script>

<template>
  <section class="upload-panel" aria-labelledby="upload-title">
    <div><h2 id="upload-title">批量上传资料</h2><p>一次可选择多份文件，共用资料类型和重点说明；最多同时上传 3 个文件，失败项可以单独续传。</p></div>
    <div class="form-grid">
      <label>资料类型 <el-select v-model="materialType" :disabled="uploading"><el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></label>
      <label>重点说明（可选）<el-input v-model="focusNotes" maxlength="1000" show-word-limit :disabled="uploading" placeholder="本批资料共用，例如：老师强调内容或考试范围" /></label>
    </div>
    <button class="drop-zone" :class="{ dragging }" type="button" :disabled="uploading" @click="fileInput?.click()" @dragover.prevent="dragging = true" @dragleave="dragging = false" @drop.prevent="drop">
      <strong>拖拽多个文件到这里，或点击批量选择</strong>
      <span>单个文档 ≤100MB；音视频 ≤2GB；单批最多 50 个</span>
    </button>
    <input ref="fileInput" class="visually-hidden" type="file" multiple accept=".pdf,.pptx,.txt,.md,.mp3,.mp4" @change="chooseFiles(($event.target as HTMLInputElement).files ?? undefined)" />
    <div v-if="uploadItems.length" class="upload-actions">
      <div class="batch-progress"><span>本批 {{ completedUploadCount }}/{{ uploadItems.length }} 个已完成</span><el-progress :percentage="batchPercent" /></div>
      <div class="upload-queue">
        <article v-for="item in uploadItems" :key="item.key" class="upload-item">
          <div class="upload-file"><strong>{{ item.file.name }}</strong><span>{{ formatSize(item.file.size) }}</span></div>
          <div class="upload-item-progress"><el-progress :percentage="itemPercent(item)" :status="item.status === 'FAILED' ? 'exception' : item.status === 'COMPLETED' ? 'success' : undefined" /></div>
          <span class="upload-state">{{ item.status === 'READY' ? '等待上传' : item.status === 'UPLOADING' ? '上传中' : item.status === 'COMPLETED' ? (item.duplicate ? '已存在，已复用' : '等待解析') : '上传失败' }}</span>
          <el-button v-if="item.status !== 'UPLOADING'" link :disabled="uploading" @click="removeUploadItem(item)">移除</el-button>
          <p v-if="item.error" class="error" role="alert">{{ item.error }}</p>
        </article>
      </div>
      <div class="batch-actions">
        <el-button type="primary" :loading="uploading" :disabled="!uploadItems.some(item => ['READY','FAILED'].includes(item.status))" @click="startUpload(false)">上传全部待处理文件</el-button>
        <el-button v-if="failedUploadCount" :disabled="uploading" @click="startUpload(true)">仅重试失败项（{{ failedUploadCount }}）</el-button>
        <el-button v-if="completedUploadCount" :disabled="uploading" @click="clearFinishedUploads">清除已完成项</el-button>
      </div>
    </div>
  </section>

  <section class="materials" aria-labelledby="materials-title">
    <h2 id="materials-title">课程资料</h2><p class="connection" role="status">任务进度：{{ progressConnection === 'connected' ? '实时连接' : '正在重新连接，最终状态仍可恢复' }}</p>
    <p v-if="materials.length === 0" class="empty">还没有资料。</p>
    <el-table v-else class="desktop-table" :data="materials">
      <el-table-column prop="originalFilename" label="文件" min-width="220" />
      <el-table-column label="大小" width="110"><template #default="scope">{{ formatSize(scope.row.sizeBytes) }}</template></el-table-column>
      <el-table-column label="状态" width="190"><template #default="scope"><span class="status">{{ statusLabels[scope.row.status as MaterialStatus] }}</span><small v-if="scope.row.taskId && taskEvents[scope.row.taskId]">阶段 {{ taskEvents[scope.row.taskId]?.stage }} · {{ taskEvents[scope.row.taskId]?.progress }}%<template v-if="taskEvents[scope.row.taskId]?.message"> · {{ taskEvents[scope.row.taskId]?.message }}</template></small></template></el-table-column>
      <el-table-column label="操作" width="240"><template #default="scope">
        <el-button link type="primary" @click="openPreview(scope.row)">预览</el-button>
        <el-button v-if="scope.row.status === 'PUBLISH_FAILED'" link type="primary" @click="taskAction(scope.row, 'republish')">重新投递</el-button>
        <el-button v-if="scope.row.status === 'FAILED'" link type="primary" @click="taskAction(scope.row, 'retry')">重试</el-button>
        <el-button v-if="scope.row.status === 'QUEUED'" link @click="taskAction(scope.row, 'cancel')">取消任务</el-button>
        <el-button v-if="deletable.has(scope.row.status)" link type="danger" @click="remove(scope.row)">删除</el-button>
      </template></el-table-column>
    </el-table>
    <div class="mobile-list"><article v-for="material in materials" :key="material.id"><strong>{{ material.originalFilename }}</strong><span>{{ formatSize(material.sizeBytes) }} · {{ statusLabels[material.status] }}</span><div><el-button link type="primary" @click="openPreview(material)">预览</el-button><el-button v-if="material.status === 'PUBLISH_FAILED'" link type="primary" @click="taskAction(material, 'republish')">重新投递</el-button><el-button v-if="material.status === 'FAILED'" link type="primary" @click="taskAction(material, 'retry')">重试</el-button><el-button v-if="material.status === 'QUEUED'" link @click="taskAction(material, 'cancel')">取消任务</el-button><el-button v-if="deletable.has(material.status)" link type="danger" @click="remove(material)">删除</el-button></div></article></div>
  </section>

  <section class="finalize" aria-labelledby="finalize-title">
    <div><h2 id="finalize-title">确认课程资料</h2>
      <p v-if="unfinishedCount">还有 {{ unfinishedCount }} 份资料尚未结束，暂时不能确认。</p>
      <p v-else-if="successfulCount === 0">至少需要一份解析成功的资料。</p>
      <p v-else-if="!knowledgeState?.hasUnconfirmedSuccessfulMaterials">当前成功资料已生成知识版本；上传并解析新资料后可再次确认。</p>
      <p v-else>确认后会固定本次成功资料，并在后台自动生成新版知识提纲。</p>
      <p v-if="knowledgeState?.current" class="current-version">当前已发布：第 {{ knowledgeState.current.version }} 版</p>
    </div>
    <el-button type="primary" :disabled="!canConfirmMaterials" :loading="confirmingMaterials" @click="finalizeMaterials">资料已上传完毕</el-button>
    <el-button v-if="knowledgeState?.recentFailedOutlineTask && !knowledgeState.activeOutlineTask" type="danger" plain @click="retryOutline">重试本次提纲</el-button>
  </section>
</template>

<style scoped>
.upload-panel,.materials,.finalize { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; margin-top: 24px; padding: 24px; }h2{font-size:20px;line-height:28px;margin:0 0 4px}.upload-panel>div>p,.connection,.finalize p{color:var(--fw-text-secondary);margin:0}.finalize{align-items:center;display:flex;gap:12px}.finalize>div{flex:1}.current-version{margin-top:6px!important}.form-grid{display:grid;gap:16px;grid-template-columns:220px 1fr;margin:20px 0}.form-grid label{display:grid;font-weight:600;gap:6px}.drop-zone{align-items:center;background:var(--fw-background);border:1px dashed #9ca3af;border-radius:8px;color:var(--fw-text);cursor:pointer;display:flex;flex-direction:column;gap:4px;padding:28px;width:100%}.drop-zone.dragging{border-color:var(--fw-primary);background:#eff6ff}.drop-zone span{color:var(--fw-text-secondary);font-weight:400}.visually-hidden{height:1px;overflow:hidden;position:absolute;width:1px;clip:rect(0 0 0 0)}.upload-actions{margin-top:16px}.batch-progress{margin-bottom:12px}.upload-queue{display:grid;gap:8px;margin:12px 0}.upload-item{align-items:center;border:1px solid var(--fw-border);border-radius:8px;display:grid;gap:10px;grid-template-columns:minmax(220px,1.3fr) minmax(160px,1fr) 90px auto;padding:10px 12px}.upload-file{display:grid;min-width:0}.upload-file strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.upload-file span,.upload-state{color:var(--fw-text-secondary);font-size:13px}.upload-item .error{grid-column:1/-1;margin:0}.batch-actions{display:flex;flex-wrap:wrap;gap:8px}.error{color:var(--fw-danger)}.empty{color:var(--fw-text-secondary)}.mobile-list{display:none}.status{display:block;font-weight:600}.status+small{color:var(--fw-text-secondary);display:block;line-height:18px}@media(max-width:767px){.upload-panel,.materials,.finalize{padding:20px}.upload-item{grid-template-columns:1fr}.upload-item .error{grid-column:auto}.batch-actions{align-items:stretch;flex-direction:column}.finalize{align-items:stretch;flex-direction:column}.form-grid{grid-template-columns:1fr}.desktop-table{display:none}.mobile-list{display:grid;gap:12px}.mobile-list article{border:1px solid var(--fw-border);border-radius:8px;display:grid;gap:6px;padding:16px}.mobile-list span{color:var(--fw-text-secondary)}}
</style>
