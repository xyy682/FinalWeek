<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ content: string }>()
type Block = { type: 'paragraph' | 'heading' | 'list' | 'ordered' | 'code'; lines: string[] }
type Inline = { type: 'text' | 'strong' | 'em' | 'code' | 'link'; text: string; href?: string }
const blocks = computed<Block[]>(() => {
  const result: Block[] = []; const lines = props.content.replace(/\r/g, '').split('\n'); let code: string[] | null = null
  for (const raw of lines) {
    if (raw.trim().startsWith('```')) { if (code) { result.push({ type: 'code', lines: code }); code = null } else code = []; continue }
    if (code) { code.push(raw); continue }
    const line = raw.trim(); if (!line) continue
    if (/^#{1,3}\s/.test(line)) result.push({ type: 'heading', lines: [line.replace(/^#{1,3}\s+/, '')] })
    else if (/^[-*]\s/.test(line)) addList(result, 'list', line.replace(/^[-*]\s+/, ''))
    else if (/^\d+[.)]\s/.test(line)) addList(result, 'ordered', line.replace(/^\d+[.)]\s+/, ''))
    else result.push({ type: 'paragraph', lines: [line] })
  }
  if (code) result.push({ type: 'code', lines: code })
  return result
})
function addList(result: Block[], type: 'list' | 'ordered', line: string) {
  const previous = result.at(-1); if (previous?.type === type) previous.lines.push(line); else result.push({ type, lines: [line] })
}
function inline(value: string): Inline[] {
  const parts: Inline[] = []; const pattern = /(\*\*[^*\n]+\*\*|`[^`\n]+`|\[[^\]\n]+\]\(https?:\/\/[^)\s]+\)|\*[^*\n]+\*)/g
  let start = 0
  for (const match of value.matchAll(pattern)) {
    const index = match.index ?? 0; if (index > start) parts.push({ type: 'text', text: value.slice(start, index) })
    const token = match[0]
    if (token.startsWith('**')) parts.push({ type: 'strong', text: token.slice(2, -2) })
    else if (token.startsWith('`')) parts.push({ type: 'code', text: token.slice(1, -1) })
    else if (token.startsWith('[')) { const parsed = token.match(/^\[([^\]]+)\]\((https?:\/\/[^)]+)\)$/); if (parsed) parts.push({ type: 'link', text: parsed[1] ?? '', href: parsed[2] ?? '' }) }
    else parts.push({ type: 'em', text: token.slice(1, -1) })
    start = index + token.length
  }
  if (start < value.length) parts.push({ type: 'text', text: value.slice(start) })
  return parts.length ? parts : [{ type: 'text', text: value }]
}
</script>

<template>
  <div class="safe-markdown">
    <template v-for="(block, index) in blocks" :key="index">
      <component :is="block.type === 'heading' ? 'h3' : block.type === 'paragraph' ? 'p' : block.type === 'list' ? 'ul' : block.type === 'ordered' ? 'ol' : 'pre'">
        <code v-if="block.type === 'code'">{{ block.lines.join('\n') }}</code>
        <template v-else-if="block.type === 'list' || block.type === 'ordered'">
          <li v-for="line in block.lines" :key="line"><template v-for="(part, i) in inline(line)" :key="i"><strong v-if="part.type === 'strong'">{{ part.text }}</strong><em v-else-if="part.type === 'em'">{{ part.text }}</em><code v-else-if="part.type === 'code'">{{ part.text }}</code><a v-else-if="part.type === 'link'" :href="part.href" target="_blank" rel="noopener noreferrer">{{ part.text }}</a><template v-else>{{ part.text }}</template></template></li>
        </template>
        <template v-else v-for="(part, i) in inline(block.lines[0] ?? '')" :key="i"><strong v-if="part.type === 'strong'">{{ part.text }}</strong><em v-else-if="part.type === 'em'">{{ part.text }}</em><code v-else-if="part.type === 'code'">{{ part.text }}</code><a v-else-if="part.type === 'link'" :href="part.href" target="_blank" rel="noopener noreferrer">{{ part.text }}</a><template v-else>{{ part.text }}</template></template>
      </component>
    </template>
  </div>
</template>

<style scoped>
.safe-markdown{line-height:1.75;overflow-wrap:anywhere}.safe-markdown p{margin:8px 0}.safe-markdown ul,.safe-markdown ol{margin:8px 0;padding-left:24px}.safe-markdown h3{font-size:16px;margin:14px 0 6px}.safe-markdown pre{background:var(--fw-surface);border-radius:6px;overflow:auto;padding:12px;white-space:pre-wrap}.safe-markdown :not(pre)>code{background:rgba(100,116,139,.12);border-radius:4px;padding:1px 5px}.safe-markdown a{color:var(--fw-primary)}
</style>