<script setup lang="ts">
import { onMounted, ref } from 'vue'
import * as mediaApi from '@/api/media'
import { errorMessage } from '@/api/http'

const years = ref<mediaApi.YearMonths[]>([])
const error = ref('')

onMounted(async () => {
  try {
    years.value = await mediaApi.listAlbumDates()
  } catch (e) {
    error.value = errorMessage(e, '加载相册失败')
  }
})
</script>

<template>
  <div class="albums">
    <router-link to="/media"><el-button text>← 返回时间线</el-button></router-link>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-for="year in years" :key="year.year" class="year">
      <h2>{{ year.year }} 年</h2>
      <div class="months">
        <router-link
          v-for="month in year.months"
          :key="month.period"
          :to="{ path: '/media', query: { month: month.period } }"
          class="month"
        >
          <div class="label">{{ month.period.slice(5) }} 月</div>
          <div class="count">{{ month.count }} 项</div>
        </router-link>
      </div>
    </div>
    <div v-if="!error && years.length === 0" class="hint">暂无相册</div>
  </div>
</template>

<style scoped>
.albums {
  padding: 1rem;
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
  text-decoration: none;
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
