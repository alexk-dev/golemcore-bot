import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/dashboard/',
  build: {
    outDir: '../src/main/resources/static/dashboard',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return undefined;
          }

          if (id.includes('react-markdown') || id.includes('remark-gfm')) {
            return 'markdown';
          }

          if (id.includes('recharts')) {
            return 'charts';
          }

          if (id.includes('@tanstack/react-query') || id.includes('axios')) {
            return 'data';
          }

          if (
            id.includes('/react/') ||
            id.includes('react-dom') ||
            id.includes('scheduler')
          ) {
            return 'core-react';
          }

          if (id.includes('zustand')) {
            return 'state';
          }

          if (
            id.includes('react-bootstrap') ||
            id.includes('bootstrap') ||
            id.includes('bootswatch') ||
            id.includes('react-hot-toast') ||
            id.includes('react-icons')
          ) {
            return 'ui';
          }

          return 'vendor';
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
});
