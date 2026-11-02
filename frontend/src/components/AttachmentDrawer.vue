<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { attachmentUrl, deleteAttachment, listAttachments } from '../api/attachments'
import { ApiClientError } from '../api/http'
import ConfirmDialog from './ConfirmDialog.vue'
import Drawer from './Drawer.vue'
import { USE_LABELS, formatBytes, type AttachmentItem } from '../types/attachment'

/**
 * 이력서 목록의 [첨부 파일] 서랍 — 설계 6.9 길 2. 어디서도 쓰지 않고 올린 지 7일이 지난 파일만 지울 수 있다.
 * 7일 조건과 사용처는 서버가 deletable 로 계산해 준다. 서버 DELETE 는 7일을 보지 않으므로 버튼을 그리지 않는 것이 이 조건의 전부다.
 */
const emit = defineEmits<{ close: [] }>()

const items = ref<AttachmentItem[]>([])
const loading = ref(true)
const error = ref('')
const target = ref<AttachmentItem | null>(null)
const deleting = ref(false)

async function load() {
  loading.value = true
  try {
    items.value = await listAttachments()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '첨부 파일 목록을 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

async function remove() {
  if (!target.value) return
  deleting.value = true
  error.value = ''
  try {
    await deleteAttachment(target.value.id)
  } catch (failure) {
    // 409 는 목록을 읽은 뒤 다른 곳에서 쓰기 시작한 경우다. 서버 문구가 쓰는 곳을 알려 준다.
    error.value = failure instanceof ApiClientError ? failure.message : '파일을 지우지 못했습니다.'
  } finally {
    target.value = null
    deleting.value = false
  }
  await load()
}

const formatDate = (value: string) => new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(new Date(value))

onMounted(load)
</script>

<template>
  <Drawer title="첨부 파일" subtitle="어디서도 쓰지 않고 올린 지 7일이 지난 파일만 지울 수 있습니다." @close="emit('close')">
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <p v-if="loading" class="body-copy empty" aria-live="polite">불러오는 중입니다.</p>
    <p v-else-if="items.length === 0" class="body-copy empty">올린 파일이 없습니다.</p>
    <ul v-else class="attachment-list">
      <li v-for="item in items" :key="item.id" class="attachment-row">
        <div class="attachment-row__main">
          <a :href="attachmentUrl(item.id)" target="_blank" rel="noopener">{{ item.filename }}</a>
          <span class="attachment-row__meta">{{ formatBytes(item.size) }} · {{ formatDate(item.createdAt) }} 올림</span>
          <span v-if="item.usedBy.length" class="attachment-row__uses">
            <span v-for="use in item.usedBy" :key="`${use.type}-${use.id}`" class="chip" data-tone="primary">
              {{ USE_LABELS[use.type] ?? use.type }} · {{ use.name }}
            </span>
          </span>
          <span v-else-if="!item.deletable" class="attachment-row__meta">올린 지 7일이 지나면 지울 수 있습니다.</span>
        </div>
        <button v-if="item.deletable" type="button" class="button danger compact" @click="target = item">삭제</button>
      </li>
    </ul>
  </Drawer>

  <ConfirmDialog
    v-if="target"
    title="첨부 파일을 지울까요?"
    :subject="target.filename"
    detail=" 파일이 영구 삭제됩니다. 되돌릴 수 없습니다."
    aria-label="첨부 파일 삭제 확인"
    :busy="deleting"
    @confirm="remove"
    @cancel="target = null"
  />
</template>

<style scoped>
.attachment-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.attachment-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--radius); }
.attachment-row__main { display: grid; gap: 4px; min-width: 0; }
.attachment-row__main a { overflow-wrap: anywhere; font-weight: 600; }
.attachment-row__meta { color: var(--muted); font-size: 12px; }
.attachment-row__uses { display: flex; flex-wrap: wrap; gap: 4px; }
</style>
