<script setup lang="ts">
import type { ApplicationStatus, JobPosting, StepResult } from '../types/posting'
import {
  employmentTypeLabels,
  formatDeadlineParts,
  nextStepResult,
  selectableStatuses,
  statusLabels,
  stepResultLabels,
} from '../types/posting'
import MarkdownMemo from './MarkdownMemo.vue'
import SubmissionRecords from './SubmissionRecords.vue'
import { opensEditor } from '../utils/doubleClick'

// 수정·보관·삭제는 드로어 헤더가 가진다. 여기서 바로 바꾸는 건 내 상태와 절차 결과 — 가장 잦은 조작.
defineProps<{ posting: JobPosting }>()
const emit = defineEmits<{
  changeStatus: [ApplicationStatus]
  changeStep: [seq: number, result: StepResult]
  /** 모집 직무 행을 더블클릭하면 모집 직무 화면에서 그 직무를 연다. */
  openPosition: [positionId: string]
  /** 역방향 — 이 공고를 낸 기업으로 건너뛴다. */
  openCompany: [companyId: string]
  edit: []
}>()

function editFromDoubleClick(event: MouseEvent) {
  if (opensEditor(event)) emit('edit')
}

const formatDate = (value: string | null) =>
  value ? new Intl.DateTimeFormat('ko-KR', { month: 'short', day: 'numeric' }).format(new Date(value)) : ''
</script>

<template>
  <div title="더블클릭해 채용공고 수정" @dblclick="editFromDoubleClick">
    <section class="detail-section">
      <label class="field">
        <span>내 상태</span>
        <select
          :value="posting.status"
          @change="emit('changeStatus', ($event.target as HTMLSelectElement).value as ApplicationStatus)"
        >
          <option v-if="posting.status === 'CLOSED'" value="CLOSED" disabled>종료 (기존)</option>
          <option v-for="value in selectableStatuses" :key="value" :value="value">{{ statusLabels[value] }}</option>
        </select>
      </label>
    </section>

    <SubmissionRecords v-if="posting.status !== 'INTERESTED' && posting.status !== 'DRAFTING'" :posting-id="posting.id" :revision="posting.updatedAt" />

    <section class="detail-section">
      <p class="label">채용 절차 <small>노드를 누르면 결과가 바뀝니다</small></p>
      <p v-if="posting.steps.length === 0" class="body-copy empty">입력된 절차가 없습니다. 수정에서 추가하세요.</p>
      <ol v-else class="step-chain">
        <li v-for="step in posting.steps" :key="step.seq">
          <button
            type="button"
            class="step-node"
            :data-result="step.result"
            :title="stepResultLabels[step.result]"
            @click="emit('changeStep', step.seq, nextStepResult(step.result))"
          >
            <strong>{{ step.name }}</strong>
            <span>{{ stepResultLabels[step.result] }}<template v-if="step.scheduledAt"> · {{ formatDate(step.scheduledAt) }}</template></span>
          </button>
        </li>
      </ol>
    </section>

    <section class="detail-section">
      <dl class="detail-facts">
        <div>
          <dt>고용회사</dt>
          <dd>
            <button type="button" class="entity-link" @click="emit('openCompany', posting.companyId)">
              <span class="entity-icon" aria-hidden="true">▦</span>
              <span>{{ posting.companyName ?? '회사 미입력' }}</span>
              <span aria-hidden="true">↗</span>
            </button>
          </dd>
        </div>
        <div>
          <dt>서류마감</dt>
          <dd class="deadline-text">
            <span>{{ formatDeadlineParts(posting.deadlineAt).year }}</span>
            <strong>{{ formatDeadlineParts(posting.deadlineAt).emphasized }}</strong>
          </dd>
        </div>
        <div>
          <dt>고용형태</dt>
          <dd>{{ employmentTypeLabels[posting.employmentType] }}</dd>
        </div>
        <div>
          <dt>공고링크</dt>
          <dd>
            <a v-if="posting.postingUrl" :href="posting.postingUrl" target="_blank" rel="noreferrer">열기 ↗</a>
            <span v-else>—</span>
          </dd>
        </div>
      </dl>
    </section>

    <section class="detail-section">
      <p class="label">모집 직무 · {{ posting.positions.length }} <small>더블클릭하면 직무가 열립니다</small></p>
      <div class="mini-list">
        <button
          v-for="position in posting.positions"
          :key="position.id"
          type="button"
          class="mini-row"
          title="더블클릭하면 모집 직무 화면에서 열립니다"
          @dblclick="emit('openPosition', position.id)"
          @keydown.enter.prevent="emit('openPosition', position.id)"
        >
          <span class="mini-row__text">
            <strong>{{ position.name }}</strong>
            <span>{{ [position.team, position.headcount, position.workLocation].filter(Boolean).join(' · ') || '상세는 모집 직무 탭에서' }}</span>
          </span>
          <span v-if="posting.targetPositionId === position.id" class="chip" data-tone="primary">지원</span>
        </button>
      </div>
    </section>

    <section class="detail-section">
      <p class="label">지원자격</p>
      <p class="body-copy" :class="{ empty: !posting.qualifications }">
        {{ posting.qualifications || '미입력' }}
      </p>
    </section>

    <section class="detail-section">
      <p class="label">공고 메모</p>
      <MarkdownMemo :source="posting.memo" empty="아직 공고 메모가 없습니다." />
    </section>
  </div>
</template>
