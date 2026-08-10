<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { sendLoginCode } from '@/api/auth'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const email = ref('')
const code = ref('')
const countdown = ref(0)
const sending = ref(false)
const loggingIn = ref(false)
const fieldError = ref('')
let timer: number | undefined

const canSend = computed(() => countdown.value === 0 && !sending.value)

async function sendCode() {
  fieldError.value = ''
  sending.value = true
  try {
    const result = await sendLoginCode(email.value)
    countdown.value = result.retryAfterSeconds
    timer = window.setInterval(() => {
      countdown.value = Math.max(0, countdown.value - 1)
      if (countdown.value === 0 && timer) window.clearInterval(timer)
    }, 1000)
    ElMessage.success(result.message)
  } catch (error) {
    fieldError.value = error instanceof ApiError ? `${error.body.message}（${error.body.requestId}）` : '验证码发送失败'
  } finally {
    sending.value = false
  }
}

async function submit() {
  fieldError.value = ''
  loggingIn.value = true
  try {
    await auth.login(email.value, code.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/courses'
    await router.replace(redirect)
  } catch (error) {
    fieldError.value = error instanceof ApiError ? error.body.message : '登录失败，请重试'
  } finally {
    loggingIn.value = false
  }
}

onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
</script>

<template>
  <main class="login-shell">
    <RouterLink class="brand" to="/">FinalWeek</RouterLink>
    <section aria-labelledby="login-title">
      <p class="eyebrow">欢迎回来</p>
      <h1 id="login-title">邮箱验证码登录</h1>
      <p class="hint">本地开发验证码会发送到 <a href="http://localhost:8025" target="_blank" rel="noreferrer">Mailpit</a>。</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="邮箱" required>
          <el-input v-model="email" type="email" autocomplete="email" placeholder="student@example.com" />
        </el-form-item>
        <el-form-item label="验证码" required>
          <div class="code-row">
            <el-input v-model="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码" />
            <el-button :disabled="!canSend || !email" :loading="sending" @click="sendCode">
              {{ countdown > 0 ? `${countdown} 秒` : '发送验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <p v-if="fieldError" class="field-error" role="alert">{{ fieldError }}</p>
        <el-button class="submit" native-type="submit" type="primary" :loading="loggingIn" :disabled="code.length !== 6 || !email">登录</el-button>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.login-shell { margin: 0 auto; max-width: 480px; padding: 64px 24px; }
.brand { color: var(--fw-primary); font-size: 18px; font-weight: 700; text-decoration: none; }
section { background: var(--fw-surface); border: 1px solid var(--fw-border); border-radius: 12px; margin-top: 32px; padding: 32px; }
.eyebrow { color: var(--fw-primary); font-weight: 600; margin: 0; }
h1 { font-size: 28px; line-height: 36px; margin: 8px 0; }
.hint { color: var(--fw-text-secondary); margin: 0 0 24px; }
.hint a { color: var(--fw-primary); }
.code-row { display: grid; gap: 8px; grid-template-columns: 1fr 120px; width: 100%; }
.field-error { color: var(--fw-danger); margin: -4px 0 12px; }
.submit { width: 100%; }
@media (max-width: 767px) { .login-shell { padding: 32px 16px; } section { padding: 24px; } .code-row { grid-template-columns: 1fr; } }
</style>

