<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ApiClientError, confirmSubmission, listSubmissions } from '../api/submissions'
import { listResumes } from '../api/resumes'
import { listSelfIntroductions } from '../api/self-introductions'
import type { ResumeSummary } from '../types/resume'
import type { SelfIntroduction } from '../types/self-introduction'
import type { Submission, SubmissionPayload } from '../types/submission'
import { submissionLevelLabels } from '../types/submission'
import AttachmentField from './AttachmentField.vue'

/**
 * 공고의 제출 기록(설계 6.6). LINKED 는 상태를 제출 완료로 바꿀 때 서버가 남긴 "기록 당시 연결 자료"이고,
 * 실제로 낸 것은 여기서 사용자가 골라 확인한다(USER_CONFIRMED). 접수 메일·번호·파일을 붙이면 RECEIPT_VERIFIED.
 * revision 이 바뀌면(공고 저장·상태 변경) 목록을 다시 읽는다.
 */
const props = defineProps<{ postingId: string; revision: string }>()

const records = ref<Submission[]>([])
const error = ref('')
const editing = ref(false)
const busy = ref(false)
const resumes = ref<ResumeSummary[]>([])
const intros = ref<SelfIntroduction[]>([])
const draft = reactive({
  level: 'USER_CONFIRMED' as SubmissionPayload['level'],
  resumeIds: [] as string[],
  selfIntroIds: [] as string[],
  receiptExcerpt: '',
  receiptNumber: '',
  receiptFile: '',
})

const message = (e: unknown, fallback: string) => (e instanceof ApiClientError ? e.message : fallback)

/** 요청마다 번호를 올려, 늦게 도착한 이전 공고·이전 revision 의 응답은 버린다. */
let seq = 0

async function load() {
  const mine = ++seq
  error.value = ''
  try {
    const value = await listSubmissions(props.postingId)
    if (mine === seq) records.value = value
  } catch (e) {
    if (mine === seq) error.value = message(e, '제출 기록을 불러오지 못했습니다.')
  }
}
watch(() => [props.postingId, props.revision], load, { immediate: true })
// 다른 공고로 바뀌면 이전 공고용으로 고르던 선택을 닫는다 — 그대로 두면 새 공고에 확인 기록으로 저장된다.
watch(() => props.postingId, () => {
  editing.value = false
  records.value = []
})

/** 가장 최근 기록의 문서를 처음 선택으로 둔다 — 보통 LINKED 의 연결 자료가 실제로 낸 것과 같다. */
async function startConfirm() {
  const postingId = props.postingId
  error.value = ''
  try {
    ;[resumes.value, intros.value] = await Promise.all([listResumes(), listSelfIntroductions()])
  } catch (e) {
    error.value = message(e, '이력서·자기소개 목록을 불러오지 못했습니다.')
    return
  }
  if (postingId !== props.postingId) return
  const latest = records.value[0]
  Object.assign(draft, {
    level: 'USER_CONFIRMED',
    resumeIds: latest ? latest.resumes.map((r) => r.resumeId) : [],
    selfIntroIds: latest ? latest.selfIntros.map((s) => s.id) : [],
    receiptExcerpt: '',
    receiptNumber: '',
    receiptFile: '',
  })
  editing.value = true
}

const canSave = computed(() => draft.resumeIds.length + draft.selfIntroIds.length > 0
  && (draft.level === 'USER_CONFIRMED' || !!(draft.receiptExcerpt.trim() || draft.receiptNumber.trim() || draft.receiptFile)))

async function save() {
  busy.value = true
  error.value = ''
  const receipt = draft.level === 'RECEIPT_VERIFIED'
  try {
    await confirmSubmission(props.postingId, {
      level: draft.level,
      resumeIds: draft.resumeIds,
      selfIntroIds: draft.selfIntroIds,
      receiptExcerpt: receipt ? draft.receiptExcerpt.trim() || null : null,
      receiptNumber: receipt ? draft.receiptNumber.trim() || null : null,
      receiptAttachmentIds: receipt && draft.receiptFile ? [draft.receiptFile] : [],
    })
    editing.value = false
    await load()
  } catch (e) {
    error.value = message(e, '제출본을 확인하지 못했습니다.')
  } finally {
    busy.value = false
  }
}

const formatDate = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
</script>

<template>
  <section class="detail-section submission-records">
    <p class="label">
      제출 기록 · {{ records.length }}
      <small>제출 완료로 바꾸면 그때 연결된 자료가 남습니다. 실제로 낸 것은 직접 확인하세요.</small>
    </p>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <ul v-if="records.length" class="mini-list">
      <li v-for="record in records" :key="record.id" class="mini-row">
        <span class="mini-row__text">
          <strong>{{ submissionLevelLabels[record.level] }}</strong>
          <span>
            {{ formatDate(record.recordedAt) }}
            · 이력서 {{ record.resumes.map((r) => r.name).join(', ') || '없음' }}
            · 자기소개 {{ record.selfIntros.length }}
            <template v-if="record.attachmentIds.length"> · 파일 {{ record.attachmentIds.length }}</template>
            <template v-if="record.receipt?.receiptNumber"> · 접수 번호 {{ record.receipt.receiptNumber }}</template>
          </span>
        </span>
        <span class="chip" :data-tone="record.level === 'LINKED' ? undefined : 'primary'">{{ record.level === 'LINKED' ? '미확인' : '확인' }}</span>
      </li>
    </ul>
    <p v-else class="body-copy empty">아직 제출 기록이 없습니다.</p>

    <button v-if="!editing" type="button" class="button secondary compact" @click="startConfirm">실제로 낸 것 확인</button>
    <form v-else class="form-grid" aria-label="제출본 확인" @submit.prevent="save">
      <fieldset class="field full">
        <legend>확인 수준</legend>
        <label><input v-model="draft.level" type="radio" value="USER_CONFIRMED" /> 내가 확인한 제출본</label>
        <label><input v-model="draft.level" type="radio" value="RECEIPT_VERIFIED" /> 접수 근거를 붙인 제출본</label>
      </fieldset>
      <fieldset class="field full">
        <legend>낸 이력서</legend>
        <label v-for="resume in resumes" :key="resume.id">
          <input v-model="draft.resumeIds" type="checkbox" :value="resume.id" /> {{ resume.name }}
        </label>
      </fieldset>
      <fieldset class="field full">
        <legend>낸 자기소개</legend>
        <label v-for="intro in intros" :key="intro.id">
          <input v-model="draft.selfIntroIds" type="checkbox" :value="intro.id" /> {{ intro.question }}
        </label>
      </fieldset>
      <template v-if="draft.level === 'RECEIPT_VERIFIED'">
        <label class="field full">
          <span>접수 메일 발췌</span>
          <textarea v-model="draft.receiptExcerpt" maxlength="2000" rows="3" />
        </label>
        <label class="field">
          <span>접수 번호</span>
          <input v-model="draft.receiptNumber" maxlength="100" />
        </label>
        <AttachmentField v-model:id="draft.receiptFile" label="접수 근거 파일" />
      </template>
      <div class="field full form-actions">
        <button type="button" class="button ghost compact" @click="editing = false">취소</button>
        <button type="submit" class="button primary compact" :disabled="busy || !canSave">확인 기록</button>
      </div>
    </form>
  </section>
</template>
