<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const isLoginPage = computed(() => route.name === 'login')

async function logout() {
  await session.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="app">
    <header v-if="!isLoginPage" class="topbar">
      <div class="brand">PNAS</div>
      <div class="spacer" />
      <span class="user">{{ session.displayName || session.username }}</span>
      <el-button size="small" text @click="logout">退出</el-button>
    </header>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.6rem 1rem;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}
.brand {
  font-weight: 700;
  letter-spacing: 0.2rem;
}
.spacer {
  flex: 1;
}
.user {
  color: #606266;
  font-size: 0.9rem;
}
.content {
  flex: 1;
}
</style>
