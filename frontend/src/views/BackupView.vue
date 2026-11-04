<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as backupApi from '@/api/backup'
import { errorMessage } from '@/api/http'

const AUTO_REFRESH_MS = 5000

const devices = ref<backupApi.DeviceDto[]>([])
const runs = ref<backupApi.RunDto[]>([])

const devicesLoading = ref(false)
const runsLoading = ref(false)
const devicesError = ref('')
const runsError = ref('')

const showCreate = ref(false)
const creating = ref(false)
const createForm = ref<{ name: string; platform: backupApi.DevicePlatform }>({
  name: '',
  platform: 'MAC',
})

let timer: number | undefined
let disposed = false

const platformOptions: { label: string; value: backupApi.DevicePlatform }[] = [
  { label: 'macOS', value: 'MAC' },
  { label: 'Windows', value: 'WINDOWS' },
  { label: '其他', value: 'OTHER' },
]

const platformLabels: Record<backupApi.DevicePlatform, string> = {
  MAC: 'macOS',
  WINDOWS: 'Windows',
  OTHER: '其他',
}

const modeLabels: Record<backupApi.RunMode, string> = {
  MANUAL: '手动',
  SCHEDULED: '定时',
  REALTIME: '实时',
}

const statusLabels: Record<backupApi.RunStatus, string> = {
  RUNNING: '进行中',
  SUCCESS: '成功',
  PARTIAL: '部分成功',
  FAILED: '失败',
}

const statusTagTypes: Record<backupApi.RunStatus, 'info' | 'success' | 'warning' | 'danger'> = {
  RUNNING: 'info',
  SUCCESS: 'success',
  PARTIAL: 'warning',
  FAILED: 'danger',
}

const deviceNames = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const device of devices.value) {
    map[device.id] = device.name
  }
  return map
})

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString()
}

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / 1024 ** index
  const text = index === 0 ? String(value) : value >= 100 ? value.toFixed(0) : value >= 10 ? value.toFixed(1) : value.toFixed(2)
  return `${text} ${units[index]}`
}

function platformLabel(value: unknown): string {
  return platformLabels[value as backupApi.DevicePlatform] ?? String(value ?? '—')
}

function modeLabel(value: unknown): string {
  return modeLabels[value as backupApi.RunMode] ?? String(value ?? '—')
}

function statusLabel(value: unknown): string {
  return statusLabels[value as backupApi.RunStatus] ?? String(value ?? '—')
}

function statusTagType(value: unknown): 'info' | 'success' | 'warning' | 'danger' {
  return statusTagTypes[value as backupApi.RunStatus] ?? 'info'
}

function deviceName(deviceId: string): string {
  return deviceNames.value[deviceId] ?? deviceId.slice(0, 8)
}

async function loadDevices(silent = false): Promise<void> {
  if (disposed) return
  if (!silent) devicesLoading.value = true
  try {
    devices.value = await backupApi.listDevices()
    devicesError.value = ''
  } catch (e) {
    devicesError.value = errorMessage(e, '加载设备失败')
    if (!silent) ElMessage.error(devicesError.value)
  } finally {
    if (!silent) devicesLoading.value = false
  }
}

async function loadRuns(silent = false): Promise<void> {
  if (disposed) return
  if (!silent) runsLoading.value = true
  try {
    runs.value = await backupApi.listRuns(50)
    runsError.value = ''
  } catch (e) {
    runsError.value = errorMessage(e, '加载运行记录失败')
    if (!silent) ElMessage.error(runsError.value)
  } finally {
    if (!silent) runsLoading.value = false
  }
}

async function reload(): Promise<void> {
  await Promise.all([loadDevices(), loadRuns()])
}

async function refreshSilently(): Promise<void> {
  await Promise.all([loadDevices(true), loadRuns(true)])
}

function openCreate(): void {
  createForm.value = { name: '', platform: 'MAC' }
  showCreate.value = true
}

async function copyToken(token: string): Promise<void> {
  let ok = false
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(token)
      ok = true
    }
  } catch {
    ok = false
  }
  if (!ok) {
    // 非安全上下文时回退到隐藏 textarea + execCommand。
    try {
      const area = document.createElement('textarea')
      area.value = token
      area.setAttribute('readonly', '')
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      ok = document.execCommand('copy')
      document.body.removeChild(area)
    } catch {
      ok = false
    }
  }
  if (ok) {
    ElMessage.success('令牌已复制到剪贴板')
  } else {
    ElMessage.error('复制失败,请手动选中令牌复制')
  }
}

async function submitCreate(): Promise<void> {
  const name = createForm.value.name.trim()
  if (!name) {
    ElMessage.error('请输入设备名称')
    return
  }
  creating.value = true
  try {
    const created = await backupApi.createDevice(name, createForm.value.platform)
    showCreate.value = false
    await loadDevices(true)
    await showToken(created.token)
  } catch (e) {
    ElMessage.error(errorMessage(e, '新建设备失败'))
  } finally {
    creating.value = false
  }
}

