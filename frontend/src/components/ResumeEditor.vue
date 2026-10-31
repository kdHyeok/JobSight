<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue'
import type { Position } from '../types/position'
import AttachmentField from './AttachmentField.vue'
import ResumeSection from './ResumeSection.vue'
import ResumeQuestions from './ResumeQuestions.vue'
import type { Resume, ResumeContent, ResumePayload, Row, SectionKey } from '../types/resume'
import { BASIC_FIELDS, MAX_LENGTH, SECTIONS, fillContent, text } from '../types/resume'
import { downloadAttachments, downloadWord } from '../utils/resume-export'

/**
 * 이력서 한 버전의 전체 페이지 편집기. 폼이 커서 드로어(520px)에 넣지 않는다 — design-system 규칙 7.
 * 상태는 여기(draft)에 두고 저장 버튼에서 한 번에 PUT 한다.
 */
const props = defineProps<{
  resume: Resume
  saving: boolean
  apiFieldErrors: Record<string, string>
  positions: Position[]
}>()

const emit = defineEmits<{
  save: [payload: ResumePayload]
  copy: []
  remove: []
  back: [dirty: boolean]
  openIntroductions: []
}>()

const name = ref(props.resume.name)
const content = ref<ResumeContent>(fillContent(props.resume.content))
const positionIds = ref<string[]>([...props.resume.positionIds])
const positionQuery = ref('')
const positionPicker = ref<HTMLElement | null>(null)
const positionInput = ref<HTMLInputElement | null>(null)
const positionMenu = ref<HTMLElement | null>(null)
const positionMenuOpen = ref(false)
const activePositionIndex = ref(-1)
const positionListId = `resume-positions-${useId()}`
const exportOpen = ref(false)
const attachmentIds = ref<string[]>([])
const exportError = ref('')

watch(() => props.resume, (resume) => {
  name.value = resume.name
  content.value = fillContent(resume.content)
  positionIds.value = [...resume.positionIds]
  positionQuery.value = ''
  closePositionMenu()
})

/** 저장한 시점의 문서와 깊은 비교. 입력마다 새 객체를 만드니 JSON 비교가 정직하다. */
const dirty = computed(() =>
  name.value !== props.resume.name
  || JSON.stringify(content.value) !== JSON.stringify(fillContent(props.resume.content))
  || JSON.stringify([...positionIds.value].sort()) !== JSON.stringify([...props.resume.positionIds].sort()),
)

// 저장 안 한 변경이 있으면 탭 닫기·새로고침에 경고. 한 줄이면 되는 일을 라이브러리로 하지 않는다.
const guard = (event: BeforeUnloadEvent) => { event.preventDefault() }
watch(dirty, (isDirty) => {
  if (isDirty) window.addEventListener('beforeunload', guard)
  else window.removeEventListener('beforeunload', guard)
}, { immediate: true })
onMounted(() => {
  document.addEventListener('pointerdown', dismissPositionMenu)
  document.addEventListener('focusin', dismissPositionMenu)
})
onUnmounted(() => {
  window.removeEventListener('beforeunload', guard)
  document.removeEventListener('pointerdown', dismissPositionMenu)
  document.removeEventListener('focusin', dismissPositionMenu)
})

function setBasic(key: string, value: string) {
  content.value = { ...content.value, basic: { ...content.value.basic, [key]: value } }
}
function setRows(key: SectionKey, rows: Row[]) {
  content.value = { ...content.value, [key]: rows }
}

function submit() {
  if (!name.value.trim()) return
  emit('save', { name: name.value.trim(), content: content.value, positionIds: [...positionIds.value] })
}

const basicValue = (key: string) => content.value.basic[key as keyof typeof content.value.basic] ?? ''
const selectedPositions = computed(() => props.positions.filter((item) => positionIds.value.includes(item.id)))
const positionMatches = computed(() => {
  const query = positionQuery.value.trim().toLocaleLowerCase()
  return props.positions
    .filter((item) => !positionIds.value.includes(item.id))
    .filter((item) => !query || `${item.name} ${item.companyName ?? ''} ${item.postingTitle ?? ''}`.toLocaleLowerCase().includes(query))
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
})
const activePositionId = computed(() => positionMenuOpen.value && positionMatches.value[activePositionIndex.value]
  ? `${positionListId}-${activePositionIndex.value}` : undefined)
