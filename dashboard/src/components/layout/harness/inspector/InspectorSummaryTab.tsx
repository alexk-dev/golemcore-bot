import type { ChatRuntimeSessionState } from '../../../chat/chatRuntimeTypes';
import { formatTokens } from './contextUsage';
import InspectorContextCard from './InspectorContextCard';
import InspectorActionsCard from './InspectorActionsCard';

interface InspectorSummaryTabProps {
  session: ChatRuntimeSessionState | undefined;
}

interface SummaryRowProps {
  label: string;
  value: string;
}

function SummaryRow({ label, value }: SummaryRowProps) {
  return (
    <div className="harness-inspector__card-row">
      <span className="harness-inspector__card-label">{label}</span>
      <span className="harness-inspector__card-value">{value}</span>
    </div>
  );
}

function resolveStatusLabel(session: ChatRuntimeSessionState | undefined): string {
  if (session == null) {
    return 'Idle';
  }
  if (session.running) {
    return 'Running';
  }
  if (session.typing) {
    return 'Streaming';
  }
  return 'Idle';
}

function resolveModeLabel(session: ChatRuntimeSessionState | undefined): string {
  const reasoning = session?.turnMetadata.reasoning ?? null;
  if (reasoning != null && reasoning.length > 0) {
    return 'Plan ON';
  }
  return 'Plan OFF';
}

function tokenValue(value: number | null): string {
  return value != null ? formatTokens(value) : '—';
}

export default function InspectorSummaryTab({ session }: InspectorSummaryTabProps) {
  const meta = session?.turnMetadata;

  return (
    <>
      <section className="harness-inspector__card" aria-label="Run summary">
        <h3 className="harness-inspector__card-title">Task</h3>
        <SummaryRow label="Status" value={resolveStatusLabel(session)} />
        <SummaryRow label="Mode" value={resolveModeLabel(session)} />
        <SummaryRow label="Model" value={meta?.model ?? '—'} />
        <SummaryRow label="Tier" value={meta?.tier ?? '—'} />
      </section>

      <InspectorContextCard meta={meta} />

      <section className="harness-inspector__card" aria-label="Token telemetry">
        <h3 className="harness-inspector__card-title">Tokens</h3>
        <SummaryRow label="Input" value={tokenValue(meta?.inputTokens ?? null)} />
        <SummaryRow label="Output" value={tokenValue(meta?.outputTokens ?? null)} />
        <SummaryRow label="Total" value={tokenValue(meta?.totalTokens ?? null)} />
        <SummaryRow label="Latency" value={meta?.latencyMs != null ? `${meta.latencyMs} ms` : '—'} />
      </section>

      <InspectorActionsCard />
    </>
  );
}
