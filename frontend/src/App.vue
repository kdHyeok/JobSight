<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { fetchMe, logout } from './api/auth'
import AdminView from './components/AdminView.vue'
import CompanyWorkspace from './components/CompanyWorkspace.vue'
import LoginView from './components/LoginView.vue'
import PositionBoard from './components/PositionBoard.vue'
import PostingBoard from './components/PostingBoard.vue'
import ResumeBoard from './components/ResumeBoard.vue'
import SelfIntroductionBoard from './components/SelfIntroductionBoard.vue'
import UserMenu from './components/UserMenu.vue'
import PluginGuide from './components/PluginGuide.vue'
import AdminRequestWidget from './components/AdminRequestWidget.vue'
import { FOCUS_QUERY, ROUTES } from './routes'
import type { Me } from './types/auth'

const ANONYMOUS: Me = { authenticated: false, id: null, email: null, displayName: null, role: null }

// 라우트가 몇 개(ROUTES)뿐이라 라우터 라이브러리를 두지 않았다.
// nginx가 모든 경로에 index.html을 돌려주므로 pathname만 보면 된다.
const path = ref(window.location.pathname)
const search = ref(window.location.search)
const me = ref<Me>(ANONYMOUS)
const booting = ref(true)
const bootError = ref('')
const menuOpen = ref(false)
const menuButton = ref<HTMLButtonElement | null>(null)
function closeMenu() { menuOpen.value = false; menuButton.value?.focus() }
function menuKey(event: KeyboardEvent) { if (event.key === 'Escape' && menuOpen.value) closeMenu() }

const isAdmin = computed(() => me.value.role === 'ADMIN')
const onAdminRoute = computed(() => path.value.startsWith(ROUTES.admin))
const onPositionsRoute = computed(() => path.value.startsWith(ROUTES.positions))
const onCompaniesRoute = computed(() => path.value.startsWith(ROUTES.companies))
const onResumesRoute = computed(() => path.value.startsWith(ROUTES.resumes))
const onIntroductionsRoute = computed(() => path.value.startsWith(ROUTES.introductions))
const onPluginRoute = computed(() => path.value === ROUTES.plugin)
/** 다른 화면에서 열어 달라고 넘긴 항목 id. 각 보드가 목록을 읽은 뒤 그 드로어를 연다. */
const focusId = computed(() => new URLSearchParams(search.value).get(FOCUS_QUERY))

function syncPath() {
  menuOpen.value = false
  path.value = window.location.pathname
  search.value = window.location.search
}

/** focus 를 주면 그 화면이 해당 항목의 드로어를 열고 시작한다. */
function navigate(next: string, focus?: string) {
  menuOpen.value = false
  const url = focus ? `${next}?${FOCUS_QUERY}=${focus}` : next
  if (window.location.pathname + window.location.search !== url) {
    window.history.pushState({}, '', url)
  }
  path.value = next
  search.value = focus ? `?${FOCUS_QUERY}=${focus}` : ''
}

async function loadMe() {
  booting.value = true
  bootError.value = ''
  try {
    me.value = await fetchMe()
  } catch {
    bootError.value = '서비스에 연결할 수 없습니다.'
    me.value = ANONYMOUS
  } finally {
    booting.value = false
  }
}

async function signOut() {
  try {
    await logout()
  } finally {
    me.value = ANONYMOUS
    navigate(ROUTES.home)
  }
}

onMounted(() => {
  window.addEventListener('keydown', menuKey)
  window.addEventListener('popstate', syncPath)
  loadMe()
})

