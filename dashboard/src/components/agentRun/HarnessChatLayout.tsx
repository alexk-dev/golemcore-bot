import { useState, type ReactNode } from 'react';
import TaskHeader from './TaskHeader';
import { useChatRunSummary } from './useChatRunSummary';

interface HarnessChatLayoutProps {
  children: ReactNode;
}

export default function HarnessChatLayout({ children }: HarnessChatLayoutProps) {
  const summary = useChatRunSummary();
  const [titleOverride, setTitleOverride] = useState<string | null>(null);
  const title = titleOverride ?? summary.title;

  return (
    <div className="harness-chat-layout">
      <div className="harness-chat-layout__hero">
        <TaskHeader
          title={title}
          status={summary.status}
          startedAt={summary.startedAt}
          durationMs={summary.durationMs}
          stepCount={summary.stepCount}
          onTitleChange={(next) => setTitleOverride(next)}
        />
      </div>
      <div className="harness-chat-layout__body">
        {children}
      </div>
    </div>
  );
}
