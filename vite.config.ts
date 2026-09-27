import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        '@app': fileURLToPath(new URL('./src/app', import.meta.url)),
        '@modules': fileURLToPath(new URL('./src/modules', import.meta.url)),
        '@shared': fileURLToPath(new URL('./src/shared', import.meta.url)),
      },
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      allowedHosts: ['dev.loombot.top', '.loombot.top'],
      watch: {
        /*
         * 忽略写入过程中的临时文件。
         *
         * 编辑器/工具保存文件时会在 src/ 旁边留下中间产物，目前见过两种：
         *   .<文件名>.<pid>.<uuid>.tmpdir/<文件名>.tmp   —— 先写临时目录再改名
         *   <文件名>~RF<hex>.TMP                        —— ReplaceFileW 的备份文件
         * 它们出现在被监视的源码树里，chokidar 刚 watch 上去文件就被删除/改名，
         * Windows 上直接抛 EBUSY，Vite 把这个错误当致命异常抛出，整个 dev server 一起退出。
         * 这些路径本来就不该被监视，按后缀排除掉，换工具也不用再踩一次。
         */
        ignored: ['**/*.tmpdir/**', '**/*.tmp', '**/*.TMP'],
      },
      proxy: {
        '/api': {
          target: env.VITE_DEV_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    preview: {
      host: '0.0.0.0',
      port: 4173,
      allowedHosts: ['loombot.top', '.loombot.top'],
      proxy: {
        '/api': {
          target: env.VITE_DEV_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
        '/ws': {
          target: (env.VITE_DEV_API_TARGET || 'http://localhost:8080').replace(/^http/u, 'ws'),
          ws: true,
        },
      },
    },
    build: {
      sourcemap: true,
    },
  }
})
