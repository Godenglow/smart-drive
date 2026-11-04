<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { clearSession, homeFor, session } from '../session'
const props = defineProps<{ kind: 'forbidden' | 'unavailable' | 'missing' }>()
const router = useRouter()
const title = computed(() => props.kind === 'forbidden' ? '当前身份无法访问此页面' : props.kind === 'unavailable' ? '暂时无法确认登录状态' : '这条路暂时没有抵达点')
function loginAgain() { clearSession(); void router.replace('/login') }
</script>
<template>
  <main class="status-page"><span class="status-number">{{ kind === 'forbidden' ? '403' : kind === 'missing' ? '404' : '↻' }}</span><h1>{{ title }}</h1><p>{{ kind === 'forbidden' ? '乘客与司机拥有各自的工作台，请返回你的首页继续。' : kind === 'unavailable' ? '请检查网络或稍后重试，你的会话暂时保留。' : '请检查地址，或回到首页继续你的旅程。' }}</p><RouterLink class="primary-button" :to="session.account ? homeFor(session.account.role) : kind === 'unavailable' ? '/' : '/login'">{{ kind === 'unavailable' ? '重新连接' : '返回首页' }} <span>↗</span></RouterLink><button v-if="kind === 'unavailable'" class="text-button" @click="loginAgain">使用其他账号登录</button></main>
</template>
