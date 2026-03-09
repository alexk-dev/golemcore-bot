import type { IconType } from 'react-icons';
import {
  FiSliders, FiCpu, FiTool, FiMic,
  FiGlobe, FiPlayCircle, FiShield, FiHardDrive, FiBarChart2,
  FiTerminal, FiShuffle, FiKey, FiRefreshCw, FiPackage, FiDatabase,
} from 'react-icons/fi';

export interface SettingsSectionMeta {
  key: string;
  title: string;
  description: string;
  icon: IconType;
}

export const SETTINGS_SECTIONS = [
  { key: 'general', title: 'General', description: 'Preferences, account security, and MFA', icon: FiSliders },
  { key: 'llm-providers', title: 'LLM Providers', description: 'Provider API keys and base URLs', icon: FiKey },
  { key: 'model-catalog', title: 'Model Catalog', description: 'Edit model definitions and provider capability metadata', icon: FiDatabase },
  { key: 'models', title: 'Model Router', description: 'Routing and tier model configuration', icon: FiCpu },
  { key: 'plugins-marketplace', title: 'Plugin Marketplace', description: 'Browse, install, and update official integrations', icon: FiPackage },

  { key: 'tool-filesystem', title: 'Filesystem Tool', description: 'Sandbox file read/write operations', icon: FiHardDrive },
  { key: 'tool-shell', title: 'Shell Tool', description: 'Sandbox shell command execution', icon: FiTerminal },
  { key: 'tool-automation', title: 'Automation Tools', description: 'Skill management, transitions, and tier switching', icon: FiShuffle },
  { key: 'tool-goals', title: 'Goal Management', description: 'Auto mode goal operations', icon: FiTool },
  { key: 'tool-voice', title: 'Voice Routing', description: 'Enable voice and choose STT/TTS providers', icon: FiMic },

  { key: 'memory', title: 'Memory', description: 'Conversation memory persistence and retention', icon: FiHardDrive },
  { key: 'skills', title: 'Skills Runtime', description: 'Enable skills and progressive loading behavior', icon: FiTool },
  { key: 'turn', title: 'Turn Budget', description: 'Runtime limits for LLM/tool calls and deadline', icon: FiCpu },
  { key: 'usage', title: 'Usage Tracking', description: 'Enable/disable analytics usage tracking', icon: FiBarChart2 },
  { key: 'mcp', title: 'MCP', description: 'Model Context Protocol runtime defaults', icon: FiTool },
  { key: 'webhooks', title: 'Webhooks', description: 'Incoming hooks, auth, and delivery actions', icon: FiGlobe },
  { key: 'auto', title: 'Auto Mode', description: 'Autonomous run behavior and constraints', icon: FiPlayCircle },
  { key: 'updates', title: 'Updates', description: 'Check and install latest patch update', icon: FiRefreshCw },
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
    sections: ['general', 'llm-providers', 'model-catalog', 'models'],
  },
  {
    key: 'plugins',
    title: 'Extensions',
    description: 'Install and configure plugin-backed integrations',
    sections: ['plugins-marketplace'],
  },
  {
    key: 'tools',
    title: 'Tools',
    description: 'Tool-specific runtime behavior and integrations',
    sections: ['tool-filesystem', 'tool-shell', 'tool-automation', 'tool-goals', 'tool-voice'],
  },
  {
    key: 'runtime',
    title: 'Runtime',
    description: 'Agent execution, memory, usage, and autonomy',
    sections: ['memory', 'skills', 'turn', 'usage', 'mcp', 'auto', 'webhooks', 'updates'],
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
