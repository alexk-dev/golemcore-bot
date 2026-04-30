import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from 'react-hot-toast';
import App from './App';
import './styles/tailwind.css';
import './styles/custom.scss';
import './styles/workspace.scss';
import './styles/responsive.scss';
import './styles/harness.scss';
import './styles/agentRun.scss';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
});

const rootElement = document.getElementById('root');

if (rootElement === null) {
  throw new Error('Root element not found');
}

ReactDOM.createRoot(rootElement).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename="/dashboard">
        <App />
        <Toaster
          position="top-right"
          toastOptions={{
            className: 'rounded-2xl border border-border/80 bg-card/95 text-card-foreground shadow-2xl backdrop-blur-xl',
          }}
        />
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>
);