/** 一次性令牌只在此处展示:关闭后无法再次获取。 */
async function showToken(token: string): Promise<void> {
  const message = h('div', { style: 'line-height: 1.6;' }, [
    h(
      'p',
      { style: 'margin: 0 0 0.5rem; color: #303133;' },
      '请立即复制并妥善保存以下令牌。关闭此对话框后将无法再次查看,只能重新新建设备。',
    ),
    h(
      'pre',
      {
        style:
          'margin: 0 0 0.6rem; padding: 0.6rem 0.7rem; background: #f5f7fa; border: 1px solid #e4e7ed; ' +
          'border-radius: 4px; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; ' +
          'font-size: 0.85rem; white-space: pre-wrap; word-break: break-all; user-select: all; color: #303133;',
      },
      token,
    ),
    h('div', { style: 'display: flex; align-items: center; gap: 0.5rem;' }, [
      h(
        'button',
        {
          type: 'button',
          style:
            'padding: 0.3rem 0.7rem; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; ' +
            'color: #606266; cursor: pointer; font-size: 0.85rem;',
          onClick: () => void copyToken(token),
        },
        '复制令牌',
      ),
      h('span', { style: 'color: #e6a23c; font-size: 0.8rem;' }, '令牌仅显示这一次'),
    ]),
  ])
  try {
    await ElMessageBox.alert(message, '设备创建成功', {
      confirmButtonText: '我已保存令牌',
      type: 'success',
      customClass: 'backup-token-dialog',
    })
  } catch {
    // 用户直接关闭对话框:设备已创建成功,不应误报创建失败。
  }
}

async function revoke(device: backupApi.DeviceDto): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定吊销设备「${device.name}」吗?吊销后该设备将无法再执行备份。`,
      '吊销设备',
      { type: 'warning', confirmButtonText: '吊销', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await backupApi.revokeDevice(device.id)
    ElMessage.success('设备已吊销')
    await loadDevices(true)
  } catch (e) {
    ElMessage.error(errorMessage(e, '吊销设备失败'))
  }
}

onMounted(() => {
  void reload()
  timer = window.setInterval(() => void refreshSilently(), AUTO_REFRESH_MS)
})

onUnmounted(() => {
  disposed = true
  window.clearInterval(timer)
})
</script>

<template>
  <div class="backup">
    <section class="section">
      <div class="toolbar">
        <h2 class="section-title">备份设备</h2>
        <el-button type="primary" @click="openCreate">新建设备</el-button>
        <el-button :loading="devicesLoading" @click="loadDevices()">刷新</el-button>
      </div>

      <el-alert
        v-if="devicesError"
        class="error"
        type="error"
        :title="devicesError"
        show-icon
        :closable="false"
      />

      <el-table
        v-loading="devicesLoading"
        :data="devices"
        row-key="id"
        size="small"
        empty-text="暂无备份设备"
      >
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="平台" width="110">
          <template #default="{ row }">{{ platformLabel(row.platform) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="190">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="最近活跃" width="190">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.revoked ? 'danger' : 'success'" size="small">
              {{ row.revoked ? '已吊销' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="!row.revoked"
              size="small"
              text
              type="danger"
              :aria-label="`吊销设备 ${row.name}`"
              @click="revoke(row)"
            >
              吊销
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="section">
      <div class="toolbar">
        <h2 class="section-title">运行记录</h2>
        <el-button :loading="runsLoading" @click="loadRuns()">刷新</el-button>
        <span class="auto-hint">每 5 秒自动刷新</span>
      </div>

      <el-alert
        v-if="runsError"
        class="error"
        type="error"
        :title="runsError"
        show-icon
        :closable="false"
      />

      <el-table
        v-loading="runsLoading"
        :data="runs"
        row-key="id"
        size="small"
        empty-text="暂无运行记录"
      >
        <el-table-column label="开始时间" width="190">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="设备" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ deviceName(row.deviceId) }}</template>
        </el-table-column>
        <el-table-column label="模式" width="90">
          <template #default="{ row }">{{ modeLabel(row.mode) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已见" width="80" align="right">
          <template #default="{ row }">{{ row.filesSeen }}</template>
        </el-table-column>
        <el-table-column label="已传" width="80" align="right">
          <template #default="{ row }">{{ row.filesUploaded }}</template>
        </el-table-column>
        <el-table-column label="跳过" width="80" align="right">
          <template #default="{ row }">{{ row.filesSkipped }}</template>
        </el-table-column>
        <el-table-column label="失败" width="80" align="right">
          <template #default="{ row }">{{ row.filesFailed }}</template>
        </el-table-column>
        <el-table-column label="上传流量" width="120" align="right">
          <template #default="{ row }">{{ formatBytes(row.bytesUploaded) }}</template>
        </el-table-column>
        <el-table-column label="错误" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.error || '—' }}</template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="showCreate" title="新建设备" width="420px" :close-on-click-modal="false">
      <el-form label-width="72px" @submit.prevent="submitCreate">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="例如 MacBook Pro" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="平台">
          <el-select v-model="createForm.platform" style="width: 100%">
            <el-option
              v-for="option in platformOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.backup {
  padding: 1rem;
  max-width: 100%;
  overflow-x: auto;
}
.section + .section {
  margin-top: 1.5rem;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}
.section-title {
  margin: 0 0.5rem 0 0;
  font-size: 1rem;
  font-weight: 600;
  color: #303133;
}
.auto-hint {
  color: #909399;
  font-size: 0.8rem;
}
.error {
  margin-bottom: 0.75rem;
}
.muted {
  color: #909399;
}
</style>
