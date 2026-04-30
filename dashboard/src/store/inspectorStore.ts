import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type InspectorTab = 'inspector' | 'plan' | 'tools' | 'logs' | 'memory';

export const INSPECTOR_TABS: ReadonlyArray<InspectorTab> = [
  'inspector',
  'plan',
  'tools',
  'logs',
  'memory',
];

interface InspectorState {
  activeTab: InspectorTab;
  panelOpen: boolean;
  setActiveTab: (tab: InspectorTab) => void;
  togglePanel: () => void;
  setPanelOpen: (open: boolean) => void;
}

export const useInspectorStore = create<InspectorState>()(
  persist(
    (set) => ({
      activeTab: 'inspector',
      panelOpen: true,
      setActiveTab: (tab: InspectorTab) => set({ activeTab: tab }),
      togglePanel: () => set((state) => ({ panelOpen: !state.panelOpen })),
      setPanelOpen: (open: boolean) => set({ panelOpen: open }),
    }),
    { name: 'gc.harness.inspector' },
  ),
);
