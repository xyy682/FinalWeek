<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { askQuestion, getMessages, retryQuestion, type ChatMessage, type ChatSource } from '@/api/chat'
import { getMaterialPreview, getSourceSegment, listMaterials, type Material, type MaterialPreview, type SourceSegment } from '@/api/materials'
import SafeMarkdown from './SafeMarkdown.vue'

const props = defineProps<{ courseId: string }>()
const messages = ref<ChatMessage[]>([]); const nextCursor = ref<string | null>(null)
const materials = ref<Material[]>([]); const question = ref(''); const loading = ref(true); const sending = ref(false)
const drawerOpen = ref(false); const sourceLoading = ref(false); const selectedSegment = ref<SourceSegment | null>(null)
const selectedPreview = ref<MaterialPreview | null>(null); const media = ref<HTMLMediaElement | null>(null)
let controller: AbortController | null = null
const selectedMaterial = computed(() => materials.value.find(value => value.id === selectedSegment.value?.materialId) ?? null)
const hasKnowledge = computed(() => materials.value.some(value => value.status === 'SUCCEEDED'))

function describe(error: unknown) {
  if (error instanceof ApiError) return error.status === 429 ? '提问过于频繁，请稍后重试' : `${error.body.message}（${error.body.requestId}）`
  if (error instanceof DOMException && error.name === 'AbortError') return '已取消本地等待，回答可能仍在服务端处理中'
  return '问答请求失败，请稍后重试'
}
async function loadLatest() {
  const page = await getMessages(props.courseId); messages.value = page.messages; nextCursor.value = page.nextCursor
}
async function loadOlder() {
  if (!nextCursor.value) return
  try { const page = await getMessages(props.courseId, nextCursor.value); messages.value = [...page.messages, ...messages.value]; nextCursor.value = page.nextCursor }
  catch (error) { ElMessage.error(describe(error)) }
}
async function send() {
  const value = question.value.trim(); if (!value || sending.value) return
  if (!hasKnowledge.value) { ElMessage.warning('至少需要一份解析成功的课程资料才能提问'); return }
  sending.value = true; controller = new AbortController()
  try { await askQuestion(props.courseId, value, controller.signal); question.value = ''; await loadLatest() }
  catch (error) { ElMessage.error(describe(error)); await loadLatest().catch(() => undefined) }
  finally { sending.value = false; controller = null }
}
async function retry(message: ChatMessage) {
  if (sending.value) return; sending.value = true; controller = new AbortController()
  try { await retryQuestion(message.id, controller.signal); await loadLatest() }
  catch (error) { ElMessage.error(describe(error)); await loadLatest().catch(() => undefined) }
  finally { sending.value = false; controller = null }
}
function cancelWait() { controller?.abort() }
function sourceLabel(source: ChatSource | SourceSegment) {
  if (source.pageNumber) return `第 ${source.pageNumber} 页`; if (source.slideNumber) return `第 ${source.slideNumber} 张幻灯片`
  if (source.paragraphNumber) return `第 ${source.paragraphNumber} 段`; if (source.startTimeMs != null) return `${Math.floor(source.startTimeMs / 60000)}:${String(Math.floor(source.startTimeMs / 1000) % 60).padStart(2, '0')}`
  return '原文位置'
}
async function openSource(source: ChatSource) {
  drawerOpen.value = true; sourceLoading.value = true; selectedPreview.value = null
  try { selectedSegment.value = await getSourceSegment(source.segmentId); selectedPreview.value = await getMaterialPreview(selectedSegment.value.materialId); await nextTick(); if (media.value && selectedSegment.value.startTimeMs != null) media.value.currentTime = selectedSegment.value.startTimeMs / 1000 }
  catch (error) { ElMessage.error(describe(error)) } finally { sourceLoading.value = false }
}
onMounted(async () => { try { [materials.value] = await Promise.all([listMaterials(props.courseId), loadLatest()]) } catch (error) { ElMessage.error(describe(error)) } finally { loading.value = false } })
</script>

