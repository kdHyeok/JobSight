<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, useId, watch } from 'vue'
import {
  confirmSelfIntroduction,
  createSelfIntroduction,
  deleteSelfIntroduction,
  listSelfIntroductions,
  updateSelfIntroduction,
} from '../api/self-introductions'
import { ApiClientError } from '../api/http'
import { listResumes } from '../api/resumes'
import { listPositions } from '../api/positions'
import { listExperiences } from '../api/experiences'
import type { ExperienceLine } from '../types/experience'
import type { Position } from '../types/position'
import type { ResumeSummary } from '../types/resume'
import {
  competencyLabels,
  type Competency,
  type LinkSource,
  type SelfIntroduction,
  type SelfIntroductionConfirmPayload,
  type SelfIntroductionPayload,
} from '../types/self-introduction'
import { opensEditor } from '../utils/doubleClick'
import { highlightMatches } from '../utils/highlight'
import ConfirmDialog from './ConfirmDialog.vue'

const items = ref<SelfIntroduction[]>([])
const resumes = ref<ResumeSummary[]>([])
const positions = ref<Position[]>([])
/** 쓴 경험 선택지(경험 원본 목차). 못 불러와도 문항 화면은 연다. */
const experienceLines = ref<ExperienceLine[]>([])
const query = ref('')
const activeQuery = ref('')
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const editingId = ref<string | null>(null)
/**
 * 편집기를 연 때 읽은 version. 저장의 expectedVersion 이다(불변 조건 12). 확정(✓)이 올린 값은 이어받지만,
 * 409 뒤에 목록을 다시 불러와도 바꾸지 않는다 — 바뀐 내용을 확인하지 않은 초안으로 덮어쓰지 않게 다시 열어야 한다.
 */
const editingVersion = ref(0)
const deleteTarget = ref<SelfIntroduction | null>(null)
const draft = reactive<Required<SelfIntroductionPayload>>({ resumeIds: [], question: '', answer: '', positionIds: [], questionTypes: [], experiences: [] })
const resumeQuery = ref('')
const resumePicker = ref<HTMLElement | null>(null)
const resumeInput = ref<HTMLInputElement | null>(null)
const resumeMenu = ref<HTMLElement | null>(null)
const resumeMenuOpen = ref(false)
const resumeMenuMaxHeight = ref(320)
const activeResumeIndex = ref(-1)
const resumeListId = `intro-resumes-${useId()}`

const resumeNames = computed(() => new Map(resumes.value.map((resume) => [resume.id, resume.name])))
const positionById = computed(() => new Map(positions.value.map((position) => [position.id, position])))
const competencies = Object.keys(competencyLabels) as Competency[]
/** 저장된 연결의 출처는 편집 중인 문항(서버 값)에서 읽는다. 초안에서 새로 고른 연결은 저장할 때 USER 가 된다. */
const editingItem = computed(() => items.value.find((item) => item.id === editingId.value))
const selectablePositions = computed(() => positions.value.filter((position) => !draft.positionIds.includes(position.id)))
const selectableTypes = computed(() => competencies.filter((code) => !draft.questionTypes.includes(code)))
/** 직접 출처가 없는 답변의 "어느 지원용?" 후보: 연결 이력서들의 직무 전부(설계 3절). 개수를 자르지 않는다. */
const candidatePositions = computed(() => {
  if (draft.positionIds.length) return []
  const ids = new Set(draft.resumeIds.flatMap((id) => resumes.value.find((resume) => resume.id === id)?.positionIds ?? []))
  return [...ids].map((id) => positionById.value.get(id)).filter((position): position is Position => !!position)
})
/** 생성은 이력서 또는 직무 1개 이상, 수정은 0개도 된다 — 연결이 모두 사라진 문항도 고칠 수 있어야 한다. */
const canSave = computed(() => !!draft.question.trim()
  && (!!editingId.value || draft.resumeIds.length > 0 || draft.positionIds.length > 0))

