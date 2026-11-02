<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import {
  ApiClientError,
  copyResume,
  createResume,
  deleteResume,
  getResume,
  listResumes,
  updateResume,
} from '../api/resumes'
import { listPositions } from '../api/positions'
import { listSelfIntroductions } from '../api/self-introductions'
import ConfirmDialog from './ConfirmDialog.vue'
import ResumeEditor from './ResumeEditor.vue'
import type { Resume, ResumePayload, ResumeSummary } from '../types/resume'
import type { Position } from '../types/position'
import type { SelfIntroduction } from '../types/self-introduction'

/**
 * /resumes 는 카드 목록, /resumes?focus=<id> 는 그 버전의 편집기.
 * 라우팅(URL)은 App 이 한다. 여기는 focus 를 받고 openResume/closeResume 를 올린다.
 */
const props = defineProps<{ focus: string | null }>()
const emit = defineEmits<{ openResume: [id: string]; closeResume: []; openIntroductions: [] }>()

const resumes = ref<ResumeSummary[]>([])
const current = ref<Resume | null>(null)
const positions = ref<Position[]>([])
/** 편집기를 열 때 이력서와 함께 받은 연결 문항. null 이면 문항 칸이 직접 불러온다. */
const questions = ref<SelfIntroduction[] | null>(null)
const loading = ref(true)
const loadError = ref('')
const notice = ref('')
const saving = ref(false)
const formErrors = ref<Record<string, string>>({})
const deleteTarget = ref<Resume | null>(null)
const deleting = ref(false)
const leaveConfirm = ref(false)

function fail(error: unknown, fallback: string) {
  if (error instanceof ApiClientError) {
    formErrors.value = error.fieldErrors
    if (Object.keys(error.fieldErrors).length === 0) loadError.value = error.message
  } else {
    loadError.value = fallback
  }
}

/** 마지막으로 보낸 조회의 순번. 이보다 앞선 조회의 응답은 늦게 와도 화면에 반영하지 않는다. */
let loadSeq = 0

async function loadList() {
  loadSeq++
  loading.value = true
  loadError.value = ''
  try {
    resumes.value = await listResumes()
  } catch (error) {
    fail(error, '이력서 목록을 불러오지 못했습니다.')
  } finally {
    loading.value = false
  }
}

async function loadCurrent(id: string) {
  const seq = ++loadSeq
  loading.value = true
  loadError.value = ''
  try {
    // 문항은 이력서 id 만 있으면 부를 수 있다. 편집기가 그려진 뒤에 부르면 왕복 하나(공개 도메인에서 약 0.3초)를
    // 더 기다리므로 여기서 함께 보낸다. 문항 실패가 이력서 열기를 막지 않게 null 로 넘기고, 그때는 문항 칸이
    // 스스로 다시 불러 오류를 보여 준다.
    const [resume, availablePositions, linkedQuestions] = await Promise.all([
      getResume(id),
      listPositions(),
      listSelfIntroductions('', id).catch(() => null),
    ])
    // A 를 연 뒤 B 를 열면 A 응답이 B 보다 늦게 올 수 있다. 그때 A 로 덮지 않는다.
    if (seq !== loadSeq) return
    current.value = resume
    positions.value = availablePositions
    questions.value = linkedQuestions
  } catch (error) {
    if (seq !== loadSeq) return
    current.value = null
    fail(error, '이력서를 불러오지 못했습니다.')
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function reload() {
  if (props.focus) loadCurrent(props.focus)
  else loadList()
}

async function create() {
  notice.value = ''
  try {
    const created = await createResume('새 이력서')
    emit('openResume', created.id)
  } catch (error) {
    fail(error, '이력서를 만들지 못했습니다.')
  }
}

async function save(payload: ResumePayload) {
  if (!current.value) return
  const seq = loadSeq
  saving.value = true
  formErrors.value = {}
  notice.value = ''
  try {
    const saved = await updateResume(current.value.id, payload)
    // 저장 중에 다른 이력서로 옮겼으면 그 화면을 덮지 않는다. 저장은 서버에서 끝났고, 돌아오면 다시 불러온다.
    if (seq !== loadSeq) return
    current.value = saved
    notice.value = '저장했습니다.'
  } catch (error) {
    if (seq !== loadSeq) return
    fail(error, '저장 중 오류가 발생했습니다.')
  } finally {
    saving.value = false
  }
}

async function copy() {
  if (!current.value) return
  saving.value = true
  notice.value = ''
  try {
    const copied = await copyResume(current.value.id, `${current.value.name} (복사)`)
    emit('openResume', copied.id)
  } catch (error) {
    fail(error, '복제하지 못했습니다.')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await deleteResume(deleteTarget.value.id)
    deleteTarget.value = null
    emit('closeResume')
  } catch (error) {
    deleteTarget.value = null
    fail(error, '삭제 중 오류가 발생했습니다.')
  } finally {
    deleting.value = false
  }
}

function back(dirty: boolean) {
  if (dirty) leaveConfirm.value = true
  else emit('closeResume')
}

function leave() {
  leaveConfirm.value = false
  emit('closeResume')
}

const formatDate = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))

