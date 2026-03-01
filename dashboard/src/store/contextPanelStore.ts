import { create } from 'zustand';
import type { Goal } from '../api/goals';

export interface FileChangeStat {
  path: string;
  addedLines: number;
  removedLines: number;
  deleted: boolean;
}

export interface TurnMetadata {
  model: string | null;
  tier: string | null;
  reasoning: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  latencyMs: number | null;
  maxContextTokens: number | null;
  fileChanges: FileChangeStat[];
}

interface ContextPanelState {
  panelOpen: boolean;
  mobileDrawerOpen: boolean;
  turnMetadata: TurnMetadata;
  goals: Goal[];
  goalsFeatureEnabled: boolean;
  autoModeEnabled: boolean;
  togglePanel: () => void;
  openMobileDrawer: () => void;
  closeMobileDrawer: () => void;
  setTurnMetadata: (meta: Partial<TurnMetadata>) => void;
  setGoals: (goals: Goal[], featureEnabled: boolean, autoModeEnabled: boolean) => void;
}

const emptyMetadata: TurnMetadata = {
  model: null,
  tier: null,
  reasoning: null,
  inputTokens: null,
  outputTokens: null,
  totalTokens: null,
  latencyMs: null,
  maxContextTokens: null,
  fileChanges: [],
};

export const useContextPanelStore = create<ContextPanelState>()((set) => ({
  panelOpen: true,
  mobileDrawerOpen: false,
  turnMetadata: { ...emptyMetadata },
  goals: [],
  goalsFeatureEnabled: false,
  autoModeEnabled: false,
  togglePanel: () => set((s) => ({ panelOpen: !s.panelOpen })),
  openMobileDrawer: () => set({ mobileDrawerOpen: true }),
  closeMobileDrawer: () => set({ mobileDrawerOpen: false }),
  setTurnMetadata: (meta) =>
    set((s) => ({ turnMetadata: { ...s.turnMetadata, ...meta } })),
  setGoals: (goals, featureEnabled, autoModeEnabled) =>
    set({ goals, goalsFeatureEnabled: featureEnabled, autoModeEnabled }),
}));
