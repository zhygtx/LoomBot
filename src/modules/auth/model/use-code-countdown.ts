import { onUnmounted, ref } from 'vue'

export function useCodeCountdown() {
  const seconds = ref(0)
  let timer: ReturnType<typeof setInterval> | null = null

  function start(duration = 60): void {
    stop()
    seconds.value = duration
    timer = setInterval(() => {
      seconds.value -= 1
      if (seconds.value <= 0) stop()
    }, 1000)
  }

  function stop(): void {
    if (timer) clearInterval(timer)
    timer = null
    if (seconds.value < 0) seconds.value = 0
  }

  onUnmounted(stop)
  return { seconds, start }
}
