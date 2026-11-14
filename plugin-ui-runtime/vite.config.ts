import { defineConfig } from 'vite'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const src = (path: string): string => fileURLToPath(new URL(path, import.meta.url))

export default defineConfig({
  base: '/plugin-ui/',
  esbuild: { jsx: 'automatic' },
  resolve: {
    dedupe: ['react', 'react-dom'],
    alias: [{ find: /^node:module$/, replacement: src('./src/node-module-stub.ts') }],
  },
  define: {
    'process.versions.node': '"0.0.0"',
    'process.execArgv': '[]',
    'process.env.CORDIS_SHARED': 'undefined',
  },
  build: {
    outDir: resolve(import.meta.dirname, '../app/src/main/assets/plugin-ui'),
    emptyOutDir: true,
    sourcemap: false,
    target: 'es2022',
  },
})
