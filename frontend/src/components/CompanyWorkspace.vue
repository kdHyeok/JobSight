<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ApiClientError,
  createCompany,
  deleteCompany,
  getCompany,
  listCompanies,
  updateCompany,
} from '../api/companies'
import CompanyDetail from './CompanyDetail.vue'
import CompanyForm from './CompanyForm.vue'
import CompanyList from './CompanyList.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import Drawer from './Drawer.vue'
import type { Company, CompanyPayload } from '../types/company'
import { linkedSelfIntroCount, writtenForNotice } from '../utils/writtenFor'

/** 다른 화면이 열어 달라고 넘긴 기업. 목록에서만 찾는다(기업엔 보관 개념이 없다). */
const props = withDefaults(defineProps<{ focus?: string | null }>(), { focus: null })

/** 기업 상세의 채용정보를 더블클릭했을 때. 라우팅은 App 이 한다. */
const emit = defineEmits<{ openPosting: [postingId: string] }>()

/** 드로어 하나가 보기·수정·추가를 모두 맡는다 — docs/design-system.md 규칙 4. */
type Mode = 'view' | 'edit' | 'create'

const companies = ref<Company[]>([])
const loading = ref(true)
const loadError = ref('')
const notice = ref('')

const mode = ref<Mode | null>(null)
/** 목록 응답에는 openPostings 가 없다. 카드를 누르면 상세로 다시 읽는다. */
const selected = ref<Company | null>(null)
const selectedId = ref<string | null>(null)
const saving = ref(false)
const formErrors = ref<Record<string, string>>({})
const deleteTarget = ref<Company | null>(null)
/** 소속 공고 직무로 쓴 자소서의 직접 출처가 함께 사라진다는 안내. 보통 드로어가 이미 상세를 들고 있다. */
/** 삭제 확인창 덧붙임 문구. null 이면 자소서 수를 받는 중이라 확정을 막는다. */
const deleteNotice = ref<string | null>('')
const deleting = ref(false)
const industryOptions = computed(() => [...new Set(companies.value.flatMap((company) => company.industries))].sort())

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    companies.value = await listCompanies()
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '기업 목록을 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

async function open(company: Company) {
  selectedId.value = company.id
  selected.value = company
  mode.value = 'view'
  try {
    selected.value = await getCompany(company.id)
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '기업 상세를 불러오지 못했습니다.'
  }
}

function openCreate() {
  selected.value = null
  selectedId.value = null
  formErrors.value = {}
  mode.value = 'create'
}

function startEdit() {
  formErrors.value = {}
  mode.value = 'edit'
}

function close() {
  mode.value = null
  selectedId.value = null
  selected.value = null
}

async function save(payload: CompanyPayload) {
  saving.value = true
  formErrors.value = {}
  notice.value = ''
  const editingId = mode.value === 'edit' ? selected.value?.id ?? null : null
  try {
    const saved = editingId
      ? await updateCompany(editingId, payload)
      : await createCompany(payload)
    await load()
    await open(saved)
    notice.value = editingId ? '기업 정보를 수정했습니다.' : '새 기업을 추가했습니다.'
  } catch (error) {
    if (error instanceof ApiClientError) {
      formErrors.value = error.fieldErrors
      if (Object.keys(error.fieldErrors).length === 0) loadError.value = error.message
    } else {
      loadError.value = '저장 중 오류가 발생했습니다.'
    }
  } finally {
    saving.value = false
  }
}

async function askDelete(target: Company) {
  deleteTarget.value = target
  deleteNotice.value = null
  const count = await linkedSelfIntroCount(target, () => getCompany(target.id))
  if (deleteTarget.value?.id === target.id) deleteNotice.value = writtenForNotice(count, '이 기업 공고의 직무로')
}

async function remove() {
  if (!deleteTarget.value) return
  deleting.value = true
  notice.value = ''
  try {
    await deleteCompany(deleteTarget.value.id)
    deleteTarget.value = null
    close()
    await load()
    notice.value = '기업 정보를 삭제했습니다.'
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '삭제 중 오류가 발생했습니다.'
  } finally {
    deleting.value = false
  }
}

function applyFocus() {
  const found = companies.value.find((company) => company.id === props.focus)
  if (found) open(found)
}

watch(() => props.focus, applyFocus)

onMounted(async () => {
  await load()
  applyFocus()
})
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div class="page-title">
        <h1>기업</h1>
        <span class="page-count">{{ companies.length }}</span>
      </div>
      <button type="button" class="button primary" @click="openCreate">기업 추가</button>
    </div>

    <div v-if="notice" class="notice success" role="status">{{ notice }}</div>
    <div v-if="loadError" class="notice error" role="alert">
      <span>{{ loadError }}</span>
      <button type="button" @click="load">다시 시도</button>
    </div>

    <section v-if="loading" class="loading-card" aria-live="polite">
      <span class="spinner" /> 불러오는 중입니다.
    </section>

    <section v-else-if="companies.length === 0" class="empty-state">
      <strong>등록된 기업이 없습니다.</strong>
      <p>기업을 추가하면 이 자리에 카드로 정리됩니다.</p>
      <button type="button" class="button primary" @click="openCreate">첫 기업 추가</button>
    </section>

    <CompanyList
      v-else
      :companies="companies"
      :selected-id="selectedId"
      @select="open"
    />
  </main>

  <Drawer
    v-if="mode === 'view' && selected"
    :title="selected.name"
    subtitle="기업 프로필"
    @close="close"
  >
    <template #actions>
      <button type="button" class="button secondary compact" @click="startEdit">수정</button>
      <button type="button" class="button danger compact" @click="askDelete(selected)">삭제</button>
    </template>
    <CompanyDetail :company="selected" @edit="startEdit" @open-posting="emit('openPosting', $event)" />
  </Drawer>

  <Drawer
    v-else-if="mode === 'edit' || mode === 'create'"
    :title="mode === 'edit' ? '기업 정보 수정' : '기업 추가'"
    :subtitle="mode === 'edit' ? selected?.name : undefined"
    @close="mode === 'edit' ? (mode = 'view') : close()"
  >
    <template #actions>
      <button
        type="button"
        class="button secondary compact"
        :disabled="saving"
        @click="mode === 'edit' ? (mode = 'view') : close()"
      >
        취소
      </button>
      <button type="submit" form="company-form" class="button primary compact" :disabled="saving">
        {{ saving ? '저장 중…' : '저장' }}
      </button>
    </template>
    <CompanyForm
      :company="mode === 'edit' ? selected : null"
      :saving="saving"
      :api-field-errors="formErrors"
      :industry-options="industryOptions"
      @submit="save"
    />
  </Drawer>

  <ConfirmDialog
    v-if="deleteTarget"
    title="기업 정보를 삭제할까요?"
    :subject="deleteTarget.name"
    :detail="`의 기록과 연결된 채용공고가 함께 삭제됩니다.${deleteNotice ?? ''}`"
    aria-label="기업 삭제 확인"
    :busy="deleting"
    :pending="deleteNotice === null"
    @confirm="remove"
    @cancel="deleteTarget = null"
  />
</template>
