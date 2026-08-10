<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppHeader from '@/components/AppHeader.vue'
import { ApiError } from '@/api/http'
import { createCourse, deleteCourse, listCourses, renameCourse, type Course } from '@/api/courses'

const courses = ref<Course[]>([])
const loading = ref(true)
const mutating = ref(false)
const errorMessage = ref('')

function describeError(error: unknown, fallback: string): string {
  return error instanceof ApiError ? `${error.body.message}（${error.body.code} · ${error.body.requestId}）` : fallback
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    courses.value = await listCourses()
  } catch (error) {
    errorMessage.value = describeError(error, '课程加载失败')
  } finally {
    loading.value = false
  }
}

async function addCourse() {
  try {
    const { value } = await ElMessageBox.prompt('课程名称可随时修改。', '创建课程', {
      confirmButtonText: '创建', cancelButtonText: '取消', inputPlaceholder: '例如：计算机网络',
      inputValidator: (value) => value.trim().length > 0 && value.trim().length <= 80 ? true : '请输入 1–80 个字符',
    })
    mutating.value = true
    courses.value.unshift(await createCourse(value.trim()))
    ElMessage.success('课程已创建')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(describeError(error, '创建失败'))
  } finally {
    mutating.value = false
  }
}

async function rename(course: Course) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的课程名称。', '重命名课程', {
      confirmButtonText: '保存', cancelButtonText: '取消', inputValue: course.name,
      inputValidator: (value) => value.trim().length > 0 && value.trim().length <= 80 ? true : '请输入 1–80 个字符',
    })
    mutating.value = true
    const updated = await renameCourse(course.id, value.trim())
    courses.value = courses.value.map((item) => item.id === course.id ? updated : item)
    ElMessage.success('课程名称已更新')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(describeError(error, '重命名失败'))
  } finally {
    mutating.value = false
  }
}

async function remove(course: Course) {
  try {
    await ElMessageBox.confirm(`删除“${course.name}”后将无法恢复。`, '确认删除课程', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning', confirmButtonClass: 'el-button--danger',
    })
    mutating.value = true
    await deleteCourse(course.id)
    courses.value = courses.value.filter((item) => item.id !== course.id)
    ElMessage.success('课程已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(describeError(error, '删除失败'))
  } finally {
    mutating.value = false
  }
}

function statusText(status: string | null): string {
  return status ? status : '暂无解析任务'
}

onMounted(load)
</script>

<template>
  <AppHeader />
  <main class="page-shell" aria-labelledby="page-title">
    <div class="page-heading">
      <div><p class="eyebrow">学习空间</p><h1 id="page-title">我的课程</h1></div>
      <el-button v-if="courses.length > 0" type="primary" :loading="mutating" @click="addCourse">创建课程</el-button>
    </div>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
    <div v-if="loading" class="state-text">正在加载课程…</div>
    <section v-else-if="courses.length === 0" class="empty-state">
      <h2>创建第一门课程</h2><p>添加资料、生成知识提纲，并规划考前复习。</p>
      <el-button type="primary" :loading="mutating" @click="addCourse">创建课程</el-button>
    </section>
    <section v-else class="course-grid" aria-label="课程列表">
      <article v-for="course in courses" :key="course.id" class="course-card">
        <div class="card-title-row">
          <RouterLink :to="`/courses/${course.id}/materials`">{{ course.name }}</RouterLink>
          <el-dropdown trigger="click">
            <el-button text aria-label="课程操作">操作</el-button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item @click="rename(course)">重命名</el-dropdown-item><el-dropdown-item divided @click="remove(course)">删除</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
        <dl><div><dt>资料</dt><dd>{{ course.materialCount }} 份</dd></div><div><dt>最近解析</dt><dd>{{ statusText(course.recentParseStatus) }}</dd></div></dl>
        <p class="updated">更新于 {{ new Date(course.updatedAt).toLocaleString('zh-CN') }}</p>
      </article>
    </section>
  </main>
</template>

<style scoped>
.page-shell { margin: 0 auto; max-width: 1280px; padding: 48px 32px; }
.page-heading { align-items: end; display: flex; justify-content: space-between; margin-bottom: 24px; }
.eyebrow { color: var(--fw-primary); font-weight: 600; margin: 0; }
h1 { font-size: 28px; line-height: 36px; margin: 4px 0 0; }
.state-text, .empty-state { color: var(--fw-text-secondary); padding: 64px 0; text-align: center; }
.empty-state { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; }
.empty-state h2 { color: var(--fw-text); font-size: 20px; margin: 0 0 8px; }
.empty-state p { margin: 0 0 24px; }
.course-grid { display: grid; gap: 16px; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); }
.course-card { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; padding: 20px; }
.card-title-row { align-items: center; display: flex; justify-content: space-between; }
.card-title-row > a { color: var(--fw-text); font-size: 16px; font-weight: 600; text-decoration: none; }
.card-title-row > a:hover { color: var(--fw-primary); }
dl { border-top: 1px solid var(--fw-border); margin: 16px 0; padding-top: 12px; }
dl div { display: flex; justify-content: space-between; }
dt { color: var(--fw-text-secondary); } dd { margin: 0; }
.updated { color: var(--fw-text-secondary); font-size: 13px; margin: 0; }
@media (max-width: 767px) { .page-shell { padding: 32px 16px; } .page-heading { align-items: start; } }
</style>
