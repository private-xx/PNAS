<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import * as mediaApi from '@/api/media'
import { errorMessage } from '@/api/http'
import MediaViewer from '@/components/MediaViewer.vue'

/** 年/月跳转(见 api/media.ts#listBuckets)按决策延后到 M5;M3 只做连续时间线 + 日期相册入口。 */

type KindFilter = '' | mediaApi.MediaKind

const items = ref<mediaApi.TimelineItem[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const finished = ref(false)
const failed = ref(false)
const error = ref('')
const kindFilter = ref<KindFilter>('')
const viewerIndex = ref(-1)
/** 过滤条件切换后,丢弃旧请求的结果(避免图片/视频结果串页)。 */
let generation = 0
/** 组件卸载后,忽略在途分页响应(不再写状态)。 */
let alive = true
const PAGE_SIZE = 60

const activeKind = computed<mediaApi.MediaKind | null>(() =>
  kindFilter.value === '' ? null : kindFilter.value,
)

/** 空态文案按当前筛选条件分支,避免"视频"页提示上传照片。 */
const emptyText = computed(() => {
  if (kindFilter.value === 'IMAGE') return '还没有照片,先上传一些照片吧'
  if (kindFilter.value === 'VIDEO') return '还没有视频,先上传一些视频吧'
  return '还没有媒体,先上传一些照片或视频吧'
})

/** 按**本地时区**取日期(R16:后端 Instant 以 UTC 序列化,直接切片会得到 UTC 日期)。 */
function localDay(iso: string): string {
  const d = new Date(iso)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const groups = computed(() => {
  const map = new Map<string, mediaApi.TimelineItem[]>()
  for (const item of items.value) {
    const day = localDay(item.takenAt)
    const list = map.get(day) ?? []
    list.push(item)
    map.set(day, list)
  }
  return Array.from(map, ([day, list]) => ({ day, list }))
})

async function loadMore() {
  if (loading.value || finished.value || failed.value) return
  loading.value = true
  error.value = ''
  const gen = generation
  try {
    const page = await mediaApi.listTimeline({ cursor: cursor.value, limit: PAGE_SIZE, kind: activeKind.value })
    if (!alive || gen !== generation) return
    if (page.length > 0) {
      items.value.push(...page)
      const last = page[page.length - 1]
      cursor.value = `${last.takenAt}|${last.assetId}`
    }
    // 不足一页即最后一页:直接收口,避免再发一次必然为空的请求。
    if (page.length < PAGE_SIZE) finished.value = true
  } catch (e) {
    if (alive && gen === generation) {
      failed.value = true
      error.value = errorMessage(e, '加载时间线失败')
    }
  } finally {
    if (alive && gen === generation) loading.value = false
  }
}

/** 失败后手动重试:解除失败锁并继续从当前游标拉取。 */
function retry() {
  failed.value = false
  error.value = ''
  void loadMore()
}

function switchKind(value: string | number | boolean | undefined) {
  const next: KindFilter = value === 'IMAGE' || value === 'VIDEO' ? value : ''
  if (next === kindFilter.value) return
  kindFilter.value = next
  generation += 1
  items.value = []
  cursor.value = null
  finished.value = false
  failed.value = false
  loading.value = false
  error.value = ''
  viewerIndex.value = -1
  void loadMore()
}

function placeholderText(status: mediaApi.ThumbStatus): string {
  if (status === 'SKIPPED') return '暂不支持缩略图'
  if (status === 'FAILED') return '缩略图失败'
  return '生成中…'
}

function onScroll() {
  if (failed.value) return
  const el = document.documentElement
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 400) {
    void loadMore()
  }
}

onMounted(() => {
  void loadMore()
  window.addEventListener('scroll', onScroll)
})
onUnmounted(() => {
  alive = false
  window.removeEventListener('scroll', onScroll)
})
</script>

<template>
  <div class="media">
    <div class="toolbar">
      <el-radio-group :model-value="kindFilter" @change="switchKind">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="IMAGE">照片</el-radio-button>
        <el-radio-button value="VIDEO">视频</el-radio-button>
      </el-radio-group>
      <router-link to="/media/albums"><el-button text>日期相册</el-button></router-link>
    </div>

    <div v-if="error" class="error-row">
      <el-alert :title="error" type="error" :closable="false" />
      <el-button size="small" @click="retry">重试</el-button>
    </div>

    <div v-for="group in groups" :key="group.day" class="day-group">
      <h3 class="day-title">{{ group.day }}</h3>
      <div class="grid">
        <div
          v-for="item in group.list"
          :key="item.assetId"
          class="cell"
          @click="viewerIndex = items.indexOf(item)"
        >
          <img
            v-if="item.thumbStatus === 'READY'"
            :src="mediaApi.thumbUrl(item.assetId, 256)"
            :alt="item.name"
            loading="lazy"
          />
          <div v-else class="placeholder">
            {{ placeholderText(item.thumbStatus) }}
            <span class="kind">{{ item.kind === 'VIDEO' ? '视频' : '图片' }}</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="finished && items.length === 0" class="hint">{{ emptyText }}</div>

    <MediaViewer v-model:index="viewerIndex" :items="items" />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
}
.day-group {
  padding: 0 1rem;
}
.error-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0 1rem;
}
.error-row .el-alert {
  flex: 1;
}
.day-title {
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 0;
  padding: 0.75rem 0 0.5rem;
  font-size: 0.95rem;
  color: #606266;
  background: var(--el-bg-color, #fff);
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 6px;
}
.cell {
  position: relative;
  aspect-ratio: 1;
  overflow: hidden;
  background: #f5f7fa;
  border-radius: 4px;
  cursor: pointer;
}
.cell img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 0.75rem;
  color: #909399;
  gap: 0.25rem;
}
.kind {
  color: #c0c4cc;
}
.hint {
  text-align: center;
  padding: 1.5rem;
  color: #909399;
}
</style>
