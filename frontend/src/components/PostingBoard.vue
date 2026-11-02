<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { listCompanies } from '../api/companies'
import {
  ApiClientError,
  changePostingStatus,
  changeStepResult,
  createPosting,
  deletePosting,
  getPosting,
  listArchivedPostings,
  listPostings,
  setPostingArchived,
  updatePosting,
} from '../api/postings'
import ConfirmDialog from './ConfirmDialog.vue'
import Drawer from './Drawer.vue'
import PostingCard from './PostingCard.vue'
import PostingDetail from './PostingDetail.vue'
import PostingForm from './PostingForm.vue'
import type { Company } from '../types/company'
import type { ApplicationStatus, JobPosting, JobPostingPayload, PostingTab, StepResult } from '../types/posting'
import { groupPostingsByKanban, postingDropTarget, postingTabCounts } from '../types/posting'
import { linkedSelfIntroCount, writtenForNotice } from '../utils/writtenFor'

const props = defineProps<{ focus: string | null }>()

/** 다른 화면으로 건너뛸 때. 라우팅은 App 이 한다. */
const emit = defineEmits<{
  openPosition: [positionId: string]
  openCompany: [companyId: string]
}>()

/** 드로어 하나가 보기·수정·추가를 모두 맡는다 — docs/design-system.md 규칙 4. */
type Mode = 'view' | 'edit' | 'create'

const tab = ref<PostingTab>('interested')
const postings = ref<JobPosting[]>([])
const tabCounts = ref<Record<PostingTab, number>>({ interested: 0, progress: 0, archived: 0 })
const companies = ref<Company[]>([])
const loading = ref(true)
const loadError = ref('')
const notice = ref('')

const mode = ref<Mode | null>(null)
const selected = ref<JobPosting | null>(null)
const saving = ref(false)
const formErrors = ref<Record<string, string>>({})
const deleteTarget = ref<JobPosting | null>(null)
/** 소속 직무로 쓴 자소서의 직접 출처가 함께 사라진다는 안내. 목록에서 고른 공고면 상세를 한 번 불러 수를 얻는다. */
const deleteNotice = ref('')
const deleting = ref(false)
const dragging = ref<JobPosting | null>(null)
const dropOver = ref('')
const movingId = ref<string | null>(null)

const kanbanColumns = computed(() => groupPostingsByKanban(postings.value, tab.value))
const visibleCount = computed(() => kanbanColumns.value.reduce((sum, column) => sum + column.postings.length, 0))

/** 서버가 마감 임박 순으로 내려준다. 여기서 다시 정렬하면 두 곳이 어긋난다. */
async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [open, archived] = await Promise.all([listPostings(), listArchivedPostings()])
    postings.value = tab.value === 'archived' ? archived : open
    tabCounts.value = postingTabCounts(open, archived)
    if (selected.value) {
      selected.value = postings.value.find((posting) => posting.id === selected.value?.id) ?? selected.value
    }
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '채용공고를 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

async function loadCompanies() {
  // 폼의 기업 선택지. 실패해도 직접 입력이 가능하니 화면을 막지 않는다.
  try {
    companies.value = await listCompanies()
  } catch {
    companies.value = []
  }
}

function open(posting: JobPosting) {
  selected.value = posting
  mode.value = 'view'
}
function openCreate() {
  selected.value = null
  formErrors.value = {}
  mode.value = 'create'
}
function startEdit() {
  formErrors.value = {}
  mode.value = 'edit'
}
function cancelEdit() {
  if (mode.value === 'edit') mode.value = 'view'
  else close()
}
function close() {
  mode.value = null
  selected.value = null
}

function fail(error: unknown, fallback: string) {
  if (error instanceof ApiClientError) {
    formErrors.value = error.fieldErrors
    if (Object.keys(error.fieldErrors).length === 0) loadError.value = error.message
  } else {
    loadError.value = fallback
  }
}

async function save(payload: JobPostingPayload) {
  saving.value = true
  formErrors.value = {}
  notice.value = ''
  const editingId = mode.value === 'edit' ? selected.value?.id ?? null : null
  try {
    const saved = editingId ? await updatePosting(editingId, payload) : await createPosting(payload)
    selected.value = saved
    mode.value = 'view'
    await load()
    // 직접 입력으로 기업이 새로 생겼을 수 있다.
    if (!payload.companyId) await loadCompanies()
    notice.value = editingId ? '채용공고를 수정했습니다.' : '채용공고를 추가했습니다.'
  } catch (error) {
    fail(error, '저장 중 오류가 발생했습니다.')
  } finally {
    saving.value = false
  }
}

