<script setup lang="ts">
import { onMounted, ref } from 'vue'
import * as mediaApi from '@/api/media'
import { errorMessage } from '@/api/http'

const years = ref<mediaApi.YearMonths[]>([])
const error = ref('')
const loading = ref(true)

onMounted(async () => {
  try {
    years.value = await mediaApi.listAlbumDates()
  } catch (e) {
    error.value = errorMessage(e, '加载相册失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="albums">
    <div class="header">
      <h1 class="title">日期相册</h1>
      <router-link to="/media"><el-button>查看时间线</el-button></router-link>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-for="year in years" :key="year.year" class="year">
      <h2>{{ year.year }} 年</h2>
      <div class="months">
        <div v-for="month in year.months" :key="month.period" class="month">
          <div class="label">{{ month.period.slice(5) }} 月</div>
          <div class="count">{{ month.count }} 项</div>
        </div>
      </div>
    </div>
    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="!error && years.length === 0" class="hint">暂无相册</div>
  </div>
</template>

<style scoped>
.albums {
  padding: 1rem;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}
.title {
  font-size: 1.15rem;
  margin: 0.5rem 0;
}
.year h2 {
  font-size: 1.05rem;
  margin: 1rem 0 0.5rem;
}
.months {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 0.5rem;
}
.month {
  display: block;
  padding: 0.75rem;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  color: inherit;
}
.month .label {
  font-weight: 600;
}
.month .count {
  color: #909399;
  font-size: 0.8rem;
}
.hint {
  color: #909399;
  padding: 1rem 0;
}
</style>
