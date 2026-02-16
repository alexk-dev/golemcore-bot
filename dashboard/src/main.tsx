import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from 'react-hot-toast';
import App from './App';
import 'bootswatch/dist/zephyr/bootstrap.min.css';
import './styles/custom.scss';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename="/dashboard">
        <App />
        <Toaster
          position="top-right"
          toastOptions={{
            className: 'bg-body text-body border shadow-sm',
          }}
        />
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>
);
