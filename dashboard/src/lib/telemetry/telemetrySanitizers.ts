const UUID_SEGMENT_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const LONG_HEX_SEGMENT_PATTERN = /^[0-9a-f]{16,}$/i;
const LONG_NUMERIC_SEGMENT_PATTERN = /^\d{6,}$/;
const OPAQUE_SEGMENT_PATTERN = /^[A-Za-z0-9_-]{20,}$/;
const KNOWN_SAFE_SEGMENTS = new Set(['chat', 'settings', 'sessions', 'analytics', 'setup', 'self-evolving']);

function shouldReplacePathSegment(segment: string): boolean {
  if (segment.length === 0 || KNOWN_SAFE_SEGMENTS.has(segment)) {
    return false;
  }

  return UUID_SEGMENT_PATTERN.test(segment)
    || LONG_HEX_SEGMENT_PATTERN.test(segment)
    || LONG_NUMERIC_SEGMENT_PATTERN.test(segment)
    || OPAQUE_SEGMENT_PATTERN.test(segment);
}

export function sanitizeTelemetryRoute(route: string | null | undefined): string {
  if (route == null || route.trim().length === 0) {
    return 'unknown';
  }

  const [pathname] = route.trim().split(/[?#]/, 1);
  const normalizedPath = pathname.startsWith('/') ? pathname : `/${pathname}`;
  if (normalizedPath === '/') {
    return normalizedPath;
  }

  const segments = normalizedPath
    .split('/')
    .filter((segment) => segment.length > 0)
    .map((segment) => (shouldReplacePathSegment(segment) ? ':id' : segment));

  return `/${segments.join('/')}`;
}

export function sanitizeTelemetryErrorName(errorName: string | null | undefined): string {
  if (errorName == null || errorName.trim().length === 0) {
    return 'UnknownError';
  }
  return errorName.trim();
}

export function sanitizeTelemetrySource(source: string | null | undefined): string {
  if (source == null || source.trim().length === 0) {
    return 'unknown';
  }
  return source.trim();
}

export function createTelemetryErrorFingerprint(
  source: string | null | undefined,
  route: string | null | undefined,
  errorName: string | null | undefined,
): string {
  return [
    sanitizeTelemetrySource(source),
    sanitizeTelemetryRoute(route),
    sanitizeTelemetryErrorName(errorName),
  ].join('|');
}
