import type { ChatConnectionState } from '../../../store/chatRuntimeStore';
import type { ChatRuntimeSessionState } from '../../chat/chatRuntimeTypes';

export type ConnectionStatus = 'connected' | 'reconnecting' | 'disconnected';

const TIER_LABELS: Record<string, string> = {
  fast: 'Fast',
  balanced: 'Balanced',
  smart: 'Smart',
  coding: 'Coding',
};

export function resolveConnectionStatus(
  connectionState: ChatConnectionState,
  session: ChatRuntimeSessionState | undefined,
): ConnectionStatus {
  if (connectionState === 'connected' || connectionState === 'connecting') {
    if (session?.running === true || session?.typing === true) {
      return 'connected';
    }
    return 'connected';
  }
  if (connectionState === 'reconnecting') {
    return 'reconnecting';
  }
  return 'disconnected';
}

export function resolveModeLabel(session: ChatRuntimeSessionState | undefined): string {
  const tier = session?.turnMetadata.tier ?? null;
  if (tier == null) {
    return 'Coding';
  }
  return TIER_LABELS[tier] ?? tier.charAt(0).toUpperCase() + tier.slice(1);
}

export function resolvePlanModeLabel(session: ChatRuntimeSessionState | undefined): 'Plan ON' | 'Plan OFF' {
  const reasoning = session?.turnMetadata.reasoning ?? null;
  if (reasoning != null && reasoning.length > 0) {
    return 'Plan ON';
  }
  return 'Plan OFF';
}
