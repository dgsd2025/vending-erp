import { ref } from 'vue'

/** 手机模式自动判定(视口 ≤768px,与各页 CSS media query 同阈值) */
const mq = typeof window !== 'undefined' ? window.matchMedia('(max-width: 768px)') : null

export const isMobile = ref(mq ? mq.matches : false)

mq?.addEventListener('change', (e) => {
  isMobile.value = e.matches
})
