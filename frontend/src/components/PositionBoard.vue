<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ApiClientError,
  deletePosition,
  listPositions,
  replacePositionReferences,
  updatePosition,
} from '../api/positions'
import { createReference, deleteReference, listReferences, updateReference } from '../api/references'
import ConfirmDialog from './ConfirmDialog.vue'
import Drawer from './Drawer.vue'
import PositionDetail from './PositionDetail.vue'
import PositionForm from './PositionForm.vue'
import type { Position, PositionPayload, ReferenceItem, ReferencePayload } from '../types/position'
import type { PostingTab } from '../types/posting'
import { ddayLabel, ddayTone, postingTabFor } from '../types/posting'

const props = defineProps<{ focus: string | null }>()

/** 역방향 — 직무 상세에서 소속 공고로 건너뛴다. 라우팅은 App 이 한다. */
const emit = defineEmits<{ openPosting: [postingId: string] }>()

type Mode = 'view' | 'edit'

const positions = ref<Position[]>([])
const references = ref<ReferenceItem[]>([])
const tab = ref<PostingTab>('interested')
const query = ref('')
const loading = ref(true)
const loadError = ref('')
const notice = ref('')

const mode = ref<Mode | null>(null)
const selected = ref<Position | null>(null)
const saving = ref(false)
const formErrors = ref<Record<string, string>>({})
const deleteTarget = ref<Position | null>(null)
const deleting = ref(false)
/** 참고 정보 삭제는 여러 직무에 영향을 주므로 따로 확인받는다. */
const referenceDeleteTarget = ref<ReferenceItem | null>(null)
const deletingReference = ref(false)
const techOptions = computed(() => [...new Set(positions.value.flatMap((position) => position.techStack))].sort())
const selectedSubtitle = computed(() => {
  if (!selected.value) return ''
  return [
    selected.value.companyName,
    selected.value.postingTitle !== selected.value.name ? selected.value.postingTitle : null,
  ].filter(Boolean).join(' · ')
})
const tabCounts = computed<Record<PostingTab, number>>(() => ({
  interested: positions.value.filter((position) => postingTabFor(position.status, position.archived) === 'interested').length,
  progress: positions.value.filter((position) => postingTabFor(position.status, position.archived) === 'progress').length,
  archived: positions.value.filter((position) => postingTabFor(position.status, position.archived) === 'archived').length,
}))

