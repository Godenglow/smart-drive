<script setup lang="ts">
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { clearSession, homeFor, session } from './session'
const router = useRouter()
function logout() { clearSession('你已安全退出。'); void router.replace('/login') }
</script>

<template>
  <header class="site-header">
    <RouterLink class="brand" :to="session.account ? homeFor(session.account.role) : '/login'" aria-label="SmartDrive 首页">
      <span class="brand-mark" aria-hidden="true">S<span>↗</span></span><span>SmartDrive<small>安心代驾</small></span>
    </RouterLink>
    <div class="header-right">
      <span class="service-note"><i></i> 每一次出发，都安心</span>
      <button v-if="session.account" class="text-button" @click="logout">退出登录</button>
      <RouterLink v-else class="header-link" :to="$route.path === '/register' ? '/login' : '/register'">{{ $route.path === '/register' ? '已有账号？登录' : '新朋友？注册账号' }} <span aria-hidden="true">↗</span></RouterLink>
    </div>
  </header>
  <RouterView :key="$route.path" />
  <footer class="site-footer"><span>SMARTDRIVE · 让回家的路更从容</span><span>本地演示平台 <span class="footer-dot">·</span> 模拟服务，无真实支付</span></footer>
</template>
