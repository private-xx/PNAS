<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as jobsApi from '@/api/jobs'
import * as mediaApi from '@/api/media'
import { errorMessage } from '@/api/http'

const AUTO_REFRESH_MS = 5000

const jobs = ref<jobsApi.JobDto[]>([])
const state = ref('')
const loading = ref(false)
const reindexing = ref(false)
let timer: number | undefined
let inFlight = false

const stateOptions: { label: string; value: jobsApi.JobState }[] = [
  { label: '排队中', value: 'QUEUED' },
  { label: '执行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' },
  { label: '死信', value: 'DEAD' },
]

const stateLabels: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败',
  DEAD: '死信',
}

const stateTagTypes: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  QUEUED: 'info',
  RUNNING: 'primary',
  SUCCESS: 'success',
  FAILED: 'warning',
  DEAD: 'danger',
}

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString()
}

async function reload(silent = false): Promise<void> {
  // 自动刷新不显示 loading 遮罩,避免表格每 5 秒闪烁一次。
  if (inFlight) return
  inFlight = true
  if (!silent) loading.value = true
  try {
    jobs.value = await jobsApi.listJobs(state.value || undefined)
  } catch (e) {
    if (!silent) ElMessage.error(errorMessage(e, '加载任务失败'))
  } finally {
    inFlight = false
    if (!silent) loading.value = false
  }
}

async function retry(id: string): Promise<void> {
  try {
    await jobsApi.retryJob(id)
    ElMessage.success('已重试')
    await reload()
  } catch (e) {
    ElMessage.error(errorMessage(e, '重试失败'))
  }
}

async function cancel(id: string): Promise<void> {
  try {
    await jobsApi.cancelJob(id)
    ElMessage.success('已取消')
    await reload()
  } catch (e) {
    ElMessage.error(errorMessage(e, '取消失败'))
  }
}

async function reindex(): Promise<void> {
  reindexing.value = true
  try {
    const { jobId } = await mediaApi.reindexMedia()
    ElMessage.success(`已触发重建媒体索引(${jobId.slice(0, 8)}…)`)
    await reload()
  } catch (e) {
    // 非管理员会收到 403,优先展示后端返回的消息。
    ElMessage.error(errorMessage(e, '重建媒体索引失败(需要管理员权限)'))
  } finally {
    reindexing.value = false
  }
}

onMounted(() => {
  void reload()
  timer = window.setInterval(() => void reload(true), AUTO_REFRESH_MS)
})

onUnmounted(() => {
  window.clearInterval(timer)
})
</script>

<template>
  <div class="jobs">
    <div class="toolbar">
      <el-select v-model="state" placeholder="全部状态" style="width: 160px" @change="reload()">
        <el-option label="全部" value="" />
        <el-option v-for="option in stateOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-button :loading="loading" @click="reload()">刷新</el-button>
      <el-button type="primary" :loading="reindexing" @click="reindex">重建媒体索引</el-button>
      <span class="auto-hint">每 5 秒自动刷新</span>
    </div>

    <el-table v-loading="loading" :data="jobs" row-key="id" size="small" empty-text="暂无任务">
      <el-table-column prop="type" label="类型" width="180" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="stateTagTypes[row.state] ?? 'info'" size="small">
            {{ stateLabels[row.state] ?? row.state }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="尝试" width="90">
        <template #default="{ row }">{{ row.attempt }}/{{ row.maxAttempts }}</template>
      </el-table-column>
      <el-table-column label="下次执行" width="190">
        <template #default="{ row }">{{ formatTime(row.nextRunAt) }}</template>
      </el-table-column>
      <el-table-column label="错误" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.error || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.state === 'FAILED' || row.state === 'DEAD'"
            size="small"
            text
            type="primary"
            @click="retry(row.id)"
          >
            重试
          </el-button>
          <el-button
            v-if="row.state === 'QUEUED' || row.state === 'RUNNING'"
            size="small"
            text
            type="danger"
            @click="cancel(row.id)"
          >
            取消
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.jobs {
  padding: 1rem;
  max-width: 100%;
  overflow-x: auto;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}
.auto-hint {
  color: #909399;
  font-size: 0.8rem;
}
</style>
