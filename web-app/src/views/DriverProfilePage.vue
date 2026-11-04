<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ApiError } from '../api'
import { driverApi } from '../drivers'
import { usePolling } from '../polling'
const name = ref('')
const dirty = ref(false)
const busy = ref(false)
const message = ref('')
const failure = ref('')
const requestId = ref('')
const { loading, error, trace, refresh } = usePolling(async signal => {
  const state = await driverApi.state(signal)
  if (!signal.aborted && !dirty.value && !busy.value) name.value = state.profile?.displayName || ''
}, () => false)
async function save() {
  if (busy.value) return
  failure.value = ''; message.value = ''; requestId.value = ''
  if (!name.value.trim() || name.value.trim().length > 64) { failure.value = '司机称呼需为 1–64 字。'; return }
  busy.value = true
  try { const result = await driverApi.profile(name.value.trim()); name.value = result.displayName; dirty.value = false; message.value = '司机资料已保存，可以返回工作台上线。' }
  catch (err) { failure.value = err instanceof Error ? err.message : '保存失败，请重试。'; requestId.value = err instanceof ApiError ? err.requestId : '' }
  finally { busy.value = false }
}
</script>
<template>
  <main class="workspace order-workspace">
    <nav class="order-nav" aria-label="司机导航"><RouterLink to="/driver">← 司机工作台</RouterLink><RouterLink to="/driver/orders">我的订单 →</RouterLink></nav>
    <div class="page-heading"><span class="section-kicker">DRIVER PROFILE</span><h1>司机资料<span class="heading-dot">.</span></h1><p class="muted">先保存称呼，再上线接单。</p></div>
    <div v-if="error" class="error-box" role="alert">{{ error }}<small v-if="trace">请求编号：{{ trace }}</small><button class="text-button" :disabled="loading" @click="refresh">重新读取</button></div>
    <form class="order-panel driver-profile" novalidate @submit.prevent="save">
      <label class="field">司机称呼<input v-model="name" :disabled="busy || loading" maxlength="64" autocomplete="nickname" @input="dirty = true; message = ''"></label>
      <p class="muted">用于本地演示的司机资料，请填写 1–64 字。</p>
      <div v-if="failure" class="error-box" role="alert">{{ failure }}<small v-if="requestId">请求编号：{{ requestId }}</small></div>
      <p v-if="message" class="notice success" role="status">{{ message }}</p>
      <button class="primary-button" :disabled="busy || loading">{{ busy ? '正在保存…' : '保存司机资料' }} <span>↗</span></button>
    </form>
  </main>
</template>
