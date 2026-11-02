<script setup lang="ts">
import { ref } from 'vue'
import { ApiClientError, getWritingGuide, saveWritingGuide } from '../api/submissions'
import MarkdownTextarea from './MarkdownTextarea.vue'

/**
 * 계정당 작성 기준 하나(설계 6.7): 선호 문체, 피할 표현, 성과를 설명할 때 지킬 범위. AI 는 writing_guide_get 으로 읽기만 한다.
 * 펼칠 때 읽는다. 다른 곳에서 먼저 저장했으면(409) 새 내용을 다시 읽어 보여 준다 — 입력은 버린다.
 */
const open = ref(false)
const body = ref('')
const version = ref(0)
const loaded = ref(false)
const busy = ref(false)
const message = ref('')
const failed = ref(false)

async function load() {
  const guide = await getWritingGuide()
  body.value = guide.body
  version.value = guide.version
  loaded.value = true
}

async function toggle(event: Event) {
  open.value = (event.target as HTMLDetailsElement).open
  if (!open.value || loaded.value) return
  try {
    await load()
  } catch (e) {
    failed.value = true
    message.value = e instanceof ApiClientError ? e.message : '작성 기준을 불러오지 못했습니다.'
  }
}

async function save() {
  busy.value = true
  message.value = ''
  failed.value = false
  try {
    const saved = await saveWritingGuide(body.value, version.value)
    version.value = saved.version
    message.value = '저장했습니다.'
  } catch (e) {
    failed.value = true
    if (e instanceof ApiClientError && e.code === 'WRITING_GUIDE_STALE') {
      await load()
      message.value = '다른 곳에서 바뀌었습니다. 새 내용으로 다시 고치세요.'
    } else {
      message.value = e instanceof ApiClientError ? e.message : '저장하지 못했습니다.'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <details class="resume-section writing-guide" @toggle="toggle">
    <summary>작성 기준 <small>AI 가 자기소개를 쓸 때 따르는 내 규칙</small></summary>
    <form v-if="loaded" class="form-grid" @submit.prevent="save">
      <label class="field full">
        <span>선호 문체 · 피할 표현 · 성과 설명 범위 (Markdown)</span>
        <MarkdownTextarea v-model="body" maxlength="20000" rows="8" />
      </label>
      <div class="field full">
        <button type="submit" class="button primary compact" :disabled="busy">저장</button>
      </div>
    </form>
    <p v-if="message" class="notice" :class="{ error: failed }" role="status">{{ message }}</p>
  </details>
</template>
