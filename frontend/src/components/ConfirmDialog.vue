<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    /** 굵게 표시되는 대상 이름. */
    subject: string
    /** subject 뒤에 이어지는 설명. */
    detail: string
    confirmLabel?: string
    ariaLabel?: string
    busy: boolean
    /** 확정 전에 알려야 할 내용을 아직 받는 중. 확정만 막고 취소는 둔다. */
    pending?: boolean
  }>(),
  { confirmLabel: '삭제', ariaLabel: '삭제 확인' },
)
const emit = defineEmits<{ confirm: []; cancel: [] }>()
</script>

<template>
  <div class="modal-backdrop" @mousedown.self="emit('cancel')">
    <section class="confirm-card" role="alertdialog" aria-modal="true" :aria-label="ariaLabel">
      <span class="danger-mark">!</span>
      <h2>{{ title }}</h2>
      <p><strong>{{ subject }}</strong>{{ detail }}</p>
      <div class="modal-actions">
        <button type="button" class="button secondary" :disabled="busy" @click="emit('cancel')">취소</button>
        <button type="button" class="button danger solid" :disabled="busy || pending" @click="emit('confirm')">
          {{ busy ? `${confirmLabel} 중…` : confirmLabel }}
        </button>
      </div>
    </section>
  </div>
</template>
