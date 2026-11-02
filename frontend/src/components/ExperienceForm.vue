<script setup lang="ts">
import { computed, reactive } from 'vue'
import TagInput from './TagInput.vue'
import {
  kindLabels,
  type ExperienceDetail,
  type ExperienceFacts,
  type ExperienceKind,
  type ExperiencePayload,
  type FactItem,
} from '../types/experience'

/**
 * 경험 원본 생성·수정 폼. 껍데기 없이 드로어 안에 들어가고, 헤더 버튼이 form="experience-form" 으로 제출한다.
 * 사실 항목은 받은 객체를 그대로 고쳐 id·evidenceIds 를 지킨다 — 서버가 id 로 ADD·CORRECT 를 판정한다(docs/experiences.md 불변 5).
 * 근거·미확인·충돌 칸은 화면에서 고치지 않고 받은 그대로 돌려보낸다.
 */
const props = defineProps<{
  /** 수정할 경험. 없으면 새로 만든다. */
  experience?: ExperienceDetail | null
  /** 기술 태그 선택지(다른 경험의 skills). */
  skillSuggestions?: string[]
}>()
const emit = defineEmits<{ submit: [payload: ExperiencePayload] }>()

const original = props.experience ?? null
const facts = original?.facts ?? {}
const copyItems = (items?: FactItem[] | null): FactItem[] => (items ?? []).map((item) => ({ ...item }))

const draft = reactive({
  kind: (original?.kind ?? 'PROJECT') as ExperienceKind,
  title: original?.title ?? '',
  startYm: original?.startYm ?? '',
  memo: original?.memo ?? '',
  period: facts.period ?? '',
  role: facts.role ?? '',
  environment: facts.measurement?.environment ?? '',
  scope: facts.measurement?.scope ?? '',
  actions: copyItems(facts.actions),
  results: copyItems(facts.results),
  skills: [...(original?.skills ?? [])],
  domains: [...(original?.domains ?? [])],
})

const kinds = Object.keys(kindLabels) as ExperienceKind[]
const blankToNull = (value: string | null | undefined) => (value && value.trim() ? value.trim() : null)
const items = (list: FactItem[]) => list
  .filter((item) => item.text.trim())
  .map((item) => ({ ...item, text: item.text.trim() }))

/** 화면에서 고치는 사실 칸만 비교한다. 근거 등은 그대로라 비교하지 않는다. */
function editable(value: ExperienceFacts) {
  const measurement = blankToNull(value.measurement?.environment) || blankToNull(value.measurement?.scope)
    ? { environment: blankToNull(value.measurement?.environment), scope: blankToNull(value.measurement?.scope) }
    : null
  return JSON.stringify({
    period: blankToNull(value.period),
    role: blankToNull(value.role),
    measurement,
    actions: items(value.actions ?? []).map(({ id, text }) => ({ id: id ?? null, text })),
    results: items(value.results ?? []).map(({ id, text }) => ({ id: id ?? null, text })),
  })
}

const nextFacts = computed<ExperienceFacts>(() => {
  const environment = blankToNull(draft.environment)
  const scope = blankToNull(draft.scope)
  return {
    ...facts,
    period: blankToNull(draft.period),
    role: blankToNull(draft.role),
    measurement: environment || scope ? { environment, scope } : null,
    actions: items(draft.actions),
    results: items(draft.results),
  }
})
/** 사실을 고쳤는가. 고치지 않은 저장은 facts 를 보내지 않는다 — facts 를 보낸 웹 저장만 'AI 기록'을 확정한다. */
const factsChanged = computed(() => !original || editable(nextFacts.value) !== editable(facts))

function submit() {
  if (!draft.title.trim()) return
  const payload: ExperiencePayload = {
    kind: draft.kind,
    title: draft.title.trim(),
    // 서버는 null 을 "유지"로 읽는다. 지운 칸은 빈 문자열로 보내야 실제로 비워진다.
    startYm: draft.startYm.trim(),
    memo: draft.memo.trim(),
    skills: draft.skills,
    domains: draft.domains,
  }
  if (factsChanged.value) payload.facts = nextFacts.value
  if (original) payload.expectedVersion = original.version
  emit('submit', payload)
}

const addItem = (list: FactItem[]) => list.push({ text: '' })
const removeItem = (list: FactItem[], index: number) => list.splice(index, 1)
</script>

<template>
  <form id="experience-form" class="form-grid" @submit.prevent="submit">
    <label class="field">
      <span>종류</span>
      <select v-model="draft.kind">
        <option v-for="kind in kinds" :key="kind" :value="kind">{{ kindLabels[kind] }}</option>
      </select>
    </label>
    <label class="field">
      <span>시작 연월</span>
      <input v-model="draft.startYm" maxlength="200" placeholder="예: 2024.03" />
    </label>
    <label class="field full">
      <span>제목 <b>*</b></span>
      <input v-model="draft.title" maxlength="200" required placeholder="프로젝트명·회사명·활동명" />
    </label>

    <p class="form-section">사실 (이력서·자소서가 이 내용을 가리킨다)</p>
    <label class="field">
      <span>기간</span>
      <input v-model="draft.period" maxlength="200" placeholder="예: 2024.03~2024.08" />
    </label>
    <label class="field">
      <span>역할</span>
      <input v-model="draft.role" maxlength="2000" />
    </label>
    <label class="field">
      <span>측정 환경</span>
      <input v-model="draft.environment" maxlength="1000" placeholder="예: 스테이징, 표본 1,000건" />
    </label>
    <label class="field">
      <span>측정 범위</span>
      <input v-model="draft.scope" maxlength="1000" placeholder="예: 검색 API 한 경로" />
    </label>
    <fieldset v-for="[label, list] in ([['한 일', draft.actions], ['결과', draft.results]] as const)" :key="label" class="field full repeater">
      <legend>{{ label }} <span class="page-count">{{ list.length }}</span></legend>
      <div v-for="(item, index) in list" :key="item.id ?? `new-${index}`" class="repeater__row">
        <textarea v-model="item.text" :aria-label="`${label} ${index + 1}`" maxlength="2000" rows="2" />
        <button type="button" class="icon-button" :aria-label="`${label} ${index + 1} 삭제`" @click="removeItem(list, index)">×</button>
      </div>
      <button type="button" class="button ghost compact" :disabled="list.length >= 50" @click="addItem(list)">+ {{ label }} 추가</button>
    </fieldset>
    <p v-if="original && factsChanged" class="form-hint full">사실을 고쳤습니다. 저장하면 새 개정이 되고 지금 개정을 확정한 것으로 봅니다.</p>

    <p class="form-section">분류와 메모</p>
    <div class="field full">
      <span>기술</span>
      <TagInput v-model="draft.skills" :suggestions="skillSuggestions ?? []" :max="50" placeholder="기술 입력 후 Enter" />
    </div>
    <div class="field full">
      <span>도메인</span>
      <TagInput v-model="draft.domains" :suggestions="[]" :max="20" placeholder="예: 커머스, 금융" />
    </div>
    <label class="field full">
      <span>메모</span>
      <textarea v-model="draft.memo" maxlength="2000" rows="3" />
    </label>
  </form>
</template>
