import type { IconType } from 'react-icons';
import {
  FiSliders, FiSend, FiCpu, FiTool, FiMic,
  FiGlobe, FiPlayCircle, FiShield, FiSearch, FiHardDrive, FiBarChart2,
  FiTerminal, FiMail, FiCompass, FiShuffle, FiKey,
} from 'react-icons/fi';

export interface SettingsSectionMeta {
  key: string;
  title: string;
  description: string;
  icon: IconType;
}

export const SETTINGS_SECTIONS = [
  { key: 'general', title: 'General', description: 'Preferences, account security, and MFA', icon: FiSliders },
  { key: 'telegram', title: 'Telegram', description: 'Bot token, auth mode, and invite codes', icon: FiSend },
  { key: 'models', title: 'Model Router', description: 'Routing and tier model configuration', icon: FiCpu },
  { key: 'llm-providers', title: 'LLM Providers', description: 'Provider API keys and base URLs', icon: FiKey },

  { key: 'tool-browser', title: 'Browser', description: 'Web browsing tool runtime status and behavior', icon: FiCompass },
  { key: 'tool-brave', title: 'Brave Search', description: 'Brave API search tool', icon: FiSearch },
  { key: 'tool-filesystem', title: 'Filesystem Tool', description: 'Sandbox file read/write operations', icon: FiHardDrive },
  { key: 'tool-shell', title: 'Shell Tool', description: 'Sandbox shell command execution', icon: FiTerminal },
  { key: 'tool-email', title: 'Email (IMAP/SMTP)', description: 'Email reading and sending integrations', icon: FiMail },
  { key: 'tool-automation', title: 'Automation Tools', description: 'Skill management, transitions, and tier switching', icon: FiShuffle },
  { key: 'tool-goals', title: 'Goal Management', description: 'Auto mode goal operations', icon: FiTool },

  { key: 'voice-elevenlabs', title: 'ElevenLabs', description: 'TTS/STT provider settings', icon: FiMic },
  { key: 'memory', title: 'Memory', description: 'Conversation memory persistence and retention', icon: FiHardDrive },
  { key: 'skills', title: 'Skills Runtime', description: 'Enable skills and progressive loading behavior', icon: FiTool },
  { key: 'turn', title: 'Turn Budget', description: 'Runtime limits for LLM/tool calls and deadline', icon: FiCpu },
  { key: 'usage', title: 'Usage Tracking', description: 'Enable/disable analytics usage tracking', icon: FiBarChart2 },
  { key: 'rag', title: 'RAG', description: 'LightRAG integration settings', icon: FiGlobe },
  { key: 'mcp', title: 'MCP', description: 'Model Context Protocol runtime defaults', icon: FiTool },
  { key: 'webhooks', title: 'Webhooks', description: 'Incoming hooks, auth, and delivery actions', icon: FiGlobe },
  { key: 'auto', title: 'Auto Mode', description: 'Autonomous run behavior and constraints', icon: FiPlayCircle },
  { key: 'advanced-rate-limit', title: 'Rate Limit', description: 'Request throttling configuration', icon: FiShield },
  { key: 'advanced-security', title: 'Security', description: 'Input sanitization and injection guards', icon: FiShield },
  { key: 'advanced-compaction', title: 'Compaction', description: 'Context compaction behavior', icon: FiShield },
] as const;

export type SettingsSectionKey = typeof SETTINGS_SECTIONS[number]['key'];

export interface SettingsBlock {
  key: string;
  title: string;
  description: string;
  sections: SettingsSectionKey[];
}

export const SETTINGS_BLOCKS: SettingsBlock[] = [
  {
    key: 'core',
    title: 'Core',
    description: 'Main runtime settings and access configuration',
    sections: ['general', 'telegram', 'models', 'llm-providers'],
  },
  {
    key: 'tools',
    title: 'Tools',
    description: 'Tool-specific runtime behavior and integrations',
    sections: ['tool-browser', 'tool-brave', 'tool-filesystem', 'tool-shell', 'tool-email', 'tool-automation', 'tool-goals'],
  },
  {
    key: 'runtime',
    title: 'Runtime',
    description: 'Agent execution, memory, usage, and autonomy',
    sections: ['voice-elevenlabs', 'memory', 'skills', 'turn', 'usage', 'rag', 'mcp', 'auto', 'webhooks'],
  },
  {
    key: 'advanced',
    title: 'Advanced',
    description: 'Security and infrastructure guardrails',
    sections: ['advanced-rate-limit', 'advanced-security', 'advanced-compaction'],
  },
];

export function isSettingsSectionKey(value: string | undefined): value is SettingsSectionKey {
  return SETTINGS_SECTIONS.some((section) => section.key === value);
}
