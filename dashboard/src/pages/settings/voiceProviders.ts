export const STT_PROVIDER_ELEVENLABS = 'elevenlabs';
export const STT_PROVIDER_WHISPER = 'whisper';
export const TTS_PROVIDER_ELEVENLABS = 'elevenlabs';

export function resolveSttProvider(value: string | null | undefined): string {
  if (value === STT_PROVIDER_WHISPER) {
    return STT_PROVIDER_WHISPER;
  }
  return STT_PROVIDER_ELEVENLABS;
}

export function resolveTtsProvider(value: string | null | undefined): string {
  void value;
  return TTS_PROVIDER_ELEVENLABS;
}

export function isValidHttpUrl(value: string | null | undefined): boolean {
  if (value == null || value.trim().length === 0) {
    return false;
  }
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}
