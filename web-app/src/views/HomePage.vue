<script setup lang="ts">
import { computed } from 'vue'
import { session } from '../session'
const driver = computed(() => session.account?.role === 'DRIVER')
const phone = computed(() => session.account?.phone.replace(/(\d{3})\d{4}(\d{4})/, '$1 **** $2'))
</script>

<template>
  <main class="workspace">
    <div class="workspace-label"><span class="live-dot"></span> {{ driver ? '司机工作台' : '乘客首页' }}</div>
    <section class="welcome-banner">
      <div><span class="eyebrow">{{ driver ? 'READY FOR THE ROAD' : 'A GOOD WAY HOME' }}</span><h1>{{ driver ? '你好，安心摆渡人。' : '下一程，安心出发。' }}</h1><p>{{ driver ? '欢迎来到司机工作台。你的每一次认真，都让归途更温暖。' : '欢迎来到 SmartDrive。美好的一天，从从容容出发。' }}</p></div>
      <span class="welcome-symbol" aria-hidden="true">↗</span>
    </section>
    <div class="workspace-grid">
      <section class="account-card"><span class="section-kicker">我的账号</span><h2>{{ driver ? '司机' : '乘客' }}身份已就绪</h2><dl><div><dt>手机号</dt><dd>{{ phone }}</dd></div><div><dt>账号编号</dt><dd>{{ session.account?.userId }}</dd></div><div><dt>当前身份</dt><dd><span class="role-badge">{{ driver ? '司机 DRIVER' : '乘客 PASSENGER' }}</span></dd></div></dl><p class="muted">{{ driver ? '只向你开放司机专属工作台。' : '只向你开放乘客专属首页。' }}</p></section>
      <section class="next-card"><span class="section-kicker">接下来的一程</span><h2>{{ driver ? '认真服务，从这里开始' : '你的出行，即将启程' }}</h2><p>{{ driver ? '司机资料、上线与位置心跳、待抢订单和履约操作将在后续接入。' : '下单、订单列表、行程详情与模拟支付将在后续接入。' }}</p><div class="coming-soon"><span aria-hidden="true">◷</span>{{ driver ? '司机服务 · 准备中' : '乘客服务 · 准备中' }}</div><small>当前已开放账号注册、登录与身份验证。</small></section>
    </div>
  </main>
</template>
