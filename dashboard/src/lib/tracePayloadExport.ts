export function exportPayloadAsJson(payload: string, role: string | null, spanName: string | null): void {
  let formatted: string;
  try {
    const parsed: unknown = JSON.parse(payload);
    formatted = JSON.stringify(parsed, null, 2);
  } catch {
    formatted = payload;
  }
  const blob = new Blob([formatted], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `payload-${spanName ?? 'span'}-${role ?? 'snapshot'}.json`;
  link.click();
  URL.revokeObjectURL(url);
}
