<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'

/**
 * 数据新鲜度水印(全局铁律1「数据截至一等公民」)。
 * 两种形态:
 *  - variant="bar"(默认):页面顶部通栏,全站每个数据页统一挂载。
 *    绿=新鲜 / 灰=尚未导入 / 红=超 3 天过期(+「建议先导数据 →」可点跳导入中心)。
 *  - variant="badge":侧栏页脚小徽标(紧凑)。
 * 数据截至真值来自 app store(驾驶舱写入;直达其它页时惰性拉取一次)。
 */
const props = withDefaults(defineProps<{ variant?: 'bar' | 'badge' }>(), {
  variant: 'bar',
})

const router = useRouter()
const app = useAppStore()

onMounted(() => {
  // 直达非驾驶舱页:若还没有数据截至值,惰性补一次(不阻塞渲染)
  if (props.variant === 'bar') void app.loadDataAsOf()
})

const days = computed(() => app.daysStale)
const stale = computed(() => app.isStale)
const hasData = computed(() => !!app.dataAsOf)

const staleText = computed(() => {
  if (!hasData.value) return '尚未导入数据'
  if (days.value === 0) return '今日已更新'
  if (days.value === 1) return '昨日数据'
  return `已 ${days.value} 天未更新`
})

function goImport() {
  router.push('/import')
}
</script>

<template>
  <!-- 侧栏页脚徽标 -->
  <el-tag
    v-if="variant === 'badge'"
    :type="!hasData ? 'info' : stale ? 'danger' : 'success'"
    effect="plain"
    size="large"
  >
    {{ hasData ? `数据截至 ${app.dataAsOf}` : '数据截至 ——(尚未导入)' }}
  </el-tag>

  <!-- 页面顶部通栏(全站统一) -->
  <div
    v-else
    class="freshbar"
    :class="{ fresh: hasData && !stale, none: !hasData, stale }"
    data-block="data-freshness-bar"
  >
    <span class="fb-ico">🕐</span>
    <span class="fb-main">
      数据截至
      <b>{{ hasData ? app.dataAsOf : '——' }}</b>
      <span class="fb-sub">· {{ staleText }}</span>
    </span>
    <template v-if="stale || !hasData">
      <span class="fb-warn">{{ !hasData ? '还没有可用数据,先去导入' : '数据已过期(超 3 天),分析口径可能失真' }}</span>
      <button class="fb-go" @click="goImport">建议先导数据 →</button>
    </template>
  </div>
</template>

<style scoped>
.freshbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  border-radius: 10px;
  font-size: 12.5px;
  border: 1px solid;
  margin-bottom: 12px;
}
.freshbar .fb-main b {
  font-family: var(--num);
  font-weight: 700;
  margin: 0 2px;
}
.freshbar .fb-sub {
  color: inherit;
  opacity: 0.75;
  margin-left: 2px;
}
.freshbar .fb-warn {
  margin-left: auto;
  font-weight: 600;
}
.freshbar .fb-go {
  border: none;
  border-radius: 8px;
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}
/* 新鲜:低调绿 */
.freshbar.fresh {
  background: var(--green-soft);
  border-color: #cfe3d5;
  color: var(--green);
}
/* 尚未导入:中性灰 */
.freshbar.none {
  background: #f1ede3;
  border-color: #e0d8c6;
  color: #7c745f;
}
.freshbar.none .fb-go {
  background: #7c745f;
  color: #fff;
}
/* 过期:灰化 + 红提示(铁律1) */
.freshbar.stale {
  background: #eceae3;
  border-color: #d9c7c1;
  color: #8a8577;
  filter: grayscale(0.15);
}
.freshbar.stale .fb-warn {
  color: var(--red);
}
.freshbar.stale .fb-go {
  background: var(--red);
  color: #fff;
}
@media (max-width: 768px) {
  .freshbar {
    flex-wrap: wrap;
  }
  .freshbar .fb-warn {
    margin-left: 0;
    width: 100%;
    order: 3;
  }
}
</style>
