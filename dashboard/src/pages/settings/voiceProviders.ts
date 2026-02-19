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