/** 회사 · 공고명 · 직무명. 같은 회사의 다른 공고에 같은 이름 직무가 있어도 고를 수 있게 공고명을 넣는다(없거나 직무명과 같으면 뺀다). */
function positionLabel(id: string) {
  const position = positionById.value.get(id)
  if (!position) return '삭제된 직무'
  const posting = position.postingTitle && position.postingTitle !== position.name ? position.postingTitle : null
  return [position.companyName || '회사 미입력', posting, position.name].filter(Boolean).join(' · ')
}
function positionSource(id: string): LinkSource | undefined {
  return editingItem.value?.positionLinks?.find((link) => link.positionId === id)?.source
}
function typeSource(code: Competency): LinkSource | undefined {
  return editingItem.value?.questionTypes?.find((link) => link.code === code)?.source
}
/** select 는 고르는 즉시 초안에 넣고 다시 비운다. */
function picked(event: Event) {
  const select = event.target as HTMLSelectElement
  const value = select.value
  select.value = ''
  return value
}
function addPosition(id: string) {
  if (id && !draft.positionIds.includes(id)) draft.positionIds = [...draft.positionIds, id]
}
function removePosition(id: string) {
  draft.positionIds = draft.positionIds.filter((value) => value !== id)
}
function addType(code: string) {
  const value = code as Competency
  if (competencies.includes(value) && draft.questionTypes.length < 3 && !draft.questionTypes.includes(value)) {
    draft.questionTypes = [...draft.questionTypes, value]
  }
}
function removeType(code: Competency) {
  draft.questionTypes = draft.questionTypes.filter((value) => value !== code)
}
/**
 * "쓴 경험"(작성 근거, 설계 6.8). 새로 고른 경험은 그 경험의 지금 개정과 짝을 짓는다 — 이 답변을 쓸 때 본 개정이다.
 * 이미 고른 경험의 개정은 바꾸지 않는다(저장된 쌍 그대로).
 */
const experienceById = computed(() => new Map(experienceLines.value.map((line) => [line.id, line])))
const selectableExperiences = computed(() => experienceLines.value
  .filter((line) => !draft.experiences.some((ref) => ref.experienceId === line.id)))
function addExperience(id: string) {
  const line = experienceById.value.get(id)
  if (line && draft.experiences.length < 10 && !draft.experiences.some((ref) => ref.experienceId === id)) {
    draft.experiences = [...draft.experiences, { experienceId: id, experienceRevision: line.revision }]
  }
}
function removeExperience(id: string) {
  draft.experiences = draft.experiences.filter((ref) => ref.experienceId !== id)
}
function experienceSource(id: string): LinkSource | undefined {
  return editingItem.value?.experiences?.find((link) => link.experienceId === id)?.source
}
/** 웹의 ✓. 초안이 아니라 바로 확정한다 — 확정은 웹에서만 하는 별도 동작이다(설계 3절 규칙 3). */
async function confirmLink(payload: SelfIntroductionConfirmPayload) {
  if (!editingId.value) return
  errorMessage.value = ''
  try {
    const updated = await confirmSelfIntroduction(editingId.value, payload)
    items.value = items.value.map((item) => (item.id === updated.id ? updated : item))
    if (editingId.value === updated.id) editingVersion.value = updated.version
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'AI 기록을 확정하지 못했습니다.'
  }
}
const selectedResumes = computed(() => draft.resumeIds.map((id) => ({ id, name: resumeNames.value.get(id) ?? '삭제된 이력서' })))
const filteredResumes = computed(() => {
  const needle = resumeQuery.value.trim().toLowerCase()
  return resumes.value
    .filter((resume) => !draft.resumeIds.includes(resume.id))
    .filter((resume) => !needle || resume.name.toLowerCase().includes(needle))
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
})
const activeResumeId = computed(() => resumeMenuOpen.value && filteredResumes.value[activeResumeIndex.value]
  ? `${resumeListId}-${activeResumeIndex.value}` : undefined)
watch(filteredResumes, () => {
  activeResumeIndex.value = -1
  if (resumeMenu.value) resumeMenu.value.scrollTop = 0
})
function fitResumeMenu() {
  if (!resumeMenuOpen.value || !resumeInput.value) return
  const viewport = window.visualViewport
  const height = viewport?.height ?? window.innerHeight
  const spaceBelow = (viewport?.offsetTop ?? 0) + height - resumeInput.value.getBoundingClientRect().bottom - 12
  resumeMenuMaxHeight.value = Math.max(48, Math.min(320, height * 0.45, spaceBelow))
}
watch(resumeMenuOpen, async (open) => {
  if (!open) return
  await nextTick()
  if (window.innerHeight - (resumeInput.value?.getBoundingClientRect().bottom ?? 0) < 80) {
    resumeInput.value?.scrollIntoView?.({ block: 'center' })
  }
  fitResumeMenu()
})

