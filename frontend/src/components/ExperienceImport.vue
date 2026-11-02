<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiClientError } from '../api/http'
import { applyImport, getImportPlan } from '../api/experiences'
import {
  kindLabels,
  type ExperienceLine,
  type ImportAction,
  type ImportPlan,
  type ImportResult,
} from '../types/experience'

/**
 * 이력서의 미연결 경험 행을 경험 원본으로 가져온다(설계 6.3). 계획(dry-run) → 묶음마다 CREATE·SKIP·LINK_TO → 적용.
 * 서버는 행 문구를 바꾸지 않고 앵커만 쓴다. 계획 뒤에 이력서가 바뀌면 409 IMPORT_PLAN_STALE — 계획을 다시 받는다.
 * 같은 경험일 수 있는 묶음(mergeCandidates)은 보여 주기만 한다. 합치기(병합)는 4c 라 없다.
 */
const props = defineProps<{ experiences: ExperienceLine[] }>()
const emit = defineEmits<{ done: [result: ImportResult]; close: [] }>()

const plan = ref<ImportPlan | null>(null)
const loading = ref(true)
const applying = ref(false)
const message = ref('')
const decisions = reactive<Record<string, { action: ImportAction; experienceId: string }>>({})

const titleOf = computed(() => new Map((plan.value?.groups ?? []).map((group) => [group.key, group.title])))
const sameKind = (kind: string) => props.experiences.filter((line) => line.kind === kind)
const ready = computed(() => !!plan.value?.groups.length
  && plan.value.groups.every((group) => decisions[group.key]?.action !== 'LINK_TO' || !!decisions[group.key].experienceId))

async function load(notice = '') {
  loading.value = true
  message.value = notice
  try {
    plan.value = await getImportPlan()
    for (const key of Object.keys(decisions)) delete decisions[key]
    for (const group of plan.value.groups) decisions[group.key] = { action: 'CREATE', experienceId: '' }
  } catch (error) {
    message.value = error instanceof Error ? error.message : '가져오기 계획을 만들지 못했습니다.'
  } finally {
    loading.value = false
  }
}

/** 서버 결정 상한(ExperienceRequests.Import 의 @Size(max = 500)). */
const MAX_DECISIONS = 500

async function apply() {
  if (!plan.value || !ready.value) return
  // 건너뛰기는 보내지 않는다(빠진 묶음은 서버가 손대지 않는다). 그래야 묶음이 500개를 넘어도 나눠서 가져올 수 있다.
  const chosen = plan.value.groups.filter((group) => decisions[group.key].action !== 'SKIP')
  if (chosen.length > MAX_DECISIONS) {
    message.value = `한 번에 ${MAX_DECISIONS}묶음까지 가져올 수 있습니다(지금 ${chosen.length}묶음). 나머지는 건너뛰기로 두고 적용한 뒤 다시 가져오세요.`
    return
  }
  const skipped = plan.value.groups.length - chosen.length
  applying.value = true
  message.value = ''
  try {
    const result = await applyImport(plan.value.resumeVersions, chosen.map((group) => {
      const decision = decisions[group.key]
      return decision.action === 'LINK_TO'
        ? { key: group.key, action: decision.action, experienceId: decision.experienceId }
        : { key: group.key, action: decision.action }
    }))
    emit('done', { ...result, skipped: result.skipped + skipped })
  } catch (error) {
    if (error instanceof ApiClientError && error.status === 409) {
      // 계획 뒤에 이력서가 바뀌었다. 고른 결정은 새 계획과 맞지 않을 수 있어 다시 고르게 한다.
      await load('계획을 만든 뒤 이력서가 바뀌었습니다. 새 계획으로 다시 골라 주세요.')
    } else {
      message.value = error instanceof Error ? error.message : '가져오지 못했습니다.'
    }
  } finally {
    applying.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <section class="resume-section" aria-label="경험 가져오기">
    <div class="resume-section__head">
      <h2>이력서에서 가져오기 <span class="page-count">{{ plan?.groups.length ?? 0 }}</span></h2>
      <div class="page-tools">
        <button type="button" class="button secondary compact" :disabled="applying" @click="emit('close')">닫기</button>
        <button type="button" class="button primary compact" :disabled="applying || loading || !ready" @click="apply">
          {{ applying ? '가져오는 중…' : '적용' }}
        </button>
      </div>
    </div>
    <p class="body-copy">이력서에서 아직 경험 원본에 연결되지 않은 행을 같은 (종류, 제목, 시작 연월)끼리 묶었습니다. 행의 문구는 바뀌지 않습니다.</p>
    <div v-if="message" class="notice error" role="alert">{{ message }}</div>
    <div v-if="loading" class="loading-card" aria-live="polite"><span class="spinner" /> 계획을 만드는 중입니다.</div>
    <p v-else-if="!plan?.groups.length" class="body-copy empty">가져올 행이 없습니다.</p>
    <div v-else class="mini-list">
      <article v-for="group in plan.groups" :key="group.key" class="resume-row" :data-key="group.key">
        <div class="resume-row__bar">
          <strong>{{ group.title }}</strong>
          <span class="card__chips">
            <span class="chip">{{ kindLabels[group.kind] }}</span>
            <span v-if="group.period" class="chip">{{ group.period }}</span>
          </span>
        </div>
        <p class="body-copy">{{ group.rows.map((row) => `${row.resumeName} · ${row.section} ${row.index + 1}`).join(', ') }}</p>
        <p v-if="group.mergeCandidates.length" class="form-hint" role="note">
          같은 경험일 수 있음: {{ group.mergeCandidates.map((key) => titleOf.get(key) ?? key).join(', ') }}
          — 하나만 만들고 나머지는 건너뛴 뒤, 다시 가져오기에서 "기존 경험에 연결"로 붙이세요.
        </p>
        <div class="form-grid">
          <label class="field">
            <span>처리</span>
            <select v-model="decisions[group.key].action" :aria-label="`${group.title} 처리`">
              <option value="CREATE">새 경험으로 만들기</option>
              <option value="LINK_TO" :disabled="!sameKind(group.kind).length">기존 경험에 연결</option>
              <option value="SKIP">건너뛰기</option>
            </select>
          </label>
          <label v-if="decisions[group.key].action === 'LINK_TO'" class="field">
            <span>연결할 경험</span>
            <select v-model="decisions[group.key].experienceId" :aria-label="`${group.title} 연결할 경험`">
              <option value="">선택…</option>
              <option v-for="line in sameKind(group.kind)" :key="line.id" :value="line.id">{{ line.title }}{{ line.period ? ` (${line.period})` : '' }}</option>
            </select>
          </label>
        </div>
      </article>
    </div>
  </section>
</template>
