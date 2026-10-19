<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { MCP_ENDPOINTS, PLUGIN_LINKS } from '../routes'

type Config = { mcpUrl: string }
const config = ref<Config | null>(null)
const loading = ref(false)
const error = ref('')
const notice = ref('')
const commandNotice = ref('')
const installCommand = 'codex plugin marketplace add kdHyeok/jobsite-v006 --ref main\ncodex plugin add jobsight@jobsight'
const updateCommand = 'codex plugin marketplace upgrade jobsight\ncodex plugin add jobsight@jobsight'
async function copyCommand(command: string) {
  try { await navigator.clipboard.writeText(command); commandNotice.value = '명령을 복사했습니다.' }
  catch { commandNotice.value = '자동 복사가 차단되었습니다. 명령을 선택해 직접 복사해 주세요.' }
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetch(MCP_ENDPOINTS.config)
    if (!response.ok) throw new Error()
    const value = await response.json() as Config
    if (typeof value.mcpUrl !== 'string') throw new Error()
    config.value = value
  } catch {
    error.value = '연결 설정을 불러오지 못했습니다. 서버의 플러그인 기능 배포 상태를 확인해 주세요.'
  } finally { loading.value = false }
}
async function copyUrl() {
  if (!config.value) return
  try {
    await navigator.clipboard.writeText(config.value.mcpUrl)
    notice.value = 'MCP URL을 복사했습니다.'
  } catch { notice.value = '자동 복사가 차단되었습니다. 아래 URL을 선택해 직접 복사해 주세요.' }
}
onMounted(load)
</script>

<template>
  <main class="page plugin-guide">
    <div class="page-head"><div class="page-title"><h1>플러그인 연결</h1></div></div>
    <p>JobSight의 기업 조사와 채용 지원 정보를 ChatGPT에서 관리하세요.</p>
    <section class="guide-card" aria-labelledby="guide-connect">
      <h2 id="guide-connect">1. MCP URL 복사</h2>
      <p>이 서버의 주소 하나로 OAuth 설정을 찾습니다. 저장소 공개나 Google 시크릿 입력은 필요 없습니다.</p>
      <p v-if="loading" role="status">연결 설정을 불러오는 중입니다.</p>
      <div v-if="error" class="notice error" role="alert">
        {{ error }} <button class="button secondary compact" type="button" @click="load">다시 시도</button>
      </div>
      <template v-if="config">
        <label class="field"><span>MCP 서버 URL</span><input :value="config.mcpUrl" readonly @focus="($event.target as HTMLInputElement).select()" /></label>
        <button type="button" class="button primary" @click="copyUrl">MCP URL 복사</button>
      </template>
      <p class="guide-notice" aria-live="polite">{{ notice }}</p>
    </section>
    <section class="guide-card" aria-labelledby="guide-oauth">
      <h2 id="guide-oauth">2. ChatGPT에서 OAuth 연결</h2>
      <p>이전 서비스 주소로 연결했다면 위 MCP URL로 새 연결을 만들고 다시 인증해 주세요. 새 연결이 동작하면 이전 연결을 제거할 수 있습니다.</p>
      <ol>
        <li>ChatGPT 설정에서 개발자 모드를 켜고 앱/플러그인 연결 추가를 엽니다.</li>
        <li>이름은 JobSight, MCP 서버 URL은 복사한 주소를 입력합니다.</li>
        <li>Google 계정으로 로그인하고 권한 동의를 완료합니다.</li>
      </ol>
      <a :href="PLUGIN_LINKS.docs" target="_blank" rel="noopener noreferrer">공식 연결 안내 ↗</a>
    </section>
    <section class="guide-card" aria-labelledby="guide-skill">
      <h2 id="guide-skill">3. Codex 플러그인 설치 — GitHub에서 바로</h2>
      <p>Codex CLI가 설치된 터미널에서 실행하세요.</p>
      <pre class="guide-command"><code>{{ installCommand }}</code></pre>
      <button type="button" class="button primary" @click="copyCommand(installCommand)">설치 명령 복사</button>
      <h3>최신 버전으로 업데이트</h3>
      <pre class="guide-command"><code>{{ updateCommand }}</code></pre>
      <button type="button" class="button secondary" @click="copyCommand(updateCommand)">업데이트 명령 복사</button>
      <p aria-live="polite">{{ commandNotice }}</p>
      <p>설치 후 Codex를 재시작하고 새 대화에서 연결을 확인하세요.</p>
    </section>
    <section class="guide-card" aria-labelledby="guide-test">
      <h2 id="guide-test">4. 새 대화에서 확인</h2>
      <p>JobSight를 도구로 선택하고 먼저 읽기 작업으로 확인하세요.</p>
      <blockquote>JobSight 사용 지침과 연결 계정을 확인하고, 내 기업 목록을 조회해줘. 데이터는 변경하지 마.</blockquote>
      <p>기업·복지, 공고·상태·절차, 직무·역량, 직무 참고 정보, 기업 뉴스·유튜브의 조회·추가·수정·삭제를 지원합니다. 삭제는 연결된 데이터에도 영향을 주므로 대상을 확인하세요.</p>
    </section>
  </main>
</template>