function closeResumeMenu() {
  resumeMenuOpen.value = false
  activeResumeIndex.value = -1
}
function resetResumeSearch() {
  resumeQuery.value = ''
  closeResumeMenu()
}
function dismissResumeMenu(event: Event) {
  if (event.target instanceof Node && !resumePicker.value?.contains(event.target)) closeResumeMenu()
}
function addResume(id: string) {
  if (!draft.resumeIds.includes(id)) draft.resumeIds = [...draft.resumeIds, id]
  resumeQuery.value = ''
  activeResumeIndex.value = -1
  resumeMenuOpen.value = true
  resumeInput.value?.focus()
}
function removeResume(id: string) {
  draft.resumeIds = draft.resumeIds.filter((value) => value !== id)
}
async function onResumeKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'Escape') {
    if (resumeMenuOpen.value) { event.preventDefault(); event.stopPropagation() }
    closeResumeMenu()
  } else if (event.key === 'Tab') {
    closeResumeMenu()
  } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    resumeMenuOpen.value = true
    const count = filteredResumes.value.length
    if (!count) return
    const step = event.key === 'ArrowDown' ? 1 : -1
    activeResumeIndex.value = activeResumeIndex.value < 0
      ? (step === 1 ? 0 : count - 1)
      : Math.max(0, Math.min(count - 1, activeResumeIndex.value + step))
    await nextTick()
    document.getElementById(activeResumeId.value ?? '')?.scrollIntoView?.({ block: 'nearest' })
  } else if (event.key === 'Enter') {
    // 이 입력은 문항 폼 안에 있다. 활성 항목이 없어도 검색 Enter로 폼을 저장하지 않는다.
    event.preventDefault()
    const resume = resumeMenuOpen.value ? filteredResumes.value[activeResumeIndex.value] : undefined
    if (resume) addResume(resume.id)
    else resumeMenuOpen.value = true
  }
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  activeQuery.value = query.value.trim()
  try {
    items.value = await listSelfIntroductions(activeQuery.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '자기소개 문항을 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

function openNew() {
  resetResumeSearch()
  editingId.value = ''
  Object.assign(draft, { resumeIds: resumes.value[0] ? [resumes.value[0].id] : [], question: '', answer: '', positionIds: [], questionTypes: [], experiences: [] })
}

function openEdit(item: SelfIntroduction) {
  resetResumeSearch()
  editingId.value = item.id
  editingVersion.value = item.version
  Object.assign(draft, {
    resumeIds: [...item.resumeIds],
    question: item.question,
    answer: item.answer ?? '',
    positionIds: (item.positionLinks ?? []).map((link) => link.positionId),
    questionTypes: (item.questionTypes ?? []).map((link) => link.code),
    // 저장된 (경험, 개정) 쌍을 그대로 들고 간다. 지금 개정으로 바꾸지 않는다(설계 6.8).
    experiences: (item.experiences ?? []).map(({ experienceId, experienceRevision }) => ({ experienceId, experienceRevision })),
  })
}

function editFromDoubleClick(event: MouseEvent, item: SelfIntroduction) {
  if (opensEditor(event)) openEdit(item)
}

function closeEditor() {
  resetResumeSearch()
  editingId.value = null
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  errorMessage.value = ''
  const payload: SelfIntroductionPayload = { ...draft, question: draft.question.trim() }
  try {
    if (editingId.value) await updateSelfIntroduction(editingId.value, { ...payload, expectedVersion: editingVersion.value })
    else await createSelfIntroduction(payload)
    closeEditor()
    await load()
  } catch (error) {
    // 409(다른 곳에서 먼저 저장·저장 처리 중): 초안은 남기고 목록만 새로 읽어 바뀐 내용을 보여 준다.
    if (error instanceof ApiClientError && error.status === 409) await load()
    errorMessage.value = error instanceof Error ? error.message : '자기소개 문항을 저장하지 못했습니다.'
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!deleteTarget.value) return
  saving.value = true
  try {
    await deleteSelfIntroduction(deleteTarget.value.id)
    deleteTarget.value = null
    await load()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '자기소개 문항을 삭제하지 못했습니다.'
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  document.addEventListener('pointerdown', dismissResumeMenu)
  document.addEventListener('focusin', dismissResumeMenu)
  window.addEventListener('resize', fitResumeMenu)
  window.visualViewport?.addEventListener('resize', fitResumeMenu)
  window.visualViewport?.addEventListener('scroll', fitResumeMenu)
  // 직무는 선택지·후보 칩·카드 이름에만 쓴다. 못 불러와도 문항 화면은 연다.
  const [loadedResumes, loadedPositions, loadedExperiences] = await Promise.allSettled([listResumes(), listPositions(), listExperiences()])
  resumes.value = loadedResumes.status === 'fulfilled' ? loadedResumes.value : []
  positions.value = loadedPositions.status === 'fulfilled' ? loadedPositions.value : []
  experienceLines.value = loadedExperiences.status === 'fulfilled' ? loadedExperiences.value.experiences : []
  await load()
})
onUnmounted(() => {
  document.removeEventListener('pointerdown', dismissResumeMenu)
  document.removeEventListener('focusin', dismissResumeMenu)
  window.removeEventListener('resize', fitResumeMenu)
  window.visualViewport?.removeEventListener('resize', fitResumeMenu)
  window.visualViewport?.removeEventListener('scroll', fitResumeMenu)
})
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div class="page-title">
        <h1>자기소개</h1>
        <span class="page-count">{{ items.length }}</span>
      </div>
      <button type="button" class="button primary" :disabled="resumes.length === 0 && positions.length === 0" @click="openNew">문항 추가</button>
    </div>

    <form class="intro-search" role="search" @submit.prevent="load">
      <label class="field full">
        <span class="sr-only">질문과 답변 검색</span>
        <input v-model="query" maxlength="100" placeholder="질문과 답변에서 같은 단어 검색" />
      </label>
      <button type="submit" class="button secondary">검색</button>
      <button v-if="activeQuery" type="button" class="button ghost" @click="query = ''; load()">초기화</button>
    </form>

    <div v-if="errorMessage" class="notice error" role="alert">{{ errorMessage }}</div>
    <div v-if="resumes.length === 0 && positions.length === 0" class="notice">먼저 이력서나 지원 직무를 만든 뒤 자기소개 문항을 추가할 수 있습니다.</div>

    <section v-if="editingId !== null" class="resume-section intro-editor">
      <div class="resume-section__head"><h2>{{ editingId ? '문항 수정' : '문항 추가' }}</h2></div>
      <form class="form-grid" @submit.prevent="save">
        <fieldset class="field full resume-links">
          <legend>연결 이력서 <span class="page-count">{{ draft.resumeIds.length }}</span></legend>
          <div ref="resumePicker" class="resume-link-search">
            <div class="resume-search-control">
              <input
                ref="resumeInput"
                v-model="resumeQuery"
                type="search"
                placeholder="이력서 이름 검색"
                aria-label="연결 이력서 검색"
                role="combobox"
                aria-autocomplete="list"
                :aria-expanded="resumeMenuOpen"
                :aria-controls="resumeMenuOpen ? resumeListId : undefined"
                :aria-activedescendant="activeResumeId"
                autocomplete="off"
                @focus="resumeMenuOpen = true"
                @click="resumeMenuOpen = true"
                @input="resumeMenuOpen = true"
                @keydown="onResumeKeydown"
              />
              <span class="resume-search-caret" :class="{ 'is-open': resumeMenuOpen }" aria-hidden="true">▾</span>
            </div>
            <div v-if="resumeMenuOpen" :id="resumeListId" ref="resumeMenu" class="resume-picker" :style="{ maxHeight: `${resumeMenuMaxHeight}px` }" role="listbox" aria-label="연결할 이력서" aria-multiselectable="true">
              <button
                v-for="(resume, index) in filteredResumes"
                :id="`${resumeListId}-${index}`"
                :key="resume.id"
                type="button"
                class="resume-option"
                :class="{ active: index === activeResumeIndex }"
                role="option"
                aria-selected="false"
                tabindex="-1"
                :title="resume.name"
                @mousedown.prevent
                @click="addResume(resume.id)"
              ><span><strong>{{ resume.name }}</strong></span></button>
              <p v-if="!filteredResumes.length" class="body-copy empty resume-picker__empty" role="status">{{ !resumes.length ? '연결할 이력서가 없습니다.' : resumeQuery.trim() ? '검색 결과가 없습니다.' : '추가할 이력서가 없습니다.' }}</p>
            </div>
          </div>
          <div v-if="selectedResumes.length" class="selected-resumes">
            <button v-for="resume in selectedResumes" :key="resume.id" type="button" class="chip" :aria-label="`${resume.name} 연결 해제`" :title="resume.name" @click="removeResume(resume.id)"><span>{{ resume.name }}</span> ×</button>
          </div>
        </fieldset>
        <fieldset class="field full intro-links">
          <legend>지원 직무 <span class="page-count">{{ draft.positionIds.length }}</span></legend>
          <div v-if="draft.positionIds.length" class="link-chips">
            <span v-for="id in draft.positionIds" :key="id" class="chip" :data-tone="positionSource(id) === 'AI' ? 'warning' : undefined" :title="positionLabel(id)">
              <span>{{ positionLabel(id) }}</span>
              <small v-if="positionSource(id) === 'AI'">AI 기록</small>
              <button v-if="positionSource(id) === 'AI'" type="button" class="chip-action" :aria-label="`${positionLabel(id)} 확정`" @click="confirmLink({ positionIds: [id] })">✓</button>
              <button type="button" class="chip-action" :aria-label="`${positionLabel(id)} 연결 해제`" @click="removePosition(id)">{{ positionSource(id) === 'AI' ? '✗' : '×' }}</button>
            </span>
          </div>
          <div v-else-if="candidatePositions.length" class="link-candidates">
            <span class="body-copy">어느 지원용?</span>
            <button v-for="position in candidatePositions" :key="position.id" type="button" class="chip" @click="addPosition(position.id)">+ {{ positionLabel(position.id) }}</button>
          </div>
          <select aria-label="지원 직무 추가" :disabled="draft.positionIds.length >= 10 || !selectablePositions.length" @change="addPosition(picked($event))">
            <option value="">{{ positions.length ? '직무 추가…' : '등록된 직무가 없습니다' }}</option>
            <option v-for="position in selectablePositions" :key="position.id" :value="position.id">{{ positionLabel(position.id) }}</option>
          </select>
        </fieldset>
        <fieldset class="field full intro-links">
          <legend>문항 유형 <span class="page-count">{{ draft.questionTypes.length }}</span></legend>
          <div v-if="draft.questionTypes.length" class="link-chips">
            <span v-for="code in draft.questionTypes" :key="code" class="chip" :data-tone="typeSource(code) === 'AI' ? 'warning' : undefined">
              <span>{{ competencyLabels[code] }}</span>
              <small v-if="typeSource(code) === 'AI'">AI 기록</small>
              <button v-if="typeSource(code) === 'AI'" type="button" class="chip-action" :aria-label="`${competencyLabels[code]} 확정`" @click="confirmLink({ questionTypes: [code] })">✓</button>
              <button type="button" class="chip-action" :aria-label="`${competencyLabels[code]} 연결 해제`" @click="removeType(code)">{{ typeSource(code) === 'AI' ? '✗' : '×' }}</button>
            </span>
          </div>
          <select aria-label="문항 유형 추가" :disabled="draft.questionTypes.length >= 3" @change="addType(picked($event))">
            <option value="">유형 추가…(3개까지)</option>
            <option v-for="code in selectableTypes" :key="code" :value="code">{{ competencyLabels[code] }}</option>
          </select>
        </fieldset>
        <fieldset class="field full intro-links">
          <legend>쓴 경험 <span class="page-count">{{ draft.experiences.length }}</span></legend>
          <div v-if="draft.experiences.length" class="link-chips">
            <span v-for="ref in draft.experiences" :key="ref.experienceId" class="chip" :data-tone="experienceSource(ref.experienceId) === 'AI' ? 'warning' : undefined">
              <span>{{ experienceById.get(ref.experienceId)?.title ?? '삭제된 경험' }} · 개정 {{ ref.experienceRevision }}</span>
              <small v-if="experienceSource(ref.experienceId) === 'AI'">AI 기록</small>
              <button type="button" class="chip-action" :aria-label="`${experienceById.get(ref.experienceId)?.title ?? '경험'} 근거 해제`" @click="removeExperience(ref.experienceId)">×</button>
            </span>
          </div>
          <select aria-label="쓴 경험 추가" :disabled="draft.experiences.length >= 10 || !selectableExperiences.length" @change="addExperience(picked($event))">
            <option value="">{{ experienceLines.length ? '이 답변에 쓴 경험 추가…' : '등록된 경험이 없습니다' }}</option>
            <option v-for="line in selectableExperiences" :key="line.id" :value="line.id">{{ line.title }}{{ line.period ? ` (${line.period})` : '' }}</option>
          </select>
        </fieldset>
        <label class="field full">
          <span>질문</span>
          <textarea v-model="draft.question" maxlength="1000" rows="3" required />
        </label>
        <label class="field full">
          <span>답변</span>
          <textarea v-model="draft.answer" maxlength="10000" rows="10" placeholder="답변을 작성하세요." />
        </label>
        <div class="full page-tools">
          <button type="button" class="button secondary" @click="closeEditor">취소</button>
          <button type="submit" class="button primary" :disabled="saving || !canSave">
            {{ saving ? '저장 중…' : '저장' }}
          </button>
        </div>
      </form>
    </section>

    <section v-if="loading" class="loading-card" aria-live="polite"><span class="spinner" /> 검색 중입니다.</section>
    <section v-else-if="items.length === 0" class="empty-state">
      <strong>{{ activeQuery ? '검색 결과가 없습니다.' : '자기소개 문항이 없습니다.' }}</strong>
      <p>{{ activeQuery ? '다른 단어로 검색해 보세요.' : '이력서에서 질문을 추가하거나 여기서 새로 만드세요.' }}</p>
    </section>
    <section v-else class="intro-list" aria-label="자기소개 문항 목록">
      <article
        v-for="item in items"
        :key="item.id"
        class="intro-card"
        title="더블클릭해 자기소개 문항 수정"
        @dblclick="editFromDoubleClick($event, item)"
      >
        <div class="intro-card__head">
          <span class="card__chips">
            <span v-for="resumeId in item.resumeIds" :key="resumeId" class="chip">{{ resumeNames.get(resumeId) ?? '삭제된 이력서' }}</span>
            <span v-for="link in item.positionLinks ?? []" :key="link.positionId" class="chip" :data-tone="link.source === 'AI' ? 'warning' : 'primary'">{{ positionLabel(link.positionId) }}{{ link.source === 'AI' ? ' · AI 기록' : '' }}</span>
          </span>
          <div class="page-tools">
            <button type="button" class="button ghost compact" @click="openEdit(item)">수정</button>
            <button type="button" class="button danger compact" @click="deleteTarget = item">삭제</button>
          </div>
        </div>
        <h2 class="search-highlight">
          <template v-for="(segment, index) in highlightMatches(item.question, activeQuery)" :key="index">
            <mark v-if="segment.matched">{{ segment.text }}</mark><template v-else>{{ segment.text }}</template>
          </template>
        </h2>
        <p v-if="item.answer" class="body-copy search-highlight">
          <template v-for="(segment, index) in highlightMatches(item.answer, activeQuery)" :key="index">
            <mark v-if="segment.matched">{{ segment.text }}</mark><template v-else>{{ segment.text }}</template>
          </template>
        </p>
        <p v-else class="body-copy empty">아직 답변이 없습니다.</p>
      </article>
    </section>

    <ConfirmDialog
      v-if="deleteTarget"
      title="자기소개 문항을 삭제할까요?"
      :subject="deleteTarget.question"
      detail=" 문항과 답변이 영구 삭제됩니다."
      aria-label="자기소개 문항 삭제 확인"
      :busy="saving"
      @confirm="remove"
      @cancel="deleteTarget = null"
    />
  </main>
</template>