async function changeStatus(status: ApplicationStatus) {
  if (!selected.value) return
  try {
    selected.value = await changePostingStatus(selected.value.id, status)
    await load()
  } catch (error) {
    fail(error, '상태를 바꾸지 못했습니다.')
  }
}

async function changeStep(seq: number, result: StepResult) {
  if (!selected.value) return
  try {
    selected.value = await changeStepResult(selected.value.id, seq, result)
    await load()
  } catch (error) {
    fail(error, '절차 결과를 바꾸지 못했습니다.')
  }
}

async function toggleArchive() {
  if (!selected.value) return
  const wasArchived = selected.value.archived
  notice.value = ''
  try {
    await setPostingArchived(selected.value.id, !wasArchived)
    close()
    await load()
    notice.value = wasArchived ? '공고를 다시 꺼냈습니다.' : '공고를 보관함으로 옮겼습니다.'
  } catch (error) {
    fail(error, '보관 상태를 바꾸지 못했습니다.')
  }
}

function startDrag(posting: JobPosting, event: DragEvent) {
  dragging.value = posting
  event.dataTransfer?.setData('text/plain', posting.id)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

async function dropPosting(targetTab: PostingTab, columnKey?: string) {
  const posting = dragging.value
  dragging.value = null
  dropOver.value = ''
  if (!posting || movingId.value) return
  movingId.value = posting.id
  notice.value = ''
  try {
    const target = postingDropTarget(posting.status, targetTab, columnKey)
    let moved = posting
    if (moved.status !== target.status) moved = await changePostingStatus(moved.id, target.status)
    if (moved.archived !== target.archived) moved = await setPostingArchived(moved.id, target.archived)
    if (tab.value === targetTab) await load()
    else tab.value = targetTab
    notice.value = `${moved.title} 공고를 이동했습니다.`
  } catch (error) {
    fail(error, '공고를 이동하지 못했습니다.')
    await load()
  } finally {
    movingId.value = null
  }
}

async function askDelete(target: JobPosting) {
  deleteTarget.value = target
  deleteNotice.value = ''
  const count = await linkedSelfIntroCount(target, () => getPosting(target.id))
  if (deleteTarget.value?.id === target.id) deleteNotice.value = writtenForNotice(count, '이 공고의 직무로')
}

async function remove() {
  if (!deleteTarget.value) return
  deleting.value = true
  notice.value = ''
  try {
    await deletePosting(deleteTarget.value.id)
    deleteTarget.value = null
    close()
    await load()
    notice.value = '채용공고를 삭제했습니다.'
  } catch (error) {
    fail(error, '삭제 중 오류가 발생했습니다.')
  } finally {
    deleting.value = false
  }
}

/** 다른 화면이 넘긴 항목을 연다. 목록을 읽은 뒤에만 의미가 있다. */
async function applyFocus() {
  if (!props.focus) return
  const found = postings.value.find((posting) => posting.id === props.focus)
  if (found) {
    open(found)
    return
  }
  // 진행 중 목록에 없으면 보관된 공고다. 직접 읽어 드로어만 연다.
  try {
    open(await getPosting(props.focus))
  } catch {
    // 지워졌거나 남의 공고. 조용히 넘어간다.
  }
}

watch(tab, () => {
  close()
  load()
})

watch(() => props.focus, applyFocus)

onMounted(async () => {
  await load()
  applyFocus()
  await loadCompanies()
})
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div class="page-title">
        <h1>채용공고</h1>
      </div>
      <button type="button" class="button primary" @click="openCreate">공고 추가</button>
    </div>

    <div class="tabs" role="tablist" aria-label="공고 보기">
      <button type="button" role="tab" :aria-selected="tab === 'interested'" :class="{ active: tab === 'interested', 'drag-over': dropOver === 'tab-interested' }" @click="tab = 'interested'" @dragover.prevent="dropOver = 'tab-interested'" @dragleave="dropOver = ''" @drop.prevent="dropPosting('interested')">
        관심 공고 <span class="tab-count">{{ tabCounts.interested }}</span>
      </button>
      <button type="button" role="tab" :aria-selected="tab === 'progress'" :class="{ active: tab === 'progress', 'drag-over': dropOver === 'tab-progress' }" @click="tab = 'progress'" @dragover.prevent="dropOver = 'tab-progress'" @dragleave="dropOver = ''" @drop.prevent="dropPosting('progress')">
        진행 중 <span class="tab-count">{{ tabCounts.progress }}</span>
      </button>
      <button type="button" role="tab" :aria-selected="tab === 'archived'" :class="{ active: tab === 'archived', 'drag-over': dropOver === 'tab-archived' }" @click="tab = 'archived'" @dragover.prevent="dropOver = 'tab-archived'" @dragleave="dropOver = ''" @drop.prevent="dropPosting('archived')">
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

    <section v-else-if="visibleCount === 0" class="empty-state">
      <strong>{{ tab === 'interested' ? '관심 공고가 없습니다.' : tab === 'progress' ? '진행 중인 공고가 없습니다.' : '보관된 공고가 없습니다.' }}</strong>
      <p>마감이 지난 관심·작성중과 탈락 공고는 보관함으로 자동 이동합니다.</p>
      <button v-if="tab !== 'archived'" type="button" class="button primary" @click="openCreate">첫 공고 추가</button>
    </section>

    <section v-else class="kanban" :aria-label="`${tab} 공고 칸반`">
      <section
        v-for="column in kanbanColumns"
        :key="column.key"
        class="kanban-column"
        :class="{ 'drag-over': dropOver === `column-${column.key}` }"
        @dragover.prevent="dropOver = `column-${column.key}`"
        @dragleave="dropOver = ''"
        @drop.prevent.stop="dropPosting(tab, column.key)"
      >
        <header><strong>{{ column.label }}</strong><span>{{ column.postings.length }}</span></header>
        <div class="kanban-column__cards">
          <PostingCard
            v-for="posting in column.postings"
            :key="posting.id"
            :posting="posting"
            :selected="selected?.id === posting.id"
            :moving="movingId === posting.id"
            @select="open(posting)"
            @drag-start="startDrag(posting, $event)"
            @drag-end="dragging = null; dropOver = ''"
          />
          <p v-if="column.postings.length === 0" class="kanban-empty">없음</p>
        </div>
      </section>
    </section>
  </main>

  <Drawer
    v-if="mode === 'view' && selected"
    :title="selected.title"
    :subtitle="selected.companyName ?? '회사 미입력'"
    @close="close"
  >
    <template #actions>
      <button type="button" class="button secondary compact" @click="startEdit">수정</button>
      <button type="button" class="button secondary compact" @click="toggleArchive">
        {{ selected.archived ? '되돌리기' : '보관' }}
      </button>
      <button type="button" class="button danger compact" @click="askDelete(selected)">삭제</button>
    </template>
    <PostingDetail
      :posting="selected"
      @edit="startEdit"
      @change-status="changeStatus"
      @change-step="changeStep"
      @open-position="emit('openPosition', $event)"
      @open-company="emit('openCompany', $event)"
    />
  </Drawer>

  <Drawer
    v-else-if="mode === 'edit' || mode === 'create'"
    :title="mode === 'edit' ? '채용공고 수정' : '채용공고 추가'"
    :subtitle="mode === 'edit' ? selected?.title : undefined"
    @close="cancelEdit"
  >
    <template #actions>
      <button type="button" class="button secondary compact" :disabled="saving" @click="cancelEdit">취소</button>
      <button type="submit" form="posting-form" class="button primary compact" :disabled="saving">
        {{ saving ? '저장 중…' : '저장' }}
      </button>
    </template>
    <PostingForm
      :posting="mode === 'edit' ? selected : null"
      :companies="companies"
      :saving="saving"
      :api-field-errors="formErrors"
      @submit="save"
    />
  </Drawer>

  <ConfirmDialog
    v-if="deleteTarget"
    title="채용공고를 삭제할까요?"
    :subject="deleteTarget.title"
    :detail="` 공고와 그 직무·절차 기록이 영구 삭제됩니다.${deleteNotice}`"
    aria-label="채용공고 삭제 확인"
    :busy="deleting"
    @confirm="remove"
    @cancel="deleteTarget = null"
  />
</template>
