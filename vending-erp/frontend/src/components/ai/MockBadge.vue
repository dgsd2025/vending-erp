<script setup lang="ts">
import { computed } from 'vue'

/**
 * 全站唯一「AI 输出为 mock」醒目徽标(七律#7 · M4-8 P2-6 统一整改)。
 * 目的:凡 AI 结论仍是 mock 占位(未接真模型)的地方,都挂一枚同款橙色实心徽标——
 *   不再只靠正文里的 [MOCK] 前缀,老板扫一眼就知道"这不是真 AI 算的"。
 * 判定优先级(任一命中即 mock;真 key 接入后所有信号翻转 → 徽标自动消失):
 *   ① 显式 mock 布尔(如月报 report.mock);
 *   ② 返回体 source 字段 === 'mock'(凭证识别 / 后端并行票统一加的 source);
 *   ③ model 字段以 mock 开头(网关 mock 断路时 model 记 "mock(路由目标)");
 *   ④ mode 字段含 mock(NL 查数「mock模板匹配」/ 别名「规则建议(mock断路)」);
 *   ⑤ 正文 text 含 [MOCK] 前缀(MockLlmService 兜底信号)。
 * 只要真 key 配好,source→真源、model→真模型、mode→真链路、text 去掉 [MOCK],徽标自动隐藏。
 */
const props = defineProps<{
  mock?: boolean | null
  source?: string | null
  model?: string | null
  mode?: string | null
  text?: string | null
}>()

const tip =
  'AI 输出为 mock 占位(尚未接真模型)。数字仍由规则引擎算,AI 段只是开发期占位话术;' +
  '设置中心配好 LLM key 后自动切真模型,本徽标随即消失。'

const isMock = computed(() => {
  // 注意:Vue 会把「未传入的 Boolean prop」默认成 false,故不能用 mock===false 反推"非 mock"
  // (否则 dashboard 等只传 model/text 的调用会被误判)。只让显式 true 强制显示,其余落到下方信号。
  if (props.mock === true) return true
  if (props.source != null && props.source !== '') return props.source.toLowerCase() === 'mock'
  if (props.model != null && props.model !== '') return /^mock/i.test(props.model)
  if (props.mode != null && props.mode !== '') return /mock/i.test(props.mode)
  if (props.text != null && props.text !== '') return props.text.includes('[MOCK]')
  return false
})
</script>

<template>
  <el-tag
    v-if="isMock"
    type="warning"
    size="small"
    effect="dark"
    class="mock-badge"
    round
    :title="tip"
  >
    🧪 MOCK · 未接真AI
  </el-tag>
</template>

<style scoped>
.mock-badge {
  font-weight: 700;
  letter-spacing: 0.2px;
  cursor: help;
  vertical-align: middle;
}
</style>
