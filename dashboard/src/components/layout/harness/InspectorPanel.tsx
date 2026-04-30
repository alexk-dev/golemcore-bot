import { useChatRuntimeStore } from '../../../store/chatRuntimeStore';
import { useChatSessionStore } from '../../../store/chatSessionStore';
import { useInspectorStore, INSPECTOR_TABS, type InspectorTab } from '../../../store/inspectorStore';
import type { ChatRuntimeSessionState } from '../../chat/chatRuntimeTypes';
import InspectorSummaryTab from './inspector/InspectorSummaryTab';
import InspectorPlanTab from './inspector/InspectorPlanTab';
import InspectorToolsTab from './inspector/InspectorToolsTab';
import InspectorLogsTab from './inspector/InspectorLogsTab';
import InspectorMemoryTab from './inspector/InspectorMemoryTab';

const TAB_LABELS: Record<InspectorTab, string> = {
  inspector: 'Inspector',
  plan: 'Plan',
  tools: 'Tools',
  logs: 'Logs',
  memory: 'Memory',
};

function InspectorTabBody({ tab, session }: { tab: InspectorTab; session: ChatRuntimeSessionState | undefined }) {
  if (tab === 'inspector') {
    return <InspectorSummaryTab session={session} />;
  }
  if (tab === 'plan') {
    return <InspectorPlanTab />;
  }
  if (tab === 'tools') {
    return <InspectorToolsTab />;
  }
  if (tab === 'logs') {
    return <InspectorLogsTab />;
  }
  return <InspectorMemoryTab />;
}

export default function InspectorPanel() {
  const activeTab = useInspectorStore((s) => s.activeTab);
  const setActiveTab = useInspectorStore((s) => s.setActiveTab);
  const activeSessionId = useChatSessionStore((s) => s.activeSessionId);
  const session = useChatRuntimeStore((s) => s.sessions[activeSessionId]);

  return (
    <aside className="harness-inspector" aria-label="Inspector">
      <div className="harness-inspector__tabs" role="tablist" aria-label="Inspector sections">
        {INSPECTOR_TABS.map((tab) => {
          const isActive = tab === activeTab;
          return (
            <button
              key={tab}
              type="button"
              role="tab"
              id={`inspector-tab-${tab}`}
              aria-selected={isActive}
              aria-controls={`inspector-panel-${tab}`}
              tabIndex={isActive ? 0 : -1}
              className={`harness-inspector__tab${isActive ? ' harness-inspector__tab--active' : ''}`}
              onClick={() => setActiveTab(tab)}
            >
              {TAB_LABELS[tab]}
            </button>
          );
        })}
      </div>
      <div
        className="harness-inspector__body"
        role="tabpanel"
        id={`inspector-panel-${activeTab}`}
        aria-labelledby={`inspector-tab-${activeTab}`}
      >
        <InspectorTabBody tab={activeTab} session={session} />
      </div>
    </aside>
  );
}
