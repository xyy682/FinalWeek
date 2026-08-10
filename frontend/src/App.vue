<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterView } from 'vue-router'

const serviceUnavailable = ref(false)
const onUnavailable = () => { serviceUnavailable.value = true }

onMounted(() => window.addEventListener('fw:service-unavailable', onUnavailable))
onBeforeUnmount(() => window.removeEventListener('fw:service-unavailable', onUnavailable))
</script>

<template>
  <div v-if="serviceUnavailable" class="service-banner" role="alert">
    服务暂时不可用（503）。为保护登录状态，页面已停止自动重试。
    <button type="button" @click="serviceUnavailable = false">关闭</button>
  </div>
  <RouterView />
</template>

<style scoped>
.service-banner { align-items: center; background: #fef2f2; border-bottom: 1px solid #fecaca; color: var(--fw-danger); display: flex; gap: 16px; justify-content: center; padding: 10px 16px; }
.service-banner button { background: transparent; border: 0; color: inherit; cursor: pointer; text-decoration: underline; }
</style>

