<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, useId, watch } from 'vue'
import {
  createSelfIntroduction,
  deleteSelfIntroduction,
  listSelfIntroductions,
  updateSelfIntroduction,
} from '../api/self-introductions'
import { listResumes } from '../api/resumes'
import type { ResumeSummary } from '../types/resume'
import type { SelfIntroduction, SelfIntroductionPayload } from '../types/self-introduction'
import { opensEditor } from '../utils/doubleClick'
import { highlightMatches } from '../utils/highlight'
import ConfirmDialog from './ConfirmDialog.vue'

const items = ref<SelfIntroduction[]>([])
const resumes = ref<ResumeSummary[]>([])
const query = ref('')
const activeQuery = ref('')
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const editingId = ref<string | null>(null)
const deleteTarget = ref<SelfIntroduction | null>(null)
const draft = reactive<SelfIntroductionPayload>({ resumeIds: [], question: '', answer: '' })
const resumeQuery = ref('')
const resumePicker = ref<HTMLElement | null>(null)
const resumeInput = ref<HTMLInputElement | null>(null)
const resumeMenu = ref<HTMLElement | null>(null)
const resumeMenuOpen = ref(false)
const resumeMenuMaxHeight = ref(320)
const activeResumeIndex = ref(-1)
const resumeListId = `intro-resumes-${useId()}`

const resumeNames = computed(() => new Map(resumes.value.map((resume) => [resume.id, resume.name])))
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
  Object.assign(draft, { resumeIds: resumes.value[0] ? [resumes.value[0].id] : [], question: '', answer: '' })
}

function openEdit(item: SelfIntroduction) {
  resetResumeSearch()
  editingId.value = item.id
  Object.assign(draft, { resumeIds: [...item.resumeIds], question: item.question, answer: item.answer ?? '' })
}

function editFromDoubleClick(event: MouseEvent, item: SelfIntroduction) {
  if (opensEditor(event)) openEdit(item)
}

function closeEditor() {
  resetResumeSearch()
  editingId.value = null
}

async function save() {
  if (!draft.resumeIds.length || !draft.question.trim()) return
  saving.value = true
  errorMessage.value = ''
  const payload = { ...draft, question: draft.question.trim() }
  try {
    if (editingId.value) await updateSelfIntroduction(editingId.value, payload)
    else await createSelfIntroduction(payload)
    closeEditor()
    await load()
  } catch (error) {
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
  try {
    resumes.value = await listResumes()
  } catch {
    resumes.value = []
  }
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
      <button type="button" class="button primary" :disabled="resumes.length === 0" @click="openNew">문항 추가</button>
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
    <div v-if="resumes.length === 0" class="notice">먼저 이력서를 만든 뒤 자기소개 문항을 추가할 수 있습니다.</div>

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
          <button type="submit" class="button primary" :disabled="saving || !draft.resumeIds.length || !draft.question.trim()">
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
