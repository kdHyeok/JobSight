<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchLoginOptions } from '../api/auth'
import { AUTH_ERROR_QUERY, googleLoginUrl } from '../routes'

const googleEnabled = ref(false)
const autoApproveSignup = ref(false)
const loadingOptions = ref(true)
const optionsError = ref(false)
const errorMessage = ref('')
const notice = ref('')
const loginUrl = googleLoginUrl()

/** Google 로그인은 리다이렉트로 돌아오므로 결과를 쿼리 파라미터로 받는다. */
const AUTH_ERROR_MESSAGES: Record<string, string> = {
  DOMAIN_CHANGED: '서비스 주소가 변경되었습니다. 이 페이지에서 Google 로그인을 다시 시작해 주세요.',
  ACCOUNT_NOT_APPROVED: '가입 신청이 접수되었습니다. 관리자 승인 후 로그인할 수 있습니다.',
  ACCOUNT_SUSPENDED: '정지된 계정입니다. 관리자에게 문의하세요.',
  EMAIL_NOT_VERIFIED: 'Google 계정의 이메일이 확인되지 않았습니다.',
  INVALID_IDENTITY: 'Google 계정 정보를 확인할 수 없습니다.',
}

async function loadOptions() {
  loadingOptions.value = true
  optionsError.value = false
  try {
    const options = await fetchLoginOptions()
    googleEnabled.value = options.googleEnabled
    autoApproveSignup.value = options.autoApproveSignup
  } catch {
    optionsError.value = true
  } finally {
    loadingOptions.value = false
  }
}

onMounted(() => {
  const code = new URLSearchParams(window.location.search).get(AUTH_ERROR_QUERY)
  if (code) {
    const message = AUTH_ERROR_MESSAGES[code]
    if (code === 'ACCOUNT_NOT_APPROVED') {
      notice.value = message
    } else {
      errorMessage.value = message ?? `Google 로그인에 실패했습니다. (${code})`
    }
    // 새로고침해도 같은 안내가 반복되지 않도록 쿼리에서 지운다.
    window.history.replaceState({}, '', window.location.pathname)
  }

  void loadOptions()
})
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <h1>로그인</h1>
      <p class="auth-lead">
        Google 계정으로 로그인합니다.
        {{ autoApproveSignup
          ? '처음 로그인하면 바로 이용할 수 있습니다.'
          : '처음 로그인하면 가입 신청이 접수되고, 관리자 승인 후 이용할 수 있습니다.' }}
      </p>

      <div v-if="notice" class="notice success" role="status">{{ notice }}</div>
      <div v-if="errorMessage" class="notice error" role="alert">{{ errorMessage }}</div>

      <p v-if="loadingOptions" class="notice" role="status">로그인 설정을 확인하고 있습니다.</p>
      <div v-else-if="optionsError" class="notice error" role="alert">
        <span>로그인 설정을 확인하지 못했습니다.</span>
        <button type="button" @click="loadOptions">다시 시도</button>
      </div>
      <a v-else-if="googleEnabled" class="button google-button" :href="loginUrl">
        <span class="google-mark" aria-hidden="true">G</span>
        Google로 계속하기
      </a>
      <p v-else class="notice error" role="alert">
        Google 로그인이 아직 설정되지 않았습니다. 서버에 클라이언트 자격증명을 등록해야 합니다.
      </p>
    </section>
  </main>
</template>
