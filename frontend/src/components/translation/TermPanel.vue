<script setup lang="ts">
import { BookOutlined, SoundOutlined } from '@ant-design/icons-vue'
import type { TranslationTerm } from '../../types'

defineProps<{
  terms: TranslationTerm[]
  collectingIds: Set<string>
  collectedIds: Set<string>
}>()

const emit = defineEmits<{
  collect: [id: string]
  speak: [text: string]
}>()
</script>

<template>
  <section class="term-panel" aria-labelledby="term-panel-title">
    <header class="term-panel-header">
      <div>
        <h2 id="term-panel-title">术语批注</h2>
        <span>{{ terms.length }} 个重点术语</span>
      </div>
    </header>
    <a-empty v-if="!terms.length" :image="false" description="本次译文没有识别到重点术语" />
    <div v-else class="term-table" role="list">
      <article v-for="term in terms" :key="term.id" class="term-row" role="listitem">
        <div class="term-identity">
          <strong class="term-word">{{ term.sourceTerm }}</strong>
          <a-button type="text" size="small" :aria-label="`朗读 ${term.sourceTerm}`" @click="emit('speak', term.sourceTerm)">
            <SoundOutlined />
          </a-button>
          <span class="mono">{{ term.ipa || '—' }}</span>
          <span>{{ term.partOfSpeech || '未标注词性' }}</span>
        </div>
        <div class="term-meaning">
          <b>{{ term.targetTerm }}</b>
          <p>{{ term.explanation || '暂无补充解释' }}</p>
        </div>
        <a-button
          size="small"
          :type="collectedIds.has(term.id) ? 'default' : 'link'"
          :disabled="collectedIds.has(term.id)"
          :loading="collectingIds.has(term.id)"
          @click="emit('collect', term.id)"
        >
          <BookOutlined />{{ collectedIds.has(term.id) ? '已收藏' : '加入词库' }}
        </a-button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.term-panel { margin-top: 16px; overflow: hidden; background: var(--color-bg-surface); border: 1px solid var(--color-border); border-radius: var(--radius-lg); }
.term-panel-header { display: flex; align-items: center; justify-content: space-between; padding: 13px 16px; background: #f1f2f5; border-bottom: 1px solid var(--color-border); }
.term-panel-header h2 { display: inline; margin: 0; font-size: 14px; }
.term-panel-header span { margin-left: 9px; color: var(--color-text-secondary); font-size: 12px; }
.term-table { display: grid; }
.term-row { display: grid; grid-template-columns: minmax(220px, .75fr) minmax(320px, 1.25fr) auto; align-items: center; gap: 18px; min-height: 72px; padding: 11px 16px; }
.term-row + .term-row { border-top: 1px solid var(--color-border); }
.term-identity { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px; }
.term-identity strong { color: #26358a; font-size: 15px; }
.term-identity span { color: var(--color-text-secondary); font-size: 11px; }
.term-meaning b { display: block; font-size: 13px; }
.term-meaning p { margin: 3px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.55; }
:deep(.ant-empty) { margin-block: 22px; }
@media (max-width: 760px) {
  .term-row { grid-template-columns: 1fr auto; gap: 8px 12px; }
  .term-meaning { grid-column: 1 / -1; grid-row: 2; }
}
</style>
