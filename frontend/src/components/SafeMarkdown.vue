<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ content: string }>()
type Block = { type: 'paragraph' | 'heading' | 'list' | 'code'; lines: string[] }
const blocks = computed<Block[]>(() => {
  const result: Block[] = []; const lines = props.content.replace(/\r/g, '').split('\n'); let code: string[] | null = null
  for (const raw of lines) {
    if (raw.trim().startsWith('```')) { if (code) { result.push({ type: 'code', lines: code }); code = null } else code = []; continue }
    if (code) { code.push(raw); continue }
    const line = raw.trim(); if (!line) continue
    if (/^#{1,3}\s/.test(line)) result.push({ type: 'heading', lines: [line.replace(/^#{1,3}\s+/, '')] })
    else if (/^[-*]\s/.test(line)) {
      const item = line.replace(/^[-*]\s+/, ''); const previous = result.at(-1)
      if (previous?.type === 'list') previous.lines.push(item); else result.push({ type: 'list', lines: [item] })
    } else result.push({ type: 'paragraph', lines: [line] })
  }
  if (code) result.push({ type: 'code', lines: code })
  return result
})
</script>

<template>
  <div class="safe-markdown">
    <template v-for="(block, index) in blocks" :key="index">
      <strong v-if="block.type === 'heading'" class="heading">{{ block.lines[0] }}</strong>
      <ul v-else-if="block.type === 'list'"><li v-for="line in block.lines" :key="line">{{ line }}</li></ul>
      <pre v-else-if="block.type === 'code'"><code>{{ block.lines.join('\n') }}</code></pre>
      <p v-else>{{ block.lines[0] }}</p>
    </template>
  </div>
</template>

<style scoped>
.safe-markdown{line-height:1.7}.safe-markdown p{margin:6px 0}.safe-markdown ul{margin:6px 0;padding-left:22px}.heading{display:block;margin-top:10px}.safe-markdown pre{background:var(--fw-background);border-radius:6px;overflow:auto;padding:12px;white-space:pre-wrap}
</style>