onUnmounted(() => {
  window.removeEventListener('keydown', menuKey)
  window.removeEventListener('popstate', syncPath)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <button type="button" class="brand" @click="navigate(ROUTES.home)">
        <span class="brand-mark">J</span>
        JobSight
      </button>

      <!-- 세그먼트에는 성격이 같은 작업 화면만. 관리자는 오른쪽에 따로. -->
      <nav v-if="me.authenticated" class="segmented" aria-label="화면 전환">
        <button
          type="button"
          :class="{ active: !onAdminRoute && !onPositionsRoute && !onCompaniesRoute && !onResumesRoute && !onIntroductionsRoute && !onPluginRoute }"
          @click="navigate(ROUTES.home)"
        >
          채용공고
        </button>
        <button type="button" :class="{ active: onPositionsRoute }" @click="navigate(ROUTES.positions)">
          모집 직무
        </button>
        <button type="button" :class="{ active: onCompaniesRoute }" @click="navigate(ROUTES.companies)">
          기업
        </button>
        <button type="button" :class="{ active: onResumesRoute }" @click="navigate(ROUTES.resumes)">
          이력서
        </button>
        <button type="button" :class="{ active: onIntroductionsRoute }" @click="navigate(ROUTES.introductions)">
          자기소개
        </button>
      </nav>
      <span v-else />

      <div class="topbar-actions">
        <button ref="menuButton" type="button" class="button secondary compact" :aria-expanded="menuOpen" aria-controls="site-menu"
          @click="menuOpen = !menuOpen">☰ 메뉴</button>
        <template v-if="me.authenticated">
          <UserMenu :me="me" @updated="me = $event" @signed-out="signOut" />
        </template>
      </div>
    </header>

    <aside v-if="menuOpen" id="site-menu" class="site-menu" aria-label="사이트 메뉴">
      <div class="site-menu__head"><strong>메뉴</strong><button class="icon-button" type="button" aria-label="메뉴 닫기" @click="closeMenu">×</button></div>
      <nav aria-label="추가 메뉴">
        <button type="button" :aria-current="onPluginRoute ? 'page' : undefined" @click="navigate(ROUTES.plugin)">플러그인 연결 가이드</button>
        <button v-if="isAdmin" type="button" :aria-current="onAdminRoute ? 'page' : undefined" @click="navigate(ROUTES.admin)">관리자</button>
      </nav>
    </aside>

    <PluginGuide v-if="onPluginRoute" />
    <main v-else-if="booting" class="page">
      <div class="loading-card" aria-live="polite">
        <span class="spinner" /> 세션을 확인하는 중입니다.
      </div>
    </main>

    <template v-else-if="!me.authenticated">
      <main v-if="bootError" class="page">
        <div class="notice error" role="alert">
          <span>{{ bootError }}</span>
          <button type="button" @click="loadMe">다시 시도</button>
        </div>
      </main>
      <LoginView v-else />
    </template>

    <template v-else-if="onAdminRoute">
      <AdminView v-if="isAdmin" :me="me" />
      <main v-else class="page">
        <div class="empty-state">
          <strong>관리자 권한이 필요합니다.</strong>
          <p>이 페이지는 관리자 계정만 열 수 있습니다.</p>
          <button type="button" class="button primary" @click="navigate(ROUTES.home)">
            워크스페이스로 이동
          </button>
        </div>
      </main>
    </template>

    <PositionBoard
      v-else-if="onPositionsRoute"
      :focus="focusId"
      @open-posting="navigate(ROUTES.home, $event)"
    />

    <CompanyWorkspace
      v-else-if="onCompaniesRoute"
      :focus="focusId"
      @open-posting="navigate(ROUTES.home, $event)"
    />

    <!-- 이력서는 목록(/resumes)과 편집기(/resumes?focus=<id>)를 한 보드가 맡는다. 라우팅은 여기서. -->
    <ResumeBoard
      v-else-if="onResumesRoute"
      :focus="focusId"
      @open-resume="navigate(ROUTES.resumes, $event)"
      @close-resume="navigate(ROUTES.resumes)"
      @open-introductions="navigate(ROUTES.introductions)"
    />

    <SelfIntroductionBoard v-else-if="onIntroductionsRoute" />

    <PostingBoard
      v-else
      :focus="focusId"
      @open-position="navigate(ROUTES.positions, $event)"
      @open-company="navigate(ROUTES.companies, $event)"
    />
    <AdminRequestWidget v-if="me.authenticated" />
  </div>
</template>
