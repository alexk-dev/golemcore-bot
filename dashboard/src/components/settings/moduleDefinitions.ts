import type { ModuleDefinition } from './ModuleSettingsSection';

export const braveModuleDefinition: ModuleDefinition = {
  id: 'brave-search',
  title: 'Brave Search Module',
  description: 'Plugin-style settings for Brave Search integration.',
  fields: [
    {
      key: 'enabled',
      label: 'Enabled',
      type: 'switch',
      help: 'Turn Brave Search module on/off.',
    },
    {
      key: 'apiKey',
      label: 'API Key',
      type: 'password',
      placeholder: 'BSA-...',
      help: 'Leave as *** to keep current key.',
    },
  ],
};

export const elevenLabsModuleDefinition: ModuleDefinition = {
  id: 'elevenlabs',
  title: 'ElevenLabs Module',
  description: 'Plugin-style settings for voice synthesis/transcription.',
  fields: [
    {
      key: 'enabled',
      label: 'Enabled',
      type: 'switch',
      help: 'Enable voice features for ElevenLabs.',
    },
    {
      key: 'apiKey',
      label: 'API Key',
      type: 'password',
      placeholder: 'sk_...',
      help: 'Leave as *** to keep current key.',
    },
    {
      key: 'voiceId',
      label: 'Voice ID',
      type: 'text',
      placeholder: 'EXAVITQu4vr4xnSDxMaL',
    },
    {
      key: 'ttsModelId',
      label: 'TTS Model ID',
      type: 'text',
      placeholder: 'eleven_multilingual_v2',
    },
    {
      key: 'sttModelId',
      label: 'STT Model ID',
      type: 'text',
      placeholder: 'scribe_v1',
    },
  ],
};