<template>
  <section class="chat-panel" v-loading="loading">
    <header><div><h2>课程问答</h2><p>回答优先依据课程资料，通用知识会单独标记。</p></div><el-button v-if="nextCursor" @click="loadOlder">加载更早消息</el-button></header>
    <div class="history" aria-live="polite">
      <el-empty v-if="!messages.length && !loading" :description="hasKnowledge ? '围绕已解析的课程资料提出文字问题' : '至少上传并成功解析一份课程资料后才能提问'" />
      <article v-for="message in messages" :key="message.id" class="message" :class="message.role.toLowerCase()">
        <strong>{{ message.role === 'USER' ? '你' : 'FinalWeek' }}</strong>
        <SafeMarkdown :content="message.content" />
        <div v-if="message.role === 'ASSISTANT' && message.sources.length" class="sources"><span>课程资料来源</span><el-button v-for="source in message.sources" :key="source.segmentId" link type="primary" @click="openSource(source)">{{ sourceLabel(source) }}</el-button></div>
        <aside v-if="message.generalKnowledgeUsed && message.generalKnowledgeContent" class="general"><strong>通用知识补充</strong><SafeMarkdown :content="message.generalKnowledgeContent" /></aside>
        <p v-if="message.status === 'PENDING'" class="status">处理中</p>
        <div v-if="message.role === 'USER' && message.status === 'FAILED'" class="failed"><span>发送失败 · {{ message.errorCode }}</span><el-button link type="primary" :disabled="sending" @click="retry(message)">重试原问题</el-button></div>
      </article>
    </div>
    <div class="composer"><el-input v-model="question" type="textarea" :rows="3" maxlength="2000" show-word-limit :disabled="!hasKnowledge" :placeholder="hasKnowledge ? '输入课程相关问题（仅文字）' : '请先上传并成功解析课程资料'" @keydown.ctrl.enter.prevent="send" /><div><span>Ctrl + Enter 发送</span><el-button v-if="sending" @click="cancelWait">取消等待</el-button><el-button type="primary" :loading="sending" :disabled="!hasKnowledge || !question.trim()" @click="send">发送</el-button></div></div>
  </section>
  <el-drawer v-model="drawerOpen" title="课程资料来源" size="min(560px, 92vw)"><div v-loading="sourceLoading" class="source-drawer"><template v-if="selectedSegment"><h3>{{ selectedMaterial?.originalFilename ?? '课程资料' }}</h3><p>{{ sourceLabel(selectedSegment) }}</p><blockquote>{{ selectedSegment.content }}</blockquote><iframe v-if="selectedPreview?.normalizedPdf" :src="`${selectedPreview.url}#page=${selectedSegment.pageNumber ?? selectedSegment.slideNumber ?? 1}`" title="来源 PDF 预览" /><audio v-else-if="selectedPreview?.mediaType === 'audio/mpeg'" ref="media" controls :src="selectedPreview.url" /><video v-else-if="selectedPreview?.mediaType === 'video/mp4'" ref="media" controls :src="selectedPreview.url" /><el-button v-else-if="selectedPreview" tag="a" :href="selectedPreview.url" target="_blank" rel="noopener noreferrer">打开原资料</el-button></template></div></el-drawer>
</template>

<style scoped>
.chat-panel{background:var(--fw-surface);border:1px solid var(--fw-border);border-radius:12px;margin-top:24px;padding:24px}.chat-panel>header{align-items:flex-start;display:flex;justify-content:space-between}.chat-panel h2{font-size:20px;margin:0 0 4px}.chat-panel header p,.status{color:var(--fw-text-secondary);margin:0}.history{display:grid;gap:12px;margin:20px 0;max-height:62vh;overflow:auto;padding:4px}.message{border-radius:12px;max-width:82%;padding:14px 16px}.message.user{background:var(--fw-primary-light);justify-self:end}.message.assistant{background:var(--fw-background);border:1px solid var(--fw-border);justify-self:start}.sources{align-items:center;border-top:1px solid var(--fw-border);display:flex;flex-wrap:wrap;gap:4px;margin-top:12px;padding-top:8px}.sources>span{color:var(--fw-text-secondary);font-size:13px}.general{background:#fff8e8;border-left:3px solid #d99a00;margin-top:12px;padding:10px 12px}.failed{align-items:center;color:var(--fw-danger);display:flex;gap:8px}.composer{border-top:1px solid var(--fw-border);padding-top:16px}.composer>div{align-items:center;color:var(--fw-text-secondary);display:flex;gap:8px;justify-content:flex-end;margin-top:8px}.composer>div span{margin-right:auto}.source-drawer{display:grid;gap:16px}.source-drawer h3,.source-drawer p{margin:0}.source-drawer blockquote{background:var(--fw-background);border-left:3px solid var(--fw-primary);margin:0;padding:16px;white-space:pre-wrap}.source-drawer iframe{border:1px solid var(--fw-border);height:58vh;width:100%}.source-drawer audio,.source-drawer video{max-height:58vh;width:100%}@media(max-width:767px){.chat-panel{padding:18px}.chat-panel>header{gap:12px}.message{max-width:94%}.composer>div span{display:none}}
</style>
