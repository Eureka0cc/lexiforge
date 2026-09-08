<script setup lang="ts">
defineProps<{
  title: string
  label?: string
}>()
</script>

<template>
  <section class="document-pane" :aria-label="label ?? title">
    <header class="document-pane-header">
      <h2>{{ title }}</h2>
      <div class="document-pane-actions"><slot name="actions" /></div>
    </header>
    <div class="document-pane-body"><slot /></div>
    <footer v-if="$slots.footer" class="document-pane-footer"><slot name="footer" /></footer>
  </section>
</template>

<style scoped>
.document-pane { display: flex; min-width: 0; min-height: 510px; flex-direction: column; background: var(--color-bg-surface); }
.document-pane-header { display: flex; min-height: 45px; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 14px; background: #f1f2f5; border-bottom: 1px solid var(--color-border); }
.document-pane-header h2 { margin: 0; font-size: 13px; font-weight: 650; }
.document-pane-actions { display: flex; align-items: center; gap: 4px; }
.document-pane-body { position: relative; min-height: 0; flex: 1; }
.document-pane-footer { min-height: 42px; padding: 9px 14px; color: var(--color-text-secondary); background: var(--color-bg-editor); border-top: 1px solid var(--color-border); font-size: 12px; }
@media (max-width: 900px) { .document-pane { min-height: 430px; } }
@media (max-width: 640px) { .document-pane { min-height: 390px; } }
</style>
