<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as nodesApi from '@/api/nodes'
import { errorMessage } from '@/api/http'
import { useUploader } from '@/components/ChunkUploader'

const loading = ref(false)
const nodes = ref<nodesApi.NodeDto[]>([])
const crumbs = ref<nodesApi.NodeDto[]>([]) // 面包屑(不含根)
const showTrash = ref(false)
const newDirName = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const dragActive = ref(false)
const versions = ref<nodesApi.VersionDto[]>([])
const versionsVisible = ref(false)
const versionsTitle = ref('')

async function openVersions(node: nodesApi.NodeDto) {
  versionsTitle.value = node.name
  versions.value = []
  versionsVisible.value = true
  try {
    versions.value = await nodesApi.listVersions(node.id)
  } catch (error) {
    ElMessage.error(errorMessage(error, '读取版本历史失败'))
  }
}

const currentParentId = computed(() => (crumbs.value.length ? crumbs.value[crumbs.value.length - 1].id : null))

const { tasks, enqueue, retryTask, cancelTask, clearFinished } = useUploader(
  () => currentParentId.value,
  () => reload(),
)

async function reload() {
  loading.value = true
  try {
    nodes.value = await nodesApi.listNodes(showTrash.value ? null : currentParentId.value, showTrash.value)
  } catch (error) {
    ElMessage.error(errorMessage(error, '加载目录失败'))
  } finally {
    loading.value = false
  }
}

function openDir(node: nodesApi.NodeDto) {
  crumbs.value = [...crumbs.value, node]
  reload()
}

function goCrumb(index: number) {
  crumbs.value = index < 0 ? [] : crumbs.value.slice(0, index + 1)
  reload()
}

function toggleTrash() {
  showTrash.value = !showTrash.value
  crumbs.value = []
  reload()
}

async function createDir() {
  if (!newDirName.value.trim()) {
    ElMessage.warning('请输入目录名')
    return
  }
  try {
    await nodesApi.createDir(currentParentId.value, newDirName.value.trim())
    newDirName.value = ''
    await reload()
  } catch (error) {
    ElMessage.error(errorMessage(error, '新建目录失败'))
  }
}

async function rename(node: nodesApi.NodeDto) {
  try {
    const { value } = await ElMessageBox.prompt('新的名称', '重命名', {
      inputValue: node.name,
      inputValidator: (v) => (v && v.trim() ? true : '名称不能为空'),
    })
    await nodesApi.renameNode(node.id, value.trim())
    await reload()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(errorMessage(error, '重命名失败'))
    }
  }
}

async function removeNode(node: nodesApi.NodeDto) {
  try {
    await ElMessageBox.confirm(`将「${node.name}」移入回收站?`, '删除确认', { type: 'warning' })
    await nodesApi.trashNode(node.id)
    await reload()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(errorMessage(error, '删除失败'))
    }
  }
}

async function restore(node: nodesApi.NodeDto) {
  try {
    await nodesApi.restoreNode(node.id)
    ElMessage.success('已恢复')
    await reload()
  } catch (error) {
    ElMessage.error(errorMessage(error, '恢复失败'))
  }
}

function pickFiles() {
  fileInput.value?.click()
}

async function onFilesPicked(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files?.length) {
    await enqueue(input.files)
    input.value = ''
  }
}

async function onDrop(event: DragEvent) {
  dragActive.value = false
  const files = event.dataTransfer?.files
  if (files?.length) {
    await enqueue(files)
  }
}

