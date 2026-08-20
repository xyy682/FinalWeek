import { defineStore } from 'pinia'
import { ElNotification } from 'element-plus'
import { getTask, listActiveTasks, type TaskEvent, type TaskProgress } from '@/api/tasks'

const activeStatuses = new Set(['PENDING_PUBLISH', 'PUBLISH_FAILED', 'QUEUED', 'PROCESSING', 'RETRYING'])

export const useTaskStore = defineStore('tasks', {
  state: () => ({
    active: [] as TaskProgress[],
    connected: false,
    source: null as EventSource | null,
    reconnectTimer: 0 as number,
  }),
  getters: {
    visible: state => state.active.filter(task => task.visibleInGlobalDrawer),
  },
  actions: {
    async refresh() {
      this.active = await listActiveTasks()
    },
    async connect() {
      if (this.source) return
      try { await this.refresh() } catch { /* SSE can recover later. */ }
      const source = new EventSource('/api/v1/tasks/events', { withCredentials: true })
      this.source = source
      source.addEventListener('connected', () => { this.connected = true })
      source.addEventListener('task-progress', event => void this.handle(JSON.parse((event as MessageEvent).data) as TaskEvent))
      source.onerror = () => {
        this.connected = false; source.close(); this.source = null
        window.clearTimeout(this.reconnectTimer)
        this.reconnectTimer = window.setTimeout(() => void this.connect(), 3_000)
      }
    },
    async handle(event: TaskEvent) {
      window.dispatchEvent(new CustomEvent('fw:task-updated', { detail: event }))
      let task: TaskProgress | null = null
      try { task = await getTask(event.taskId) } catch { return }
      const index = this.active.findIndex(value => value.id === task!.id)
      if (activeStatuses.has(task.status)) {
        if (index >= 0) this.active.splice(index, 1, task)
        else this.active.unshift(task)
      } else if (index >= 0) this.active.splice(index, 1)
      if (task.status === 'SUCCEEDED') ElNotification.success({
        title: task.type === 'ANSWER_CHAT' ? '课程回答已生成' : '后台任务已完成',
        message: task.type === 'ANSWER_CHAT' ? '返回课程问答页即可查看回答。' : '结果已经可以查看。',
        onClick: () => window.location.assign(task!.jumpTarget),
      })
      if (task.status === 'FAILED' || task.status === 'PUBLISH_FAILED') ElNotification.error({
        title: '后台任务失败', message: task.errorMessage || task.errorCode || '请重试任务',
        onClick: () => window.location.assign(task!.jumpTarget),
      })
    },
  },
})
