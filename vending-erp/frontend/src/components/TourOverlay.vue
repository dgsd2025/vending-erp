<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTour } from '@/composables/useTour'

/**
 * 逐页浮层引导的遮罩层(全局挂在 App.vue)。
 * 用「聚光灯」手法:高亮框套一圈超大 box-shadow 把四周压暗,气泡贴在目标右侧;
 * 无目标的欢迎/收尾步骤居中显示。目标锚在侧栏导航项(始终存在),稳。
 */
const { state, next, prev, finish, isLast } = useTour()

const rect = ref<{ top: number; left: number; width: number; height: number } | null>(null)

const curStep = computed(() => state.steps[state.stepIndex])

function measure() {
  const sel = curStep.value?.target
  if (!sel) {
    rect.value = null
    return
  }
  const el = document.querySelector(sel) as HTMLElement | null
  if (!el) {
    rect.value = null
    return
  }
  el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  const r = el.getBoundingClientRect()
  rect.value = { top: r.top, left: r.left, width: r.width, height: r.height }
}

watch(
  () => [state.active, state.stepIndex],
  async () => {
    if (!state.active) return
    await nextTick()
    setTimeout(measure, 60)
  },
  { immediate: true },
)

function onResize() {
  if (state.active) measure()
}
onMounted(() => {
  window.addEventListener('resize', onResize)
  window.addEventListener('scroll', onResize, true)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('scroll', onResize, true)
})

/** 气泡位置:有目标贴右侧,没目标居中 */
const bubbleStyle = computed(() => {
  const r = rect.value
  if (!r) {
    return { left: '50%', top: '50%', transform: 'translate(-50%,-50%)' } as Record<string, string>
  }
  const left = Math.min(r.left + r.width + 16, window.innerWidth - 360)
  const top = Math.max(12, Math.min(r.top, window.innerHeight - 220))
  return { left: `${left}px`, top: `${top}px` } as Record<string, string>
})

const spotStyle = computed(() => {
  const r = rect.value
  if (!r) return { display: 'none' } as Record<string, string>
  const pad = 6
  return {
    top: `${r.top - pad}px`,
    left: `${r.left - pad}px`,
    width: `${r.width + pad * 2}px`,
    height: `${r.height + pad * 2}px`,
  } as Record<string, string>
})
</script>

<template>
  <div v-if="state.active" class="tour-root">
    <!-- 无目标:整屏压暗;有目标:聚光灯框自带压暗 -->
    <div v-if="!rect" class="tour-dim" @click.self="finish" />
    <div v-else class="tour-spot" :style="spotStyle" />

    <div v-if="curStep" class="tour-bubble" :style="bubbleStyle">
      <div class="tb-title">{{ curStep.title }}</div>
      <div class="tb-desc">{{ curStep.desc }}</div>
      <div class="tb-foot">
        <span class="tb-dots">
          <i v-for="(s, i) in state.steps" :key="i" :class="{ on: i === state.stepIndex }" />
        </span>
        <button class="tb-skip" @click="finish">跳过</button>
        <button v-if="state.stepIndex > 0" class="tb-prev" @click="prev">上一步</button>
        <button class="tb-next" @click="next">{{ isLast() ? '完成 ✓' : '下一步 →' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tour-root {
  position: fixed;
  inset: 0;
  z-index: 3000;
  pointer-events: none;
}
.tour-dim {
  position: absolute;
  inset: 0;
  background: rgba(20, 30, 24, 0.55);
  pointer-events: auto;
}
.tour-spot {
  position: fixed;
  border-radius: 10px;
  box-shadow: 0 0 0 9999px rgba(20, 30, 24, 0.55);
  border: 2px solid #8fd3ae;
  transition: all 0.25s ease;
  pointer-events: none;
}
.tour-bubble {
  position: fixed;
  width: 340px;
  max-width: calc(100vw - 24px);
  background: var(--green-deep, #1c3d2f);
  color: #eaf3ee;
  border-radius: 12px;
  padding: 15px 16px 12px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.35);
  pointer-events: auto;
  transition: all 0.25s ease;
}
.tb-title {
  font-weight: 800;
  font-size: 15px;
  color: #fff;
  margin-bottom: 6px;
}
.tb-desc {
  font-size: 13px;
  line-height: 1.65;
  color: #cfe0d6;
}
.tb-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 13px;
}
.tb-dots {
  display: flex;
  gap: 5px;
  margin-right: auto;
}
.tb-dots i {
  width: 6px;
  height: 6px;
  border-radius: 99px;
  background: #567564;
}
.tb-dots i.on {
  background: #8fd3ae;
  width: 16px;
}
.tb-foot button {
  border: 0;
  border-radius: 7px;
  font-size: 12.5px;
  padding: 6px 12px;
  cursor: pointer;
}
.tb-skip {
  background: transparent;
  color: #9fbcac;
}
.tb-prev {
  background: rgba(255, 255, 255, 0.14);
  color: #eaf3ee;
}
.tb-next {
  background: #fff;
  color: var(--green-deep, #1c3d2f);
  font-weight: 700;
}
</style>
