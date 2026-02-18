import { create } from 'zustand';

type Theme = 'light' | 'dark';

interface ThemeState {
  theme: Theme;
  toggle: () => void;
}

function getInitialTheme(): Theme {
  const stored = localStorage.getItem('theme');
  if (stored === 'dark' || stored === 'light') {return stored;}
  if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) {return 'dark';}
  return 'light';
}

function applyTheme(theme: Theme) {
  document.documentElement.setAttribute('data-bs-theme', theme);
}

const initialTheme = getInitialTheme();
applyTheme(initialTheme);

export const useThemeStore = create<ThemeState>((set) => ({
  theme: initialTheme,
  toggle: () =>
    set((state) => {
      const next: Theme = state.theme === 'light' ? 'dark' : 'light';
      localStorage.setItem('theme', next);
      applyTheme(next);
      return { theme: next };
    }),
}));
