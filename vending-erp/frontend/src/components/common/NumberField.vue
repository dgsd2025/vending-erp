<script setup lang="ts">
import { computed } from 'vue'

/**
 * 可靠数字输入框 —— el-input-number 在本项目渲染异常(输入区塌陷只剩 −/+),
 * 用 el-input type=number 做替身:对外 v-model 仍是 number|null(与 el-input-number 语义一致,
 * 父组件无需改),内部处理字符串↔数字转换、min/max 夹取、precision 保留小数。
 * 直接把 <el-input-number> 换成 <NumberField> 即可,props 名保持兼容。
 */
const props = withDefaults(
  defineProps<{
    modelValue: number | null | undefined
    min?: number
    max?: number
    /** 保留小数位(失焦时按此四舍五入) */
    precision?: number
    step?: number
    placeholder?: string
    disabled?: boolean
    size?: 'large' | 'default' | 'small'
  }>(),
  { min: undefined, max: undefined, precision: undefined, step: undefined, disabled: false },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: number | null): void
  (e: 'change', v: number | null): void
}>()

// 内部显示值:数字→字符串;null/undefined→空串
const display = computed<string>(() =>
  props.modelValue === null || props.modelValue === undefined ? '' : String(props.modelValue),
)

/** 输入时:空→null;能解析→number;不能解析→保持 null(不写脏值) */
function onInput(val: string) {
  if (val === '' || val === null || val === undefined) {
    emit('update:modelValue', null)
    return
  }
  const n = Number(val)
  if (Number.isNaN(n)) return
  emit('update:modelValue', n)
}

/** 失焦时:夹取 min/max + 保留 precision 小数 */
function onBlur() {
  let v = props.modelValue
  if (v === null || v === undefined) return
  if (props.min !== undefined && v < props.min) v = props.min
  if (props.max !== undefined && v > props.max) v = props.max
  if (props.precision !== undefined) {
    v = Number(v.toFixed(props.precision))
  }
  if (v !== props.modelValue) {
    emit('update:modelValue', v)
  }
  emit('change', v)
}
</script>

<template>
  <el-input
    :model-value="display"
    type="number"
    :min="min"
    :max="max"
    :step="step"
    :placeholder="placeholder"
    :disabled="disabled"
    :size="size"
    @update:model-value="onInput"
    @blur="onBlur"
  />
</template>