/** 검색은 클라이언트 필터. 이름·회사·공고·팀·스택. 수백 건을 넘으면 서버 ?q= 로. */
const visible = computed(() => {
  const needle = query.value.trim().toLowerCase()
  const inTab = positions.value.filter((position) => postingTabFor(position.status, position.archived) === tab.value)
  if (!needle) return inTab
  return inTab.filter((p) =>
    [p.name, p.companyName, p.postingTitle, p.team, ...p.techStack]
      .some((v) => (v ?? '').toLowerCase().includes(needle)))
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    positions.value = await listPositions()
    if (selected.value) {
      selected.value = positions.value.find((p) => p.id === selected.value?.id) ?? selected.value
    }
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '모집 직무를 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

async function loadReferences() {
  try {
    references.value = await listReferences()
  } catch {
    references.value = []
  }
}

function open(position: Position) {
  selected.value = position
  mode.value = 'view'
}
function close() {
  mode.value = null
  selected.value = null
}

/** 다른 화면이 넘긴 항목, 또는 참고 직무 카드가 가리키는 직무를 연다. */
function openById(id: string | null) {
  const found = positions.value.find((position) => position.id === id)
  if (found) {
    tab.value = postingTabFor(found.status, found.archived)
    open(found)
  }
}

function fail(error: unknown, fallback: string) {
  if (error instanceof ApiClientError) {
    formErrors.value = error.fieldErrors
    if (Object.keys(error.fieldErrors).length === 0) loadError.value = error.message
  } else {
    loadError.value = fallback
  }
}

async function save(payload: PositionPayload) {
  if (!selected.value) return
  saving.value = true
  formErrors.value = {}
  notice.value = ''
  try {
    selected.value = await updatePosition(selected.value.id, payload)
    mode.value = 'view'
    await load()
    notice.value = '직무 정보를 수정했습니다.'
  } catch (error) {
    fail(error, '저장 중 오류가 발생했습니다.')
  } finally {
    saving.value = false
  }
}

async function setReferences(ids: string[]) {
  if (!selected.value) return
  saving.value = true
  try {
    selected.value = await replacePositionReferences(selected.value.id, ids)
    await load()
  } catch (error) {
    fail(error, '참고 정보를 바꾸지 못했습니다.')
  } finally {
    saving.value = false
  }
}

const attach = (id: string) =>
  setReferences([...(selected.value?.references.map((r) => r.id) ?? []), id])
const detach = (id: string) =>
  setReferences((selected.value?.references ?? []).map((r) => r.id).filter((x) => x !== id))

/** 참고 정보를 고치면 그 정보가 붙은 모든 직무의 표시가 바뀐다. 목록을 다시 읽는다. */
async function editReference(id: string, payload: ReferencePayload) {
  saving.value = true
  notice.value = ''
  try {
    await updateReference(id, payload)
    await loadReferences()
    await load()
    notice.value = '참고 정보를 수정했습니다.'
  } catch (error) {
    fail(error, '참고 정보를 수정하지 못했습니다.')
  } finally {
    saving.value = false
  }
}

/** 붙어 있던 모든 직무에서 함께 떨어진다(position_references ON DELETE CASCADE). */
async function removeReference() {
  if (!referenceDeleteTarget.value) return
  deletingReference.value = true
  notice.value = ''
  try {
    await deleteReference(referenceDeleteTarget.value.id)
    referenceDeleteTarget.value = null
    await loadReferences()
    await load()
    notice.value = '참고 정보를 삭제했습니다.'
  } catch (error) {
    referenceDeleteTarget.value = null
    fail(error, '참고 정보를 삭제하지 못했습니다.')
  } finally {
    deletingReference.value = false
  }
}

async function createAndAttach(payload: ReferencePayload) {
  saving.value = true
  try {
    const created = await createReference(payload)
    references.value = [created, ...references.value]
    await attach(created.id)
  } catch (error) {
    fail(error, '참고 정보를 만들지 못했습니다.')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!deleteTarget.value) return
  deleting.value = true
  notice.value = ''
  try {
    await deletePosition(deleteTarget.value.id)
    deleteTarget.value = null
    close()
    await load()
    notice.value = '직무를 삭제했습니다.'
  } catch (error) {
    deleteTarget.value = null
    fail(error, '삭제 중 오류가 발생했습니다.')
  } finally {
    deleting.value = false
  }
}

watch(() => props.focus, () => openById(props.focus))

onMounted(async () => {
  await load()
  openById(props.focus)
  await loadReferences()
})
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div class="page-title">
        <h1>모집 직무</h1>
      </div>
      <input v-model="query" class="search" type="search" placeholder="직무·회사·공고·팀·스택 검색" aria-label="직무 검색" />
    </div>

    <div class="tabs" role="tablist" aria-label="모집 직무 보기">
      <button type="button" role="tab" :aria-selected="tab === 'interested'" :class="{ active: tab === 'interested' }" @click="tab = 'interested'">
        관심 직무 <span class="tab-count">{{ tabCounts.interested }}</span>
      </button>
      <button type="button" role="tab" :aria-selected="tab === 'progress'" :class="{ active: tab === 'progress' }" @click="tab = 'progress'">
        진행 중 <span class="tab-count">{{ tabCounts.progress }}</span>
      </button>
      <button type="button" role="tab" :aria-selected="tab === 'archived'" :class="{ active: tab === 'archived' }" @click="tab = 'archived'">
        보관함 <span class="tab-count">{{ tabCounts.archived }}</span>
      </button>
    </div>

    <div v-if="notice" class="notice success" role="status">{{ notice }}</div>
    <div v-if="loadError" class="notice error" role="alert">
      <span>{{ loadError }}</span>
      <button type="button" @click="load">다시 시도</button>
    </div>

    <section v-if="loading" class="loading-card" aria-live="polite">
      <span class="spinner" /> 불러오는 중입니다.
    </section>

    <section v-else-if="visible.length === 0" class="empty-state">
      <strong>{{ query ? '검색 결과가 없습니다.' : tab === 'interested' ? '관심 직무가 없습니다.' : tab === 'progress' ? '진행 중인 직무가 없습니다.' : '보관된 직무가 없습니다.' }}</strong>
      <p>직무는 채용공고를 추가하면 함께 생깁니다.</p>
    </section>

    <section v-else class="card-grid">
      <button
        v-for="position in visible"
        :key="position.id"
        type="button"
        class="card"
        :class="{ selected: selected?.id === position.id }"
        @click="open(position)"
      >
        <span class="card__top">
          <span class="card__title">{{ position.name }}</span>
          <span class="dday" :data-tone="ddayTone(position.deadlineAt)">{{ ddayLabel(position.deadlineAt) }}</span>
        </span>
        <span class="entity-line">
          <span class="entity-company"><span class="entity-icon" aria-hidden="true">▦</span>{{ position.companyName ?? '회사 미입력' }}</span>
          <span v-if="position.postingTitle && position.postingTitle !== position.name" class="entity-posting">{{ position.postingTitle }}</span>
        </span>
        <span v-if="position.techStack.length" class="card__chips">
          <span v-for="tech in position.techStack.slice(0, 3)" :key="tech" class="chip">{{ tech }}</span>
          <span v-if="position.techStack.length > 3" class="chip">+{{ position.techStack.length - 3 }}</span>
        </span>
      </button>
    </section>
  </main>

  <Drawer
    v-if="mode === 'view' && selected"
    :title="selected.name"
    :subtitle="selectedSubtitle"
    @close="close"
  >
    <template #actions>
      <button type="button" class="button secondary compact" @click="formErrors = {}; mode = 'edit'">수정</button>
      <button type="button" class="button danger compact" @click="deleteTarget = selected">삭제</button>
    </template>
    <PositionDetail
      :position="selected"
      :all-references="references"
      :all-positions="positions"
      :busy="saving"
      @edit="formErrors = {}; mode = 'edit'"
      @attach="attach"
      @detach="detach"
      @create="createAndAttach"
      @update="editReference"
      @remove="referenceDeleteTarget = $event"
      @open-position="openById"
      @open-posting="emit('openPosting', $event)"
    />
  </Drawer>

  <Drawer
    v-else-if="mode === 'edit' && selected"
    title="직무 정보 수정"
    :subtitle="selected.name"
    @close="mode = 'view'"
  >
    <template #actions>
      <button type="button" class="button secondary compact" :disabled="saving" @click="mode = 'view'">취소</button>
      <button type="submit" form="position-form" class="button primary compact" :disabled="saving">
        {{ saving ? '저장 중…' : '저장' }}
      </button>
    </template>
    <PositionForm :position="selected" :tech-options="techOptions" :saving="saving" :api-field-errors="formErrors" @submit="save" />
  </Drawer>

  <ConfirmDialog
    v-if="referenceDeleteTarget"
    title="참고 정보를 삭제할까요?"
    :subject="referenceDeleteTarget.title"
    detail=" 이(가) 붙어 있는 모든 직무에서 사라집니다. 이 직무에서만 빼려면 ‘떼기’ 를 쓰세요."
    aria-label="참고 정보 삭제 확인"
    :busy="deletingReference"
    @confirm="removeReference"
    @cancel="referenceDeleteTarget = null"
  />

  <ConfirmDialog
    v-if="deleteTarget"
    title="직무를 삭제할까요?"
    :subject="deleteTarget.name"
    detail=" 직무 기록이 영구 삭제됩니다. 공고의 마지막 직무라면 채용공고와 절차도 함께 삭제됩니다."
    aria-label="직무 삭제 확인"
    :busy="deleting"
    @confirm="remove"
    @cancel="deleteTarget = null"
  />
</template>
