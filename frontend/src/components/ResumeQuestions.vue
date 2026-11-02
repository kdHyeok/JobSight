<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { createSelfIntroduction, listSelfIntroductions, updateSelfIntroduction } from '../api/self-introductions'
import { ApiClientError } from '../api/http'
import type { SelfIntroduction } from '../types/self-introduction'

/**
 * questions 는 부모가 이력서와 함께 받아 둔 첫 목록이다. 있으면 다시 부르지 않는다.
 * 없으면(생략·null) 지금처럼 직접 부른다. 추가·연결·수정 뒤 다시 부르는 것은 언제나 여기서 한다.
 */
const props = defineProps<{ resumeId: string; questions?: SelfIntroduction[] | null }>()
const emit = defineEmits<{ openIntroductions: [] }>()
const items = ref<SelfIntroduction[]>(props.questions ?? [])
const matches = ref<SelfIntroduction[]>([])
const question = ref('')
const answer = ref('')
const query = ref('')
const editingId = ref('')
const editQuestion = ref('')
const editAnswer = ref('')
/** 수정을 시작할 때 읽은 version. 409 뒤 목록을 다시 읽어도 바꾸지 않는다 — 편집기를 다시 열어야 새 version 으로 저장된다. */
let editVersion = 0
const saving = ref(false)
const errorMessage = ref('')
const linkedIds = computed(() => new Set(items.value.map((item) => item.id)))

/** 마지막으로 보낸 목록 조회의 순번. 이력서가 바뀐 뒤 도착한 예전 응답은 버린다. */
let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  try {
    const result = await listSelfIntroductions('', props.resumeId)
    if (seq === loadSeq) items.value = result
  } catch (error) {
    if (seq === loadSeq) errorMessage.value = error instanceof Error ? error.message : '문항을 불러오지 못했습니다.'
  }
}

async function add() {
  if (!question.value.trim()) return
  saving.value = true
  errorMessage.value = ''
  try {
    await createSelfIntroduction({ resumeIds: [props.resumeId], question: question.value.trim(), answer: answer.value.trim() })
    question.value = ''
    answer.value = ''
    await load()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '문항을 추가하지 못했습니다.'
  } finally { saving.value = false }
}

async function search() {
  matches.value = query.value.trim() ? await listSelfIntroductions(query.value.trim()) : []
}

/** 409(다른 곳에서 먼저 저장)면 서버 안내를 띄우고 목록을 다시 읽는다. 읽은 version 으로는 덮어쓰지 않는다(불변 조건 12). */
async function failSave(error: unknown, fallback: string) {
  if (error instanceof ApiClientError && error.status === 409) await load()
  errorMessage.value = error instanceof Error ? error.message : fallback
}

async function attach(item: SelfIntroduction) {
  saving.value = true
  errorMessage.value = ''
  try {
    await updateSelfIntroduction(item.id, {
      question: item.question, answer: item.answer ?? '', resumeIds: [...new Set([...item.resumeIds, props.resumeId])],
      expectedVersion: item.version,
    })
    await load()
  } catch (error) {
    await failSave(error, '문항을 연결하지 못했습니다.')
  } finally { saving.value = false }
}

function startEdit(item: SelfIntroduction) {
  editingId.value = item.id
  editQuestion.value = item.question
  editAnswer.value = item.answer ?? ''
  editVersion = item.version
}

async function saveEdit(item: SelfIntroduction) {
  if (!editQuestion.value.trim()) return
  saving.value = true
  errorMessage.value = ''
  try {
    await updateSelfIntroduction(item.id, {
      resumeIds: item.resumeIds, question: editQuestion.value.trim(), answer: editAnswer.value.trim(), expectedVersion: editVersion,
    })
    editingId.value = ''
    await load()
  } catch (error) {
    await failSave(error, '문항을 수정하지 못했습니다.')
  } finally { saving.value = false }
}

/** 부모가 목록을 넘겼으면 그 왕복은 이미 끝났다. 같은 요청을 한 번 더 보내지 않는다. */
function loadIfMissing() {
  if (!props.questions) load()
}

// 부모가 새 목록을 넘기면 그것이 최신이다. 진행 중인 자체 조회 결과는 버린다.
watch(() => props.questions, (value) => {
  if (!value) return
  loadSeq++
  items.value = value
})
// 다른 이력서로 바뀌면 이전 이력서의 목록·수정 상태·오류를 남기지 않는다. 목록을 못 받았으면 직접 부른다.
watch(() => props.resumeId, () => {
  loadSeq++
  items.value = props.questions ?? []
  editingId.value = ''
  errorMessage.value = ''
  loadIfMissing()
})
onMounted(loadIfMissing)
</script>

<template>
  <section class="resume-section">
    <div class="resume-section__head">
      <h2>자기소개 문항 <span class="page-count">{{ items.length }}</span></h2>
      <button type="button" class="button secondary compact" @click="emit('openIntroductions')">답변 관리</button>
    </div>
    <p v-if="errorMessage" class="field-error" role="alert">{{ errorMessage }}</p>
    <p v-if="items.length === 0" class="body-copy empty">아직 자기소개 문항이 없습니다.</p>
    <ol v-else class="question-list">
      <li v-for="item in items" :key="item.id">
        <form v-if="editingId === item.id" class="question-edit" @submit.prevent="saveEdit(item)">
          <label class="field full"><span>질문</span><textarea v-model="editQuestion" maxlength="1000" rows="2" /></label>
          <label class="field full"><span>답변 (선택)</span><textarea v-model="editAnswer" maxlength="10000" rows="4" /></label>
          <div class="page-tools">
            <button type="button" class="button ghost compact" @click="editingId = ''">취소</button>
            <button type="submit" class="button primary compact" :disabled="saving || !editQuestion.trim()">{{ saving ? '저장 중…' : '저장' }}</button>
          </div>
        </form>
        <div v-else class="question-list__view" @dblclick="startEdit(item)">
          <div><strong>{{ item.question }}</strong><p v-if="item.answer">{{ item.answer }}</p></div>
          <button type="button" class="button ghost compact" @click="startEdit(item)">수정</button>
        </div>
      </li>
    </ol>
    <form class="question-add form-grid" @submit.prevent="add">
      <label class="field full"><span>질문 추가</span><input v-model="question" maxlength="1000" placeholder="예: 지원 동기와 입사 후 목표를 작성해 주세요." /></label>
      <label class="field full"><span>답변 (선택)</span><textarea v-model="answer" maxlength="10000" rows="4" placeholder="답변은 나중에 작성해도 됩니다." /></label>
      <button type="submit" class="button primary" :disabled="saving || !question.trim()">{{ saving ? '추가 중…' : '문항 추가' }}</button>
    </form>
    <form class="question-search" @submit.prevent="search">
      <label class="field full"><span>과거 질문·답변 검색</span><input v-model="query" maxlength="200" placeholder="같은 단어로 검색" /></label>
      <button type="submit" class="button secondary" :disabled="!query.trim()">검색</button>
    </form>
    <ul v-if="matches.length" class="question-results">
      <li v-for="item in matches" :key="item.id">
        <span><strong>{{ item.question }}</strong><small v-if="item.answer">{{ item.answer }}</small></span>
        <button type="button" class="button ghost compact" :disabled="saving || linkedIds.has(item.id)" @click="attach(item)">{{ linkedIds.has(item.id) ? '추가됨' : '추가' }}</button>
      </li>
    </ul>
  </section>
</template>
