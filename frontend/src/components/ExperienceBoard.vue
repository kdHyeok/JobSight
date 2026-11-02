<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiClientError } from '../api/http'
import {
  createExperience,
  deleteExperience,
  getExperience,
  listExperiences,
  reviewExperience,
  updateExperience,
} from '../api/experiences'
import ConfirmDialog from './ConfirmDialog.vue'
import Drawer from './Drawer.vue'
import ExperienceForm from './ExperienceForm.vue'
import ExperienceImport from './ExperienceImport.vue'
import { kindLabels, type ExperienceDetail, type ExperienceLine, type ExperiencePayload, type ImportResult } from '../types/experience'
import { competencyLabels } from '../types/self-introduction'

/**
 * /experiences — 경험 원본 목록(카드) + 드로어 상세·수정 + 가져오기 + 삭제(docs/experiences.md).
 * 중복 처리 흐름(설계 6.4): 이력서 편집기에서 행을 "다른 경험에 연결"한 뒤, 쓰지 않게 된 경험을 여기서 지운다.
 * 이력서가 아직 쓰면 409 로 막히고, 자소서 작성 근거만 남았으면 근거를 끊고 지울지 묻는다.
 */
const props = defineProps<{ focus: string | null }>()
const emit = defineEmits<{ openResume: [id: string]; openIntroductions: [] }>()

const lines = ref<ExperienceLine[]>([])
const unlinkedRows = ref(0)
const loading = ref(true)
const errorMessage = ref('')
const notice = ref('')
const importing = ref(false)
const selected = ref<ExperienceDetail | null>(null)
const mode = ref<'view' | 'edit' | 'new' | null>(null)
const drawerError = ref('')
const saving = ref(false)
const deleteTarget = ref<ExperienceDetail | null>(null)
const detachTarget = ref<ExperienceDetail | null>(null)

