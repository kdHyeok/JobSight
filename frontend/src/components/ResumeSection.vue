<script setup lang="ts">
import { computed } from 'vue'
import AttachmentField from './AttachmentField.vue'
import TermTable from './TermTable.vue'
import type { AttachmentMeta } from '../types/attachment'
import type { Row, RowValue, SectionConfig, TermRow } from '../types/resume'
import { MAX_LENGTH, emptyRow, text, terms, visible } from '../types/resume'
import { anchorOf, type ExperienceLine } from '../types/experience'

/**
 * 섹션 하나 = 행 카드들 + 행 추가. 행은 불변으로 갈아끼운다(부모의 변경 감지가 깊은 비교라서).
 * 행의 :key 는 index 다. 값은 :value + @input 이라 ↑↓ 뒤에도 입력값이 따라간다 — v-model 로 바꾸지 말 것.
 */
const props = defineProps<{
  section: SectionConfig
  rows: Row[]
  /** 이력서 응답에 함께 온 첨부 메타(id → 메타). 첨부 칸이 다시 부르지 않게 내려 준다. */
  attachments?: Record<string, AttachmentMeta>
  /**
   * 경험 앵커를 갖는 섹션이면 이 섹션 종류의 경험 원본들. 없으면(undefined) 경험 조작을 그리지 않는다 — docs/experiences.md.
   * staleIds 는 '재검토 필요'(참조 개정 뒤 정정), refreshIds 는 저장할 때 원본 개정으로 올릴 경험이다.
   */
  experiences?: ExperienceLine[]
  staleIds?: string[]
  refreshIds?: string[]
}>()
const emit = defineEmits<{
  'update:rows': [Row[]]
  /** 미연결 행으로 새 경험 원본을 만든다. */
  register: [index: number]
  /** 경험 원본의 사실로 새 행을 만든다. */
  load: [experienceId: string]
  /** "원본으로 갱신". */
  refresh: [experienceId: string]
}>()

function add() {
  emit('update:rows', [...props.rows, emptyRow(props.section)])
}
function remove(index: number) {
  emit('update:rows', props.rows.filter((_, i) => i !== index))
}
function move(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= props.rows.length) return
  const next = [...props.rows]
  const [row] = next.splice(index, 1)
  next.splice(target, 0, row)
  emit('update:rows', next)
}
function set(index: number, key: string, value: RowValue) {
  emit('update:rows', props.rows.map((row, i) => (i === index ? { ...row, [key]: value } : row)))
}

const setTerms = (index: number, key: string, rows: TermRow[]) => set(index, key, rows)

/**
 * "다른 경험에 연결": 경험 id 를 바꾸고 개정은 비워 보낸다. 서버는 새 연결을 그 경험의 지금 개정으로 잇는다.
 * 개정을 남기면 서버가 다른 경험의 번호로 읽어 400 이 될 수 있다.
 */
function relink(index: number, experienceId: string) {
  if (!experienceId) return
  emit('update:rows', props.rows.map((row, i) => {
    if (i !== index) return row
    const next: Row = { ...row, experienceId }
    delete next.experienceRevision
    return next
  }))
}
const titleOf = (id: string | null) => props.experiences?.find((line) => line.id === id)?.title ?? '경험 원본'
/** 한 이력서에 같은 경험을 두 행에 쓰면 서버가 거절한다(EXPERIENCE_ROW_DUPLICATE). 이미 쓴 경험은 고르지 않게 한다. */
const used = computed(() => new Set(props.rows.map(anchorOf).filter(Boolean)))
/** select 는 고르는 즉시 동작하고 다시 비운다. */
function picked(event: Event) {
  const select = event.target as HTMLSelectElement
  const value = select.value
  select.value = ''
  return value
}
</script>

