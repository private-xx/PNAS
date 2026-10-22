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
let reloadInFlight = false
let pendingReload: { silent: boolean } | null = null
let disposed = false

const stateOptions: { label: string; value: jobsApi.JobState }[] = [
  { label: '排队中', value: 'QUEUED' },
  { label: '执行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' },
  { label: '死信', value: 'DEAD' },
]

const stateLabels: Record<jobsApi.JobState, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败',
  DEAD: '死信',
}

const stateTagTypes: Record<jobsApi.JobState, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  QUEUED: 'info',
  RUNNING: 'primary',
  SUCCESS: 'success',
  FAILED: 'warning',
  DEAD: 'danger',
}

function stateLabel(value: jobsApi.JobState): string {
  return stateLabels[value]
}

function stateTagType(value: jobsApi.JobState): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  return stateTagTypes[value]
}

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString()
}

async function reload(silent = false): Promise<void> {
  if (disposed) return
  // 已有请求在飞:排队一次(非静默优先),等它结束后立即补拉,避免刷新被丢弃。
  if (reloadInFlight) {
    pendingReload = { silent: (pendingReload?.silent ?? true) && silent }
    return
  }
  reloadInFlight = true
  const requestedState = state.value
  // 自动刷新不显示 loading 遮罩,避免表格每 5 秒闪烁一次。
  if (!silent) loading.value = true
  try {
    const result = await jobsApi.listJobs(requestedState || undefined)
    // 筛选在请求期间被切换时丢弃旧结果,由排队的 reload 按新筛选拉取。
    if (requestedState === state.value) {
      jobs.value = result
    }
  } catch (e) {
    if (!silent) ElMessage.error(errorMessage(e, '加载任务失败'))
  } finally {
    reloadInFlight = false
    if (!silent) loading.value = false
    const pending = pendingReload
    pendingReload = null
    if (pending && !disposed) {
      void reload(pending.silent)
    }
  }
}

/** 用接口返回的最新任务替换表格中的对应行,避免刷新排队时操作按钮状态滞后。 */
function patchJob(updated: jobsApi.JobDto): void {
  const index = jobs.value.findIndex((job) => job.id === updated.id)
  if (index !== -1) {
    jobs.value[index] = updated
  }
}

async function retry(id: string): Promise<void> {
  try {
    const updated = await jobsApi.retryJob(id)
    patchJob(updated)
    ElMessage.success('已重试')
    await reload()
  } catch (e) {
    ElMessage.error(errorMessage(e, '重试失败'))
  }
}

async function cancel(id: string): Promise<void> {
  try {
    const updated = await jobsApi.cancelJob(id)
    patchJob(updated)
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
  disposed = true
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
          <el-tag :type="stateTagType(row.state)" size="small">
            {{ stateLabel(row.state) }}
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
            :aria-label="`重试任务 ${row.type} ${row.id.slice(0, 8)}`"
            @click="retry(row.id)"
          >
            重试
          </el-button>
          <el-button
            v-if="row.state === 'QUEUED' || row.state === 'RUNNING'"
            size="small"
            text
            type="danger"
            :aria-label="`取消任务 ${row.type} ${row.id.slice(0, 8)}`"
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