const skillSuggestions = computed(() => [...new Set(lines.value.flatMap((line) => line.skills))])
const sectionLabels: Record<string, string> = {
  projects: '프로젝트', experiences: '경력', activities: '대내외활동', trainings: '교육이수',
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listExperiences()
    lines.value = result.experiences
    unlinkedRows.value = result.index.counts.unlinkedRows
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '경험 목록을 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

async function open(id: string) {
  drawerError.value = ''
  try {
    selected.value = await getExperience(id)
    mode.value = 'view'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '경험을 불러오지 못했습니다.'
  }
}

function close() {
  mode.value = null
  selected.value = null
  drawerError.value = ''
}

async function save(payload: ExperiencePayload) {
  saving.value = true
  drawerError.value = ''
  notice.value = ''
  try {
    const saved = mode.value === 'new' || !selected.value
      ? await createExperience(payload)
      : await updateExperience(selected.value.id, payload)
    if (saved.needsReview.length) {
      notice.value = `정정한 사실을 쓴 문서에 '재검토 필요'가 붙었습니다: ${saved.needsReview.map((item) => item.name).join(', ')}`
    }
    await Promise.all([load(), open(saved.id)])
  } catch (error) {
    // EXPERIENCE_STALE: 폼은 열 때의 version 을 쥐고 있어 다시 보내도 또 409 다. 상세를 새로 읽어 보기로 돌린다.
    if (error instanceof ApiClientError && error.code === 'EXPERIENCE_STALE' && selected.value) {
      await open(selected.value.id)
      drawerError.value = '다른 곳에서 바뀌었습니다. 새 내용으로 다시 수정하세요.'
      return
    }
    // 그 밖의 오류는 폼과 입력을 남기고 이유를 보여 준다.
    drawerError.value = error instanceof Error ? error.message : '경험을 저장하지 못했습니다.'
  } finally {
    saving.value = false
  }
}

/** 웹 전용 검토 확정. 보고 있던 개정을 보낸다 — 그사이 AI 가 개정을 올렸으면 409 라 새로 읽는다. */
async function review() {
  if (!selected.value) return
  drawerError.value = ''
  try {
    selected.value = await reviewExperience(selected.value.id, selected.value.revision)
    await load()
  } catch (error) {
    const stale = error instanceof ApiClientError && error.status === 409
    if (stale && selected.value) await open(selected.value.id)
    drawerError.value = error instanceof Error ? error.message : '검토를 확정하지 못했습니다.'
  }
}

async function remove(detach: boolean) {
  const target = detach ? detachTarget.value : deleteTarget.value
  if (!target) return
  saving.value = true
  try {
    await deleteExperience(target.id, detach)
    deleteTarget.value = null
    detachTarget.value = null
    close()
    notice.value = `"${target.title}" 경험을 삭제했습니다.`
    await load()
  } catch (error) {
    deleteTarget.value = null
    detachTarget.value = null
    if (error instanceof ApiClientError && error.status === 409 && !detach) {
      // 사용처를 다시 읽는다. 이력서가 쓰면 그 행을 먼저 옮겨야 하고, 자소서 근거만 남았으면 끊고 지울지 묻는다.
      const fresh = await getExperience(target.id).catch(() => null)
      if (fresh) selected.value = fresh
      if (fresh && !fresh.rows.length && fresh.selfIntros.length) {
        detachTarget.value = fresh
        return
      }
    }
    drawerError.value = error instanceof Error ? error.message : '경험을 삭제하지 못했습니다.'
  } finally {
    saving.value = false
  }
}

async function imported(result: ImportResult) {
  importing.value = false
  notice.value = `가져오기 완료: 새 경험 ${result.created}개, 연결 ${result.linked}묶음, 건너뜀 ${result.skipped}묶음.`
  await load()
}

function openResumeFromRow(event: KeyboardEvent | MouseEvent, resumeId: string) {
  event.preventDefault()
  emit('openResume', resumeId)
}

watch(() => props.focus, (id) => { if (id) open(id) })
onMounted(async () => {
  await load()
  if (props.focus) await open(props.focus)
})
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div class="page-title">
        <h1>경험</h1>
        <span class="page-count">{{ lines.length }}</span>
      </div>
      <div class="page-tools">
        <button type="button" class="button secondary" :disabled="importing" @click="importing = true">
          이력서에서 가져오기<template v-if="unlinkedRows"> ({{ unlinkedRows }}행)</template>
        </button>
        <button type="button" class="button primary" @click="selected = null; drawerError = ''; mode = 'new'">경험 추가</button>
      </div>
    </div>

    <div v-if="notice" class="notice success" role="status">{{ notice }}</div>
    <div v-if="errorMessage" class="notice error" role="alert">
      <span>{{ errorMessage }}</span>
      <button type="button" @click="load">다시 시도</button>
    </div>

    <ExperienceImport v-if="importing" :experiences="lines" @done="imported" @close="importing = false" />

    <section v-if="loading" class="loading-card" aria-live="polite"><span class="spinner" /> 불러오는 중입니다.</section>
    <section v-else-if="!lines.length && !importing" class="empty-state">
      <strong>경험 원본이 없습니다.</strong>
      <p>프로젝트·경력·활동의 사실을 한 곳에 두고 이력서와 자기소개가 그것을 가리키게 합니다.</p>
      <button v-if="unlinkedRows" type="button" class="button primary" @click="importing = true">이력서에서 가져오기</button>
    </section>
    <section v-else-if="lines.length" class="card-grid" aria-label="경험 목록">
      <button v-for="line in lines" :key="line.id" type="button" class="card" @click="open(line.id)">
        <span class="card__top">
          <span class="card__title">{{ line.title }}</span>
          <span v-if="!line.reviewed" class="chip" data-tone="warning">AI 기록</span>
        </span>
        <span class="card__sub">{{ kindLabels[line.kind] }}{{ line.period ? ` · ${line.period}` : '' }} · 이력서 {{ line.inResumes.length }}</span>
      </button>
    </section>
  </main>

  <Drawer v-if="mode === 'view' && selected" :title="selected.title" :subtitle="`${kindLabels[selected.kind]} · 개정 ${selected.revision}`" @close="close">
    <template #actions>
      <button v-if="!selected.reviewed" type="button" class="button primary compact" @click="review">검토 확정</button>
      <button type="button" class="button secondary compact" @click="drawerError = ''; mode = 'edit'">수정</button>
      <button type="button" class="button danger compact" @click="deleteTarget = selected">삭제</button>
    </template>
    <div v-if="drawerError" class="notice error" role="alert">{{ drawerError }}</div>
    <section class="detail-section detail-chips">
      <span class="chip">{{ kindLabels[selected.kind] }}</span>
      <span v-if="!selected.reviewed" class="chip" data-tone="warning">AI 기록 · 검토 전</span>
      <span v-if="selected.dossierStale" class="chip" data-tone="warning">소재집 갱신 필요</span>
      <span v-for="skill in selected.skills" :key="skill" class="chip">{{ skill }}</span>
      <span v-for="domain in selected.domains" :key="domain" class="chip" data-tone="primary">{{ domain }}</span>
    </section>
    <section class="detail-section">
      <dl class="detail-facts">
        <div><dt>기간</dt><dd>{{ selected.facts?.period || selected.startYm || '—' }}</dd></div>
        <div><dt>역할</dt><dd>{{ selected.facts?.role || '—' }}</dd></div>
        <div v-if="selected.facts?.measurement"><dt>측정 환경·범위</dt><dd>{{ [selected.facts.measurement.environment, selected.facts.measurement.scope].filter(Boolean).join(' / ') }}</dd></div>
        <div><dt>근거</dt><dd>{{ selected.facts?.evidence?.length ?? 0 }}건</dd></div>
      </dl>
    </section>
    <section v-for="[label, list] in ([['한 일', selected.facts?.actions], ['결과', selected.facts?.results], ['미확인', selected.facts?.unverified], ['충돌', selected.facts?.conflicts]] as const)" :key="label" class="detail-section">
      <template v-if="label === '한 일' || label === '결과' || list?.length">
        <p class="label">{{ label }}</p>
        <ul v-if="list?.length" class="body-copy"><li v-for="item in list" :key="item.id ?? item.text">{{ item.text }}</li></ul>
        <p v-else class="body-copy empty">미입력</p>
      </template>
    </section>
    <section class="detail-section">
      <p class="label">쓰는 이력서 · {{ selected.rows.length }}</p>
      <div v-if="selected.rows.length" class="mini-list">
        <button
          v-for="row in selected.rows"
          :key="`${row.resumeId}-${row.section}-${row.index}`"
          type="button"
          class="mini-row"
          title="더블클릭해 이력서 열기"
          @dblclick="openResumeFromRow($event, row.resumeId)"
          @keydown.enter="openResumeFromRow($event, row.resumeId)"
        >
          <span class="mini-row__text"><strong>{{ row.resumeName }}</strong><span>{{ sectionLabels[row.section] ?? row.section }} {{ row.index + 1 }}행 · 개정 {{ row.experienceRevision }}</span></span>
          <span v-if="row.needsReview" class="chip" data-tone="warning">재검토 필요</span>
        </button>
      </div>
      <p v-else class="body-copy empty">이 경험을 쓰는 이력서 행이 없습니다.</p>
    </section>
    <section class="detail-section">
      <p class="label">작성 근거로 쓴 자기소개 · {{ selected.selfIntros.length }}</p>
      <div v-if="selected.selfIntros.length" class="mini-list">
        <button v-for="intro in selected.selfIntros" :key="intro.id" type="button" class="mini-row" title="더블클릭해 자기소개 열기"
          @dblclick="emit('openIntroductions')" @keydown.enter="emit('openIntroductions')">
          <span class="mini-row__text"><strong>{{ intro.question }}</strong><span>개정 {{ intro.experienceRevision }} · {{ intro.trust }}</span></span>
          <span v-if="intro.needsReview" class="chip" data-tone="warning">재검토 필요</span>
        </button>
      </div>
      <p v-else class="body-copy empty">없습니다.</p>
    </section>
    <section v-if="selected.dossier" class="detail-section">
      <p class="label">소재집 (개정 {{ selected.dossierBasedOn ?? '—' }} 기준)</p>
      <p v-if="selected.dossier.narrative" class="body-copy">{{ selected.dossier.narrative }}</p>
      <ul v-if="selected.dossier.questionUses?.length" class="body-copy">
        <li v-for="use in selected.dossier.questionUses" :key="use.competency + use.point">{{ competencyLabels[use.competency] ?? use.competency }}: {{ use.point }}</li>
      </ul>
    </section>
    <section class="detail-section">
      <p class="label">메모</p>
      <p class="body-copy" :class="{ empty: !selected.memo }">{{ selected.memo || '미입력' }}</p>
    </section>
    <section class="detail-section">
      <p class="label">개정 이력</p>
      <ul class="body-copy">
        <li v-for="rev in [...selected.revisions].reverse()" :key="rev.revision">개정 {{ rev.revision }} · {{ rev.changeKind }} · {{ rev.source === 'AI' ? 'AI 기록' : '직접 입력' }}</li>
      </ul>
    </section>
  </Drawer>

  <Drawer v-else-if="mode === 'edit' || mode === 'new'" :title="mode === 'new' ? '경험 추가' : '경험 수정'" :subtitle="selected?.title" @close="mode === 'new' ? close() : (mode = 'view')">
    <template #actions>
      <button type="button" class="button secondary compact" :disabled="saving" @click="mode === 'new' ? close() : (mode = 'view')">취소</button>
      <button type="submit" form="experience-form" class="button primary compact" :disabled="saving">{{ saving ? '저장 중…' : '저장' }}</button>
    </template>
    <div v-if="drawerError" class="notice error" role="alert">{{ drawerError }}</div>
    <ExperienceForm :experience="mode === 'edit' ? selected : null" :skill-suggestions="skillSuggestions" @submit="save" />
  </Drawer>

  <ConfirmDialog
    v-if="deleteTarget"
    title="경험을 삭제할까요?"
    :subject="deleteTarget.title"
    detail=" 경험과 개정 이력이 영구 삭제됩니다. 이력서가 쓰는 경험은 지울 수 없습니다."
    aria-label="경험 삭제 확인"
    :busy="saving"
    @confirm="remove(false)"
    @cancel="deleteTarget = null"
  />
  <ConfirmDialog
    v-if="detachTarget"
    title="자기소개 작성 근거를 끊고 삭제할까요?"
    :subject="detachTarget.title"
    :detail="` 경험을 자기소개 ${detachTarget.selfIntros.length}개가 작성 근거로 가리킵니다. 답변은 그대로 두고 근거만 끊습니다.`"
    confirm-label="근거 끊고 삭제"
    aria-label="작성 근거 끊고 삭제 확인"
    :busy="saving"
    @confirm="remove(true)"
    @cancel="detachTarget = null"
  />
</template>
