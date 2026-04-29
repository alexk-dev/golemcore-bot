import { useSystemHealth } from '../../../hooks/useSystem';

export function useRailVersionLabel(): string {
  const { data: health } = useSystemHealth();
  return health?.version != null ? `v${health.version}` : 'v…';
}
