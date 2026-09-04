<script setup lang="ts">
// M2 占位页:后续接入 vue-router(登录/文件/媒体/影音/管理)与 Pinia
</script>

<template>
  <main class="splash">
    <h1>PNAS</h1>
    <p class="tagline">Personal NAS · 自研进行中(M2 MVP)</p>
    <ul class="hints">
      <li>架构:Java 21 + Spring Boot + PostgreSQL + nginx + Vue 3</li>
      <li>规划:文件/照片视频/影音/集中备份</li>
    </ul>
  </main>
</template>

<style scoped>
.splash {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  color: #2c3e50;
}
h1 {
  font-size: 3rem;
  margin: 0;
  letter-spacing: 0.4rem;
}
.tagline {
  color: #7f8c8d;
}
.hints {
  color: #95a5a6;
  font-size: 0.85rem;
}
</style>
