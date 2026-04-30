import { createElement, type ReactNode } from 'react';
import {
  FiActivity,
  FiBarChart2,
  FiCalendar,
  FiCode,
  FiFileText,
  FiGlobe,
  FiList,
  FiMessageSquare,
  FiSettings,
  FiShuffle,
  FiTerminal,
  FiZap,
} from 'react-icons/fi';

export interface SidebarLink {
  to: string;
  label: string;
  icon: ReactNode;
  end?: boolean;
  requiresFlag?: 'selfEvolving';
}

export interface SidebarGroup {
  id: string;
  label: string;
  links: SidebarLink[];
  showAddSessionButton?: boolean;
}

export const SIDEBAR_GROUPS: SidebarGroup[] = [
  {
    id: 'sessions',
    label: 'Sessions',
    showAddSessionButton: true,
    links: [
      { to: '/', label: 'Chat', icon: createElement(FiMessageSquare, { size: 16 }), end: true },
      { to: '/sessions', label: 'Sessions', icon: createElement(FiList, { size: 16 }) },
    ],
  },
  {
    id: 'automation',
    label: 'Automation',
    links: [
      { to: '/scheduler', label: 'Scheduler', icon: createElement(FiCalendar, { size: 16 }) },
      { to: '/webhooks', label: 'Webhooks', icon: createElement(FiGlobe, { size: 16 }) },
    ],
  },
  {
    id: 'agent',
    label: 'Agent',
    links: [
      { to: '/skills', label: 'Skills', icon: createElement(FiZap, { size: 16 }) },
      { to: '/prompts', label: 'Prompts', icon: createElement(FiFileText, { size: 16 }) },
      { to: '/self-evolving', label: 'Self-Evolving', icon: createElement(FiShuffle, { size: 16 }), requiresFlag: 'selfEvolving' },
    ],
  },
  {
    id: 'observability',
    label: 'Observability',
    links: [
      { to: '/analytics', label: 'Analytics', icon: createElement(FiBarChart2, { size: 16 }) },
      { to: '/logs', label: 'Logs', icon: createElement(FiTerminal, { size: 16 }) },
      { to: '/diagnostics', label: 'Diagnostics', icon: createElement(FiActivity, { size: 16 }) },
    ],
  },
  {
    id: 'workspace',
    label: 'Workspace',
    links: [
      { to: '/workspace', label: 'Workspace', icon: createElement(FiCode, { size: 16 }) },
    ],
  },
  {
    id: 'system',
    label: 'System',
    links: [
      { to: '/settings', label: 'Settings', icon: createElement(FiSettings, { size: 16 }) },
    ],
  },
];
