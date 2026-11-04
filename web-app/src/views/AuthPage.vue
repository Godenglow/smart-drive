<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { ApiError, authApi, type Role } from '../api'
import { homeFor, session, signIn } from '../session'

const route = useRoute()
const router = useRouter()
const registering = computed(() => route.path === '/register')
const phone = ref(typeof route.query.phone === 'string' ? route.query.phone : '')
const password = ref('')
const confirm = ref('')
const role = ref<Role>('PASSENGER')
const visible = ref(false)
const busy = ref(false)
const error = ref('')
const trace = ref('')
const success = ref(route.query.registered === '1' ? '注册成功，请登录你的新账号。' : '')
const phoneError = computed(() => /^1[3-9]\d{9}$/.test(phone.value.trim()) ? '' : '请输入 1[3-9] 开头的 11 位手机号。')

async function submit() {
  if (busy.value) return
  error.value = ''; trace.value = ''; session.notice = ''; success.value = ''
  if (phoneError.value) { error.value = phoneError.value; return }
  if (!password.value.trim()) { error.value = '请输入密码。'; return }
  if (registering.value && (password.value.length < 8 || password.value.length > 64)) { error.value = '密码长度需为 8–64 位。'; return }
  if (registering.value && confirm.value !== password.value) { error.value = '两次输入的密码不一致。'; return }
  busy.value = true
  try {
    if (registering.value) {
      await authApi.register(phone.value.trim(), password.value, role.value)
      password.value = ''; confirm.value = ''
      await router.replace({ path: '/login', query: { phone: phone.value.trim(), registered: '1' } })
    } else {
      await signIn(phone.value.trim(), password.value)
      password.value = ''
      const home = homeFor(session.account?.role)
      // 仅回跳当前角色已知首页，不接收任意 URL。
      const target = route.query.redirect === home ? route.query.redirect : home
      await router.replace(target)
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '操作失败，请稍后重试。'
    trace.value = failure instanceof ApiError ? failure.requestId : ''
  } finally { busy.value = false }
}
</script>

<template>
  <main class="auth-layout">
    <section class="journey-panel" aria-label="品牌介绍">
      <div class="eyebrow"><span></span> YOUR WAY HOME</div>
      <h1>尽兴出发，<br>安心<span>到家。</span></h1>
      <p class="journey-copy">把方向盘交给信任，<br>把今晚的美好留给自己。</p>
      <div class="route-art" aria-hidden="true">
        <div class="map-grid"></div><div class="map-block block-one"></div><div class="map-block block-two"></div><div class="map-block block-three"></div>
        <svg viewBox="0 0 520 270" fill="none"><path d="M-20 210H160Q190 210 190 180V115Q190 85 220 85H340Q365 85 365 60V-15" stroke="#356153" stroke-width="42"/><path d="M-20 210H160Q190 210 190 180V115Q190 85 220 85H340Q365 85 365 60V-15" stroke="#a5d3ae" stroke-width="2" stroke-dasharray="5 8"/><circle cx="95" cy="210" r="9" fill="#d5eda6"/><circle cx="365" cy="28" r="10" fill="#d5eda6"/><circle cx="365" cy="28" r="19" stroke="#d5eda6" opacity=".3"/></svg>
        <span class="map-label label-start">美好一晚</span><span class="map-label label-end">温暖的家</span><span class="car-marker">↗</span>
      </div>
      <div class="journey-bottom"><span class="small-star">✳</span><p>从出发到抵达<br><strong>每一程，都值得被认真对待。</strong></p><span class="edition">01 / 安心同行</span></div>
    </section>

    <section class="auth-panel" :aria-label="registering ? '注册' : '登录'">
      <div class="auth-card">
        <span class="section-kicker">{{ registering ? '开始你的第一程' : '很高兴，再次同行' }}</span>
        <h2>{{ registering ? '创建你的账号' : '欢迎回来' }}<span class="heading-dot">.</span></h2>
        <p class="muted">{{ registering ? '选择你的身份，开启安心出行。' : '登录 SmartDrive，让回家更简单。' }}</p>
        <div class="auth-tabs"><RouterLink to="/login" :class="{ active: !registering }">账号登录</RouterLink><RouterLink to="/register" :class="{ active: registering }">注册账号</RouterLink></div>
        <div v-if="session.notice" class="notice" role="status">{{ session.notice }}</div>
        <div v-if="success" class="notice success" role="status">{{ success }}</div>
        <form novalidate @submit.prevent="submit">
          <fieldset v-if="registering" class="role-picker" :disabled="busy">
            <legend>我想成为</legend>
            <label :class="{ selected: role === 'PASSENGER' }"><input v-model="role" type="radio" value="PASSENGER" name="role">乘客 <span>安心出行</span></label>
            <label :class="{ selected: role === 'DRIVER' }"><input v-model="role" type="radio" value="DRIVER" name="role">司机 <span>从容接单</span></label>
          </fieldset>
          <label class="field" for="phone">手机号<input id="phone" v-model="phone" type="tel" inputmode="numeric" autocomplete="tel" maxlength="11" placeholder="请输入 11 位手机号" :disabled="busy" :aria-invalid="Boolean(error && phoneError)" required></label>
          <div class="field"><label for="password">密码</label><span class="password-wrap"><input id="password" v-model="password" :type="visible ? 'text' : 'password'" :autocomplete="registering ? 'new-password' : 'current-password'" :placeholder="registering ? '设置 8–64 位密码' : '请输入你的密码'" :maxlength="registering ? 64 : undefined" :disabled="busy" required><button type="button" class="password-toggle" :aria-label="visible ? '隐藏密码' : '显示密码'" :aria-pressed="visible" @click="visible = !visible">{{ visible ? '隐藏' : '显示' }}</button></span></div>
          <label v-if="registering" class="field" for="confirm">确认密码<input id="confirm" v-model="confirm" type="password" autocomplete="new-password" placeholder="再次输入密码" maxlength="64" :disabled="busy" required></label>
          <div v-if="error" class="error-box" role="alert">{{ error }}<small v-if="trace">请求编号：{{ trace }}</small></div>
          <button type="submit" class="primary-button" :disabled="busy">{{ busy ? '正在处理…' : registering ? '创建账号' : '登录' }}<span v-if="!busy" aria-hidden="true">↗</span></button>
          <p class="session-hint">{{ registering ? '注册后身份不可自行切换，请确认你的选择。' : '仅在当前标签页保留会话，请勿在公共设备上保存密码。' }}</p>
        </form>
        <div class="auth-footnote"><span aria-hidden="true">◇</span> 你的身份与每一次出行，都被认真守护。</div>
      </div>
    </section>
  </main>
</template>