watch(positionMatches, () => {
  activePositionIndex.value = -1
  if (positionMenu.value) positionMenu.value.scrollTop = 0
})
const attachmentCandidates = computed(() => {
  const found: { id: string; label: string }[] = []
  if (content.value.basic.portfolioFileId) found.push({ id: content.value.basic.portfolioFileId, label: '기본정보 · 포트폴리오 파일' })
  for (const section of SECTIONS) content.value[section.key].forEach((row, index) => section.fields.filter((field) => field.kind === 'file').forEach((field) => {
    const id = text(row, field.key)
    if (id) found.push({ id, label: `${section.label} ${index + 1} · ${field.label}` })
  }))
  return found.filter((item, index) => found.findIndex((other) => other.id === item.id) === index)
})

function addPosition(id: string) {
  if (!positionIds.value.includes(id)) positionIds.value = [...positionIds.value, id]
  positionQuery.value = ''
  activePositionIndex.value = -1
  positionMenuOpen.value = true
  positionInput.value?.focus()
}
function removePosition(id: string) {
  positionIds.value = positionIds.value.filter((value) => value !== id)
}
function closePositionMenu() {
  positionMenuOpen.value = false
  activePositionIndex.value = -1
}
function dismissPositionMenu(event: Event) {
  if (event.target instanceof Node && !positionPicker.value?.contains(event.target)) closePositionMenu()
}
async function onPositionKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'Escape') {
    if (positionMenuOpen.value) { event.preventDefault(); event.stopPropagation() }
    closePositionMenu()
  } else if (event.key === 'Tab') {
    closePositionMenu()
  } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    positionMenuOpen.value = true
    const count = positionMatches.value.length
    if (!count) return
    const step = event.key === 'ArrowDown' ? 1 : -1
    activePositionIndex.value = activePositionIndex.value < 0
      ? (step === 1 ? 0 : count - 1)
      : Math.max(0, Math.min(count - 1, activePositionIndex.value + step))
    await nextTick()
    document.getElementById(activePositionId.value ?? '')?.scrollIntoView?.({ block: 'nearest' })
  } else if (event.key === 'Enter' && positionMenuOpen.value) {
    const position = positionMatches.value[activePositionIndex.value]
    if (position) { event.preventDefault(); addPosition(position.id) }
  }
}
async function downloadSelected() {
  exportError.value = ''
  try { await downloadAttachments(name.value.trim() || '이력서', attachmentIds.value) }
  catch (error) { exportError.value = error instanceof Error ? error.message : '첨부파일을 내려받지 못했습니다.' }
}
async function exportWord() {
  downloadWord(name.value.trim() || '이력서', content.value, selectedPositions.value)
  await downloadSelected()
}
async function exportPdf() {
  window.print()
  await downloadSelected()
}
</script>

