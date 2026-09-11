<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useSessionStore } from '@/stores/session'
import { errorMessage } from '@/api/http'

const session = useSessionStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  loading.value = true
  try {
    await session.login(username.value, password.value)
    const redirect = (route.query.redirect as string) || '/'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(errorMessage(error, '登录失败'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <el-card class="login-card">
      <h1 class="title">PNAS</h1>
      <p class="subtitle">个人 NAS · 家庭私有存储</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            placeholder="密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="submit">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-card {
  width: 360px;
}
.title {
  margin: 0;
  text-align: center;
  letter-spacing: 0.3rem;
}
.subtitle {
  margin: 0.4rem 0 1.2rem;
  text-align: center;
  color: #909399;
  font-size: 0.85rem;
}
.submit {
  width: 100%;
}
</style>