watch(() => props.focus, () => {
  notice.value = ''
  formErrors.value = {}
  if (!props.focus) current.value = null
  reload()
})

onMounted(reload)
</script>

<template>
  <template v-if="props.focus === current?.id && current && !loading">
    <div v-if="notice || loadError" class="page page-notice">
      <div v-if="notice" class="notice success" role="status">{{ notice }}</div>
      <div v-if="loadError" class="notice error" role="alert">{{ loadError }}</div>
    </div>
    <ResumeEditor
      :resume="current"
      :saving="saving"
      :api-field-errors="formErrors"
      :positions="positions"
      :questions="questions"
      @save="save"
      @copy="copy"
      @remove="deleteTarget = current"
      @back="back"
      @open-introductions="emit('openIntroductions')"
    />
  </template>

  <main v-else class="page">
    <div class="page-head">
      <div class="page-title">
        <h1>이력서</h1>
        <span class="page-count">{{ resumes.length }}</span>
      </div>
      <button type="button" class="button primary" @click="create">이력서 추가</button>
    </div>

    <div v-if="notice" class="notice success" role="status">{{ notice }}</div>
    <div v-if="loadError" class="notice error" role="alert">
      <span>{{ loadError }}</span>
      <button type="button" @click="reload">다시 시도</button>
    </div>

    <section v-if="loading" class="loading-card" aria-live="polite">
      <span class="spinner" /> 불러오는 중입니다.
    </section>

    <section v-else-if="resumes.length === 0" class="empty-state">
      <strong>이력서가 없습니다.</strong>
      <p>버전마다 하나씩 만들어 두고 공고에 맞춰 골라 쓰세요.</p>
      <button type="button" class="button primary" @click="create">첫 이력서 만들기</button>
    </section>

    <section v-else class="card-grid">
      <button
        v-for="resume in resumes"
        :key="resume.id"
        type="button"
        class="card"
        @click="emit('openResume', resume.id)"
      >
        <span class="card__top">
          <span class="card__title">{{ resume.name }}</span>
        </span>
        <span class="card__sub">최근 수정 {{ formatDate(resume.updatedAt) }}</span>
      </button>
    </section>
  </main>

  <ConfirmDialog
    v-if="deleteTarget"
    title="이력서를 삭제할까요?"
    :subject="deleteTarget.name"
    detail=" 버전이 영구 삭제됩니다. 다른 버전은 그대로 남습니다."
    aria-label="이력서 삭제 확인"
    :busy="deleting"
    @confirm="remove"
    @cancel="deleteTarget = null"
  />

  <ConfirmDialog
    v-if="leaveConfirm"
    title="저장하지 않은 변경이 있습니다."
    subject="이 이력서"
    detail="의 변경 내용을 버리고 목록으로 돌아갈까요?"
    confirm-label="나가기"
    aria-label="이탈 확인"
    :busy="false"
    @confirm="leave"
    @cancel="leaveConfirm = false"
  />
</template>
