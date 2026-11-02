<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiClientError } from '../api/http'
import { attachmentUrl, fetchAttachmentMeta, uploadAttachment } from '../api/attachments'
import type { AttachmentMeta } from '../types/attachment'
import { ATTACHMENT_ACCEPT, MAX_ATTACHMENT_BYTES, formatBytes, isImage } from '../types/attachment'

/**
 * 첨부 하나. 값은 첨부 id 문자열이고 부모(이력서 행)는 그것만 들고 있다.
 *
 * 업로드는 파일을 고르는 즉시 일어난다 — 그래야 미리보기가 된다. 이력서 문서에 id 가 들어가는 건
 * `저장` 을 눌렀을 때다. 저장 없이 나가면 고아 첨부가 남는다(docs/attachments.md 함정).
 * 교체·제거는 값만 바꾸고 파일은 지우지 않는다. 복제한 이력서가 같은 id 를 가리킬 수 있다.
 */
const props = defineProps<{
  id: string
  label: string
  /** 이력서 응답에 함께 실려 온 이 id 의 메타. 있으면 다시 부르지 않는다(설계 1.3 P5). */
  preloaded?: AttachmentMeta
}>()
const emit = defineEmits<{ 'update:id': [string] }>()

const meta = ref<AttachmentMeta | null>(null)
const busy = ref(false)
const error = ref('')
const previewing = ref(false)
const picker = ref<HTMLInputElement | null>(null)

watch(() => props.id, async (id) => {
  error.value = ''
  if (!id) { meta.value = null; return }
  if (meta.value?.id === id) return
  if (props.preloaded?.id === id) { meta.value = props.preloaded; return }
  try {
    meta.value = await fetchAttachmentMeta(id)
  } catch {
    // 파일이 지워졌거나 남의 것이면 404 다. 행은 살아 있어야 하니 이름만 비운다.
    meta.value = null
  }
}, { immediate: true })

async function pick(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  error.value = ''
  if (file.size > MAX_ATTACHMENT_BYTES) {
    error.value = '파일은 10MB 이하여야 합니다.'
    return
  }
  busy.value = true
  try {
    // 교체는 새 업로드다. 이전 파일은 지우지도 덮어쓰지도 않는다 — 복제한 이력서가 같은 id 를 가리킬 수 있고,
    // 저장하지 않고 나가면 이력서는 여전히 이전 id 를 들고 있다(설계 1.4 T7).
    const uploaded = await uploadAttachment(file)
    meta.value = uploaded
    emit('update:id', uploaded.id)
  } catch (e) {
    error.value = e instanceof ApiClientError ? e.message : '파일을 올리지 못했습니다.'
  } finally {
    busy.value = false
  }
}

/** 이 이력서에서 연결만 푼다. 파일은 남는다 — 교체와 같은 이유. */
function clear() {
  emit('update:id', '')
  meta.value = null
  previewing.value = false
}
</script>

<template>
  <div class="field full attachment">
    <span>{{ label }}</span>
    <input
      ref="picker"
      type="file"
      class="attachment__picker"
      :accept="ATTACHMENT_ACCEPT"
      :aria-label="label"
      @change="pick"
    />
    <div class="attachment__bar">
      <span v-if="meta" class="chip attachment__name" :title="meta.filename">
        {{ meta.filename }} <small>{{ formatBytes(meta.size) }}</small>
      </span>
      <span v-else-if="id" class="chip" data-tone="warning">파일을 찾을 수 없습니다</span>
      <span v-else class="muted-note">jpg · png · pdf, 10MB 이하</span>

      <span class="attachment__actions">
        <button type="button" class="button ghost compact" :disabled="busy" @click="picker?.click()">
          {{ busy ? '올리는 중…' : id ? '교체' : '파일 선택' }}
        </button>
        <button v-if="meta" type="button" class="button secondary compact" @click="previewing = true">미리보기</button>
        <button v-if="id" type="button" class="button danger compact" :disabled="busy" @click="clear">제거</button>
      </span>
    </div>
    <small v-if="error" class="field-error">{{ error }}</small>

    <div v-if="previewing && meta" class="preview-backdrop" role="dialog" aria-modal="true" :aria-label="`${label} 미리보기`">
      <div class="preview-panel">
        <div class="preview-panel__head">
          <strong>{{ meta.filename }}</strong>
          <span class="attachment__actions">
            <a class="button ghost compact" :href="attachmentUrl(meta.id)" target="_blank" rel="noreferrer">새 탭 ↗</a>
            <button type="button" class="icon-button" aria-label="미리보기 닫기" @click="previewing = false">×</button>
          </span>
        </div>
        <img v-if="isImage(meta.contentType)" :src="attachmentUrl(meta.id)" :alt="meta.filename" />
        <iframe v-else :src="attachmentUrl(meta.id)" :title="meta.filename" />
      </div>
    </div>
  </div>
</template>
