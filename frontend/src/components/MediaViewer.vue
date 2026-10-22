<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import * as mediaApi from '@/api/media'

const props = defineProps<{ items: mediaApi.TimelineItem[] }>()
const index = defineModel<number>('index', { default: -1 })

const rotation = ref(0)
const highResReady = ref(false)
const detail = ref<mediaApi.AssetDetail | null>(null)
/** 防止快速翻页时旧请求的详情覆盖当前项。 */
let detailToken = 0

const current = computed(() => (index.value >= 0 ? props.items[index.value] ?? null : null))
const visible = computed({
  get: () => index.value >= 0,
  set: (v: boolean) => {
    if (!v) index.value = -1
  },
})

watch(current, (item) => {
  rotation.value = 0
  highResReady.value = false
  detail.value = null
  const token = ++detailToken
  if (!item) return
  mediaApi
    .getAsset(item.assetId)
    .then((d) => {
      if (token === detailToken) detail.value = d
    })
    .catch(() => {
      // 详情只用于展示元信息,失败时退回时间线条目里的字段
    })
})

/** 体积展示(与文件视图保持一致的口径)。 */
function sizeText(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 毫秒 → m:ss / h:mm:ss。 */
function durationText(ms: number): string {
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

/** 元信息一律按**本地时区**格式化(R16:禁止直接展示 UTC 字符串)。 */
const metaText = computed(() => {
  const item = current.value
  if (!item) return ''
  const parts: string[] = []
  const iso = detail.value?.takenAt ?? item.takenAt
  if (iso) {
    const d = new Date(iso)
    if (!Number.isNaN(d.getTime())) parts.push(d.toLocaleString('zh-CN'))
  }
  const width = detail.value?.width ?? item.width
  const height = detail.value?.height ?? item.height
  if (width && height) parts.push(`${width}×${height}`)
  const duration = detail.value?.durationMs ?? item.durationMs
  if (item.kind === 'VIDEO' && duration) parts.push(durationText(duration))
  const bytes = detail.value?.sizeBytes ?? item.sizeBytes
  if (typeof bytes === 'number') parts.push(sizeText(bytes))
  return parts.join(' · ')
})

function step(delta: number) {
  const next = index.value + delta
  if (next >= 0 && next < props.items.length) index.value = next
}

function onKey(e: KeyboardEvent) {
  if (!visible.value) return
  if (e.key === 'Escape') {
    index.value = -1
    return
  }
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
  // 焦点在视频控件/输入框时把方向键让给原生控件
  const target = e.target instanceof HTMLElement ? e.target : null
  if (target && target.closest('video, input, textarea')) return
  e.preventDefault()
  step(e.key === 'ArrowRight' ? 1 : -1)
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <el-dialog v-model="visible" width="90vw" top="4vh" :title="current?.name" destroy-on-close>
    <div v-if="current" class="viewer">
      <video
        v-if="current.kind === 'VIDEO'"
        :src="mediaApi.mediaContentUrl(current.assetId)"
        controls
        preload="metadata"
        playsinline
        class="stage"
      />
      <div v-else class="stage">
        <img
          :src="mediaApi.thumbUrl(current.assetId, 512)"
          :style="{ transform: `rotate(${rotation}deg)` }"
          class="thumb-layer"
          alt=""
        />
        <img
          v-if="highResReady"
          :src="mediaApi.mediaContentUrl(current.assetId)"
          :style="{ transform: `rotate(${rotation}deg)` }"
          class="full-layer"
          alt=""
        />
        <img
          v-else
          :src="mediaApi.mediaContentUrl(current.assetId)"
          class="hidden-preload"
          alt=""
          @load="highResReady = true"
        />
      </div>

      <div v-if="metaText" class="meta">{{ metaText }}</div>
      <div class="actions">
        <el-button :disabled="index <= 0" @click="step(-1)">上一张</el-button>
        <el-button v-if="current.kind !== 'VIDEO'" @click="rotation = (rotation + 90) % 360">旋转</el-button>
        <a :href="mediaApi.mediaContentUrl(current.assetId)" download>
          <el-button>下载原图</el-button>
        </a>
        <el-button :disabled="index >= items.length - 1" @click="step(1)">下一张</el-button>
      </div>
      <div v-if="current.kind === 'VIDEO' && current.durationMs === null" class="note">
        该容器暂无法解析时长;若浏览器不支持直放,请等待 M5 转码
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.viewer {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
}
.stage {
  position: relative;
  max-width: 100%;
  max-height: 68vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stage img,
.stage video {
  max-width: 100%;
  max-height: 68vh;
  transition: transform 0.2s;
}
.full-layer {
  position: absolute;
  inset: 0;
  margin: auto;
}
.hidden-preload {
  display: none;
}
.meta {
  color: #909399;
  font-size: 0.8rem;
}
.actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  justify-content: center;
}
.note {
  color: #909399;
  font-size: 0.8rem;
}
</style>
