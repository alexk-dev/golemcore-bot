import { create } from 'zustand';
import type { Goal, GoalTask } from '../api/goals';

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
  standaloneTasks: GoalTask[];
  goalsFeatureEnabled: boolean;
  autoModeEnabled: boolean;
  togglePanel: () => void;
  openMobileDrawer: () => void;
  closeMobileDrawer: () => void;
  setTurnMetadata: (meta: Partial<TurnMetadata>) => void;
  setGoals: (
    goals: Goal[],
    standaloneTasks: GoalTask[],
    featureEnabled: boolean,
    autoModeEnabled: boolean,
  ) => void;
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
  standaloneTasks: [],
  goalsFeatureEnabled: false,
  autoModeEnabled: false,
  togglePanel: () => set((s) => ({ panelOpen: !s.panelOpen })),
  openMobileDrawer: () => set({ mobileDrawerOpen: true }),
  closeMobileDrawer: () => set({ mobileDrawerOpen: false }),
  setTurnMetadata: (meta) =>
    set((s) => ({ turnMetadata: { ...s.turnMetadata, ...meta } })),
  setGoals: (goals, standaloneTasks, featureEnabled, autoModeEnabled) =>
    set({ goals, standaloneTasks, goalsFeatureEnabled: featureEnabled, autoModeEnabled }),
}));