function sizeText(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

onMounted(reload)
</script>

<template>
  <div class="files">
    <div class="toolbar">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item>
          <a @click.prevent="goCrumb(-1)">我的文件</a>
        </el-breadcrumb-item>
        <el-breadcrumb-item v-for="(c, i) in crumbs" :key="c.id">
          <a @click.prevent="goCrumb(i)">{{ c.name }}</a>
        </el-breadcrumb-item>
      </el-breadcrumb>
      <div class="actions">
        <template v-if="!showTrash">
          <el-input v-model="newDirName" placeholder="新目录名" size="small" class="new-dir" />
          <el-button size="small" @click="createDir">新建目录</el-button>
          <el-button size="small" type="primary" @click="pickFiles">上传文件</el-button>
        </template>
        <el-button size="small" @click="toggleTrash">{{ showTrash ? '返回文件' : '回收站' }}</el-button>
        <el-button size="small" text @click="reload">刷新</el-button>
      </div>
    </div>

    <div
      v-if="!showTrash"
      class="dropzone"
      :class="{ active: dragActive }"
      @dragover.prevent="dragActive = true"
      @dragleave.prevent="dragActive = false"
      @drop.prevent="onDrop"
    >
      拖拽文件到此处上传(支持断点续传,分块 4 MiB)
      <input ref="fileInput" type="file" multiple hidden @change="onFilesPicked" />
    </div>

    <el-table v-loading="loading" :data="nodes" size="small" empty-text="这里什么都没有">
      <el-table-column label="名称" min-width="240">
        <template #default="{ row }">
          <a v-if="row.kind === 'DIR' && !showTrash" @click.prevent="openDir(row)">📁 {{ row.name }}</a>
          <span v-else>{{ row.kind === 'DIR' ? '📁' : '📄' }} {{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="110">
        <template #default="{ row }">{{ row.kind === 'FILE' ? sizeText(row.sizeBytes) : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <template v-if="showTrash">
            <el-button size="small" type="primary" text @click="restore(row)">恢复</el-button>
          </template>
          <template v-else>
            <el-button v-if="row.kind === 'FILE'" size="small" text tag="a" :href="nodesApi.contentUrl(row.id)">
              下载
            </el-button>
            <el-button v-if="row.kind === 'FILE'" size="small" text @click="openVersions(row)">版本</el-button>
            <el-button size="small" text @click="rename(row)">重命名</el-button>
            <el-button size="small" text type="danger" @click="removeNode(row)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="tasks.length" class="uploads">
      <div class="uploads-title">
        上传队列
        <el-button size="small" text @click="clearFinished">清理已完成</el-button>
      </div>
      <div v-for="task in tasks" :key="task.id" class="upload-item">
        <span class="name">{{ task.file.name }}</span>
        <el-progress :percentage="task.progress" :status="task.status === 'error' ? 'exception' : undefined" />
        <span class="status">
          {{ task.status === 'done' ? '完成' : task.status === 'error' ? task.message : task.status }}
        </span>
        <el-button v-if="task.status === 'error'" size="small" text type="primary" @click="retryTask(task.id)">
          重试
        </el-button>
        <el-button
          v-if="task.status === 'error' || task.status === 'uploading' || task.status === 'pending'"
          size="small"
          text
          type="danger"
          @click="cancelTask(task.id)"
        >
          取消
        </el-button>
      </div>
    </div>
    <el-dialog v-model="versionsVisible" :title="`版本历史 - ${versionsTitle}`" width="min(560px, 92vw)">
      <el-table :data="versions" size="small" empty-text="暂无版本">
        <el-table-column prop="versionNo" label="版本" width="80" />
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ sizeText(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" />
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.files {
  padding: 1rem;
  max-width: 100%;
  overflow-x: auto;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
  margin-bottom: 0.75rem;
}
.actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.new-dir {
  width: 140px;
}
.dropzone {
  border: 1px dashed #c0c4cc;
  border-radius: 6px;
  padding: 1rem;
  text-align: center;
  color: #909399;
  margin-bottom: 0.75rem;
}
.dropzone.active {
  border-color: #409eff;
  color: #409eff;
}
.uploads {
  margin-top: 1rem;
}
.uploads-title {
  font-weight: 600;
  margin-bottom: 0.4rem;
}
.upload-item {
  display: grid;
  grid-template-columns: 1fr 2fr 120px;
  gap: 0.75rem;
  align-items: center;
  padding: 0.25rem 0;
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status {
  color: #909399;
  font-size: 0.85rem;
}
</style>
