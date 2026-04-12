import type { ReactElement } from 'react';

export type ActiveTab = 'runs' | 'candidates' | 'tactics';

const TAB_LABELS: Record<ActiveTab, { label: string; hint: string }> = {
  runs: { label: 'Runs', hint: 'Every agent session captured and judged by the self-evolving pipeline' },
  candidates: { label: 'Candidates', hint: 'Concrete proposals derived from judged runs, waiting for review or activation' },
  tactics: { label: 'Tactics', hint: 'Search the tactic library used by the agent at runtime' },
};

interface SelfEvolvingPageTabsProps {
  activeTab: ActiveTab;
  onSelectTab: (tab: ActiveTab) => void;
}

export function SelfEvolvingPageTabs({ activeTab, onSelectTab }: SelfEvolvingPageTabsProps): ReactElement {
  return (
    <div className="flex gap-1 border-b border-border/80 mb-4" role="tablist">
      {(Object.keys(TAB_LABELS) as ActiveTab[]).map((tab) => (
        <button
          key={tab}
          type="button"
          role="tab"
          aria-selected={activeTab === tab}
          title={TAB_LABELS[tab].hint}
          className={`px-4 py-2.5 text-sm font-semibold transition-colors border-b-2 -mb-px ${
            activeTab === tab
              ? 'border-primary text-foreground'
              : 'border-transparent text-muted-foreground hover:text-foreground hover:border-border'
          }`}
          onClick={() => onSelectTab(tab)}
        >
          {TAB_LABELS[tab].label}
        </button>
      ))}
    </div>
  );
}