<template>
  <section class="resume-section">
    <div class="resume-section__head">
      <h2>{{ section.label }} <span class="page-count">{{ rows.length }}</span></h2>
      <span class="page-tools">
        <select v-if="experiences" class="resume-exp-select" aria-label="경험에서 불러오기" :disabled="!experiences.length"
          @change="emit('load', picked($event))">
          <option value="">{{ experiences.length ? '경험에서 불러오기…' : '불러올 경험 없음' }}</option>
          <option v-for="line in experiences" :key="line.id" :value="line.id" :disabled="used.has(line.id)">{{ line.title }}{{ line.period ? ` (${line.period})` : '' }}</option>
        </select>
        <button type="button" class="button ghost compact" @click="add">+ 행 추가</button>
      </span>
    </div>

    <p v-if="rows.length === 0" class="body-copy empty">아직 행이 없습니다.</p>

    <article v-for="(row, index) in rows" :key="index" class="resume-row">
      <div class="resume-row__bar">
        <span class="repeater__seq">{{ index + 1 }}</span>
        <span v-if="experiences" class="resume-row__exp">
          <template v-if="anchorOf(row)">
            <span class="chip" data-tone="primary" :title="`경험 원본에 연결됨 · 개정 ${row.experienceRevision ?? '저장 시 결정'}`">경험: {{ titleOf(anchorOf(row)) }}</span>
            <span v-if="refreshIds?.includes(anchorOf(row)!)" class="chip">저장하면 원본 개정으로 갱신</span>
            <template v-else-if="staleIds?.includes(anchorOf(row)!)">
              <span class="chip" data-tone="warning">재검토 필요</span>
              <button type="button" class="button ghost compact" @click="emit('refresh', anchorOf(row)!)">원본으로 갱신</button>
            </template>
          </template>
          <template v-else>
            <span class="chip">경험 미연결</span>
            <button type="button" class="button ghost compact" @click="emit('register', index)">경험으로 등록</button>
          </template>
          <select class="resume-exp-select" :aria-label="`${index + 1}행 ${anchorOf(row) ? '다른 경험에 연결' : '경험에 연결'}`" :disabled="!experiences.length" @change="relink(index, picked($event))">
            <option value="">{{ anchorOf(row) ? '다른 경험에 연결…' : '경험에 연결…' }}</option>
            <option v-for="line in experiences" :key="line.id" :value="line.id" :disabled="used.has(line.id)">{{ line.title }}{{ line.period ? ` (${line.period})` : '' }}</option>
          </select>
        </span>
        <span class="repeater__move">
          <button type="button" class="icon-button" aria-label="위로" :disabled="index === 0" @click="move(index, -1)">↑</button>
          <button type="button" class="icon-button" aria-label="아래로" :disabled="index === rows.length - 1" @click="move(index, 1)">↓</button>
          <button type="button" class="icon-button" aria-label="행 삭제" @click="remove(index)">×</button>
        </span>
      </div>
      <div class="form-grid">
        <!-- 학교 구분에 따라 대학교/고등학교 항목이 갈린다(visible). -->
        <template v-for="field in section.fields" :key="field.key">
          <template v-if="visible(field, row)">
            <TermTable
              v-if="field.kind === 'terms'"
              :label="field.label"
              :columns="field.columns ?? []"
              :rows="terms(row, field.key)"
              @update:rows="setTerms(index, field.key, $event)"
            />
            <AttachmentField
              v-else-if="field.kind === 'file'"
              :id="text(row, field.key)"
              :label="field.label"
              :preloaded="attachments?.[text(row, field.key)]"
              @update:id="set(index, field.key, $event)"
            />
            <label v-else class="field" :class="{ full: field.kind === 'textarea' || field.kind === 'url' }">
              <span>{{ field.label }}</span>
              <select
                v-if="field.kind === 'select'"
                :value="text(row, field.key)"
                @change="set(index, field.key, ($event.target as HTMLSelectElement).value)"
              >
                <option value="">선택 안 함</option>
                <option v-for="option in field.options ?? []" :key="option" :value="option">{{ option }}</option>
              </select>
              <textarea
                v-else-if="field.kind === 'textarea'"
                :value="text(row, field.key)"
                :maxlength="MAX_LENGTH.textarea"
                rows="3"
                @input="set(index, field.key, ($event.target as HTMLTextAreaElement).value)"
              />
              <input
                v-else
                :value="text(row, field.key)"
                :maxlength="MAX_LENGTH[field.kind]"
                :placeholder="field.placeholder"
                @input="set(index, field.key, ($event.target as HTMLInputElement).value)"
              />
            </label>
          </template>
        </template>
      </div>
    </article>
  </section>
</template>