<template>
  <main class="page">
    <div class="page-head resume-head">
      <button type="button" class="button ghost compact" @click="emit('back', dirty)">← 목록</button>
      <input
        v-model="name"
        class="resume-name"
        maxlength="120"
        aria-label="이력서 이름"
        placeholder="이력서 이름 (예: 백엔드 신입 v2)"
      />
      <span v-if="dirty" class="resume-dirty">변경됨 · 저장 전</span>
      <div class="page-tools">
        <button type="button" class="button secondary compact" @click="exportOpen = !exportOpen">내보내기</button>
        <button type="button" class="button secondary compact" :disabled="saving" @click="emit('copy')">복제</button>
        <button type="button" class="button danger compact" :disabled="saving" @click="emit('remove')">삭제</button>
        <button type="button" class="button primary" :disabled="saving || !dirty || !name.trim()" @click="submit">
          {{ saving ? '저장 중…' : '저장' }}
        </button>
      </div>
    </div>
    <p v-if="!name.trim()" class="field-error">이력서 이름을 입력해 주세요.</p>
    <p v-else-if="apiFieldErrors.name" class="field-error">{{ apiFieldErrors.name }}</p>

    <section v-if="exportOpen" class="resume-section export-panel">
      <div class="resume-section__head"><h2>이력서 내보내기</h2></div>
      <p class="body-copy">PDF는 인쇄 창에서 “PDF로 저장”을 선택합니다. Word는 .doc 파일로 내려받습니다.</p>
      <fieldset v-if="attachmentCandidates.length" class="export-files">
        <legend>함께 받을 첨부파일 (선택 시 ZIP)</legend>
        <label v-for="item in attachmentCandidates" :key="item.id"><input v-model="attachmentIds" type="checkbox" :value="item.id" /> {{ item.label }}</label>
      </fieldset>
      <div class="page-tools"><button type="button" class="button secondary" @click="exportPdf">PDF 저장</button><button type="button" class="button primary" @click="exportWord">Word 저장</button></div>
      <p v-if="exportError" class="field-error" role="alert">{{ exportError }}</p>
    </section>

    <section class="resume-section">
      <div class="resume-section__head"><h2>지원 직무 <span class="page-count">{{ positionIds.length }}</span></h2></div>
      <div ref="positionPicker" class="resume-position-search">
        <div class="field full">
          <label :for="`${positionListId}-search`">직무 검색</label>
          <div class="position-search-control">
            <input
              :id="`${positionListId}-search`"
              ref="positionInput"
              v-model="positionQuery"
              placeholder="직무명, 기업명, 공고명 검색"
              role="combobox"
              aria-autocomplete="list"
              :aria-expanded="positionMenuOpen"
              :aria-controls="positionMenuOpen ? positionListId : undefined"
              :aria-activedescendant="activePositionId"
              autocomplete="off"
              @focus="positionMenuOpen = true"
              @click="positionMenuOpen = true"
              @input="positionMenuOpen = true"
              @keydown="onPositionKeydown"
            />
            <span class="position-search-caret" :class="{ 'is-open': positionMenuOpen }" aria-hidden="true">▾</span>
          </div>
        </div>
        <div v-if="positionMenuOpen" :id="positionListId" ref="positionMenu" class="position-picker" role="listbox" aria-label="연결할 모집 직무" aria-multiselectable="true">
          <button
            v-for="(position, index) in positionMatches"
            :id="`${positionListId}-${index}`"
            :key="position.id"
            type="button"
            class="position-option"
            :class="{ active: index === activePositionIndex }"
            role="option"
            aria-selected="false"
            tabindex="-1"
            @mousedown.prevent
            @click="addPosition(position.id)"
          >
            <span><strong>{{ position.name }}</strong><small>{{ position.companyName || '회사 미입력' }} · {{ position.postingTitle || '공고 미입력' }}</small></span>
          </button>
          <p v-if="!positionMatches.length" class="body-copy empty position-picker__empty" role="status">{{ !positions.length ? '연결할 모집 직무가 없습니다.' : positionQuery.trim() ? '검색 결과가 없습니다.' : '추가할 모집 직무가 없습니다.' }}</p>
        </div>
      </div>
      <div class="selected-positions">
        <button v-for="position in selectedPositions" :key="position.id" type="button" class="chip" :aria-label="`${position.name} 연결 해제`" @click="removePosition(position.id)">{{ position.name }} ×</button>
      </div>
    </section>

    <section class="resume-section">
      <div class="resume-section__head"><h2>기본정보</h2></div>
      <div class="form-grid">
        <AttachmentField v-for="field in BASIC_FIELDS.filter((item) => item.kind === 'file')" :key="field.key" :id="basicValue(field.key)" :label="field.label" @update:id="setBasic(field.key, $event)" />
        <label v-for="field in BASIC_FIELDS.filter((item) => item.kind !== 'file')" :key="field.key" class="field" :class="{ full: field.kind === 'url' }">
          <span>{{ field.label }}</span>
          <input
            :value="basicValue(field.key)"
            :maxlength="MAX_LENGTH[field.kind]"
            :placeholder="field.placeholder"
            @input="setBasic(field.key, ($event.target as HTMLInputElement).value)"
          />
        </label>
      </div>
    </section>

    <ResumeSection
      v-for="section in SECTIONS"
      :key="section.key"
      :section="section"
      :rows="content[section.key]"
      @update:rows="setRows(section.key, $event)"
    />
    <ResumeQuestions :resume-id="resume.id" @open-introductions="emit('openIntroductions')" />
  </main>
</template>
