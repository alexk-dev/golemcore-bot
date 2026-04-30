import { useEffect, useState } from 'react';
import { FiAlertTriangle, FiInfo, FiAlertOctagon } from 'react-icons/fi';
import type { IncidentActionViewModel, IncidentSeverity, IncidentViewModel } from './types';
import { formatCountdown, formatTimeOfDay } from './agentRunFormat';

type IncidentActionHandler = (actionId: IncidentActionViewModel['id']) => void;

interface IncidentCardProps {
  incident: IncidentViewModel;
  onAction?: IncidentActionHandler;
}

const SEVERITY_CLASS: Record<IncidentSeverity, string> = {
  info: 'incident-card--info',
  warning: 'incident-card--warning',
  error: '',
  critical: '',
};

function SeverityIcon({ severity }: { severity: IncidentSeverity }) {
  if (severity === 'info') {
    return <FiInfo size={16} aria-hidden="true" />;
  }
  if (severity === 'warning') {
    return <FiAlertTriangle size={16} aria-hidden="true" />;
  }
  return <FiAlertOctagon size={16} aria-hidden="true" />;
}

function useCountdown(targetIso: string | undefined, fallbackSeconds?: number): number | null {
  const [remaining, setRemaining] = useState<number | null>(() => {
    if (targetIso == null && fallbackSeconds == null) {
      return null;
    }
    if (targetIso != null) {
      const diffMs = new Date(targetIso).getTime() - Date.now();
      return Math.max(0, Math.floor(diffMs / 1000));
    }
    return fallbackSeconds ?? null;
  });

  // Tick every second while the countdown has time remaining; clean up on unmount.
  useEffect(() => {
    if (remaining == null || remaining <= 0) {
      return undefined;
    }
    const id = window.setInterval(() => {
      setRemaining((prev) => {
        if (prev == null) {
          return null;
        }
        if (targetIso != null) {
          const diffMs = new Date(targetIso).getTime() - Date.now();
          return Math.max(0, Math.floor(diffMs / 1000));
        }
        return Math.max(0, prev - 1);
      });
    }, 1000);
    return () => window.clearInterval(id);
  }, [remaining, targetIso]);

  return remaining;
}

export default function IncidentCard({ incident, onAction }: IncidentCardProps) {
  const remaining = useCountdown(incident.retryAt, incident.retryCountdownSeconds);
  return (
    <section
      className={`incident-card ${SEVERITY_CLASS[incident.severity]}`.trim()}
      aria-label={`${incident.title} incident`}
      role="alert"
    >
      <div className="incident-card__header">
        <SeverityIcon severity={incident.severity} />
        <span className="incident-card__title">{incident.title}</span>
        <span className="incident-card__time">{formatTimeOfDay(incident.createdAt)}</span>
      </div>
      <div className="incident-card__body">{incident.message}</div>
      <div className="incident-card__meta">
        {incident.code != null && <span>Reason: <code>{incident.code}</code></span>}
        {incident.taskSaved && <span>Task saved automatically</span>}
        {remaining != null && <span>Retry in: {formatCountdown(remaining)}</span>}
      </div>
      <div className="incident-card__actions">
        {incident.actions.map((action) => (
          <button
            key={action.id}
            type="button"
            className={`agent-btn${action.kind === 'primary' ? ' agent-btn--primary' : ''}${action.kind === 'danger' ? ' agent-btn--danger' : ''}`}
            onClick={() => onAction?.(action.id)}
          >
            {action.label}
          </button>
        ))}
      </div>
    </section>
  );
}
