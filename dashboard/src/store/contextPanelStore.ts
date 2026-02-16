import { create } from 'zustand';
import type { Goal } from '../api/goals';

export interface TurnMetadata {
  model: string | null;
  tier: string | null;
  reasoning: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  latencyMs: number | null;
  maxContextTokens: number | null;
}

interface ContextPanelState {
  panelOpen: boolean;
  turnMetadata: TurnMetadata;
  goals: Goal[];
  goalsFeatureEnabled: boolean;
  autoModeEnabled: boolean;
  togglePanel: () => void;
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
};

export const useContextPanelStore = create<ContextPanelState>()((set) => ({
  panelOpen: true,
  turnMetadata: { ...emptyMetadata },
  goals: [],
  goalsFeatureEnabled: false,
  autoModeEnabled: false,
  togglePanel: () => set((s) => ({ panelOpen: !s.panelOpen })),
  setTurnMetadata: (meta) =>
    set((s) => ({ turnMetadata: { ...s.turnMetadata, ...meta } })),
  setGoals: (goals, featureEnabled, autoModeEnabled) =>
    set({ goals, goalsFeatureEnabled: featureEnabled, autoModeEnabled }),
}));
