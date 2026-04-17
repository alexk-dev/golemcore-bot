import type { ReactElement } from 'react';

const DOCS_BASE_URL = 'https://docs.golemcore.me';

export type DocId =
  | 'overview'
  | 'quickstart'
  | 'dashboard'
  | 'model-routing'
  | 'memory'
  | 'memory-tuning'
  | 'skills'
  | 'plugins'
  | 'mcp'
  | 'auto-mode'
  | 'delayed-actions'
  | 'webhooks'
  | 'configuration'
  | 'deployment'
  | 'architecture'
  | 'troubleshooting';

export interface DocDefinition {
  title: string;
  shortLabel: string;
  path: string;
}

export interface DocLink extends DocDefinition {
  id: DocId;
  url: string;
}

interface PathDocsRule {
  docs: readonly DocId[];
  matches: (pathname: string) => boolean;
}

const DOC_DEFINITIONS: Record<DocId, DocDefinition> = {
  overview: {
    title: 'Overview',
    shortLabel: 'Overview',
    path: '/docs',
  },
  quickstart: {
    title: 'Quickstart',
    shortLabel: 'Quickstart',
    path: '/docs/user-guide/quickstart',
  },
  dashboard: {
    title: 'Dashboard',
    shortLabel: 'Dashboard',
    path: '/docs/user-guide/dashboard',
  },
  'model-routing': {
    title: 'Model Routing',
    shortLabel: 'Model Routing',
    path: '/docs/user-guide/model-routing',
  },
  memory: {
    title: 'Memory',
    shortLabel: 'Memory',
    path: '/docs/user-guide/memory',
  },
  'memory-tuning': {
    title: 'Memory Tuning',
    shortLabel: 'Memory Tuning',
    path: '/docs/user-guide/memory-tuning',
  },
  skills: {
    title: 'Skills',
    shortLabel: 'Skills',
    path: '/docs/user-guide/skills',
  },
  plugins: {
    title: 'Plugins',
    shortLabel: 'Plugins',
    path: '/docs/user-guide/plugins',
  },
  mcp: {
    title: 'MCP Servers',
    shortLabel: 'MCP',
    path: '/docs/user-guide/mcp',
  },
  'auto-mode': {
    title: 'Auto Mode',
    shortLabel: 'Auto Mode',
    path: '/docs/user-guide/auto-mode',
  },
  'delayed-actions': {
    title: 'Delayed Actions',
    shortLabel: 'Delayed Actions',
    path: '/docs/user-guide/delayed-actions',
  },
  webhooks: {
    title: 'Webhooks',
    shortLabel: 'Webhooks',
    path: '/docs/user-guide/webhooks',
  },
  configuration: {
    title: 'Configuration',
    shortLabel: 'Configuration',
    path: '/docs/user-guide/configuration',
  },
  deployment: {
    title: 'Deployment',
    shortLabel: 'Deployment',
    path: '/docs/user-guide/deployment',
  },
  architecture: {
    title: 'Architecture',
    shortLabel: 'Architecture',
    path: '/docs/developer-guide/architecture',
  },
  troubleshooting: {
    title: 'Troubleshooting',
    shortLabel: 'Troubleshooting',
    path: '/docs/reference/troubleshooting',
  },
};

const DEFAULT_SETTINGS_DOCS: readonly DocId[] = ['configuration', 'dashboard'];
const SETTINGS_SECTION_DOCS: Record<string, readonly DocId[]> = {
  general: ['dashboard', 'configuration'],
  'llm-providers': ['quickstart', 'configuration'],
  'model-catalog': ['quickstart', 'configuration'],
  models: ['model-routing', 'configuration'],
  'plugins-marketplace': ['plugins', 'configuration'],
  'tool-filesystem': ['dashboard', 'configuration'],
  'tool-shell': ['dashboard', 'configuration'],
  'tool-automation': ['dashboard', 'configuration'],
  'tool-goals': ['dashboard', 'configuration'],
  'tool-voice': ['dashboard', 'configuration'],
  memory: ['memory', 'memory-tuning'],
  skills: ['skills', 'mcp'],
  turn: ['dashboard', 'configuration'],
  usage: ['dashboard', 'configuration'],
  telemetry: ['dashboard', 'configuration'],
  tracing: ['dashboard', 'configuration'],
  mcp: ['mcp', 'skills'],
  hive: ['architecture', 'configuration'],
  'self-evolving': ['architecture', 'dashboard'],
  plan: ['auto-mode', 'delayed-actions'],
  auto: ['auto-mode', 'delayed-actions'],
  updates: ['deployment', 'configuration'],
  'advanced-rate-limit': ['configuration', 'troubleshooting'],
  'advanced-security': ['configuration', 'troubleshooting'],
  'advanced-compaction': ['configuration', 'troubleshooting'],
};
const PATH_DOC_RULES: PathDocsRule[] = [
  {
    docs: ['dashboard', 'quickstart'],
    matches: (pathname) => pathname === '/' || pathname.startsWith('/chat'),
  },
  {
    docs: ['quickstart', 'dashboard', 'configuration'],
    matches: (pathname) => pathname === '/setup',
  },
  {
    docs: ['skills', 'mcp'],
    matches: (pathname) => pathname.startsWith('/skills'),
  },
  {
    docs: ['webhooks', 'dashboard'],
    matches: (pathname) => pathname.startsWith('/webhooks'),
  },
  {
    docs: ['dashboard', 'skills'],
    matches: (pathname) => pathname.startsWith('/ide'),
  },
  {
    docs: ['auto-mode', 'delayed-actions'],
    matches: (pathname) => pathname.startsWith('/scheduler') || pathname.startsWith('/goals'),
  },
  {
    docs: ['dashboard', 'troubleshooting'],
    matches: (pathname) => pathname.startsWith('/logs')
      || pathname.startsWith('/sessions')
      || pathname.startsWith('/analytics')
      || pathname.startsWith('/diagnostics'),
  },
  {
    docs: ['dashboard', 'configuration'],
    matches: (pathname) => pathname.startsWith('/prompts'),
  },
  {
    docs: ['architecture', 'dashboard'],
    matches: (pathname) => pathname.startsWith('/self-evolving'),
  },
];

function normalizePath(pathname: string): string {
  if (pathname.length > 1 && pathname.endsWith('/')) {
    return pathname.slice(0, -1);
  }
  return pathname;
}

function dedupeDocIds(docIds: readonly DocId[]): DocId[] {
  const seen = new Set<DocId>();
  return docIds.filter((docId) => {
    if (seen.has(docId)) {
      return false;
    }
    seen.add(docId);
    return true;
  });
}

function cloneDocIds(docIds: readonly DocId[]): DocId[] {
  return [...docIds];
}

export function getDocLink(docId: DocId): DocLink {
  const definition = DOC_DEFINITIONS[docId];
  return {
    id: docId,
    title: definition.title,
    shortLabel: definition.shortLabel,
    path: definition.path,
    url: `${DOCS_BASE_URL}${definition.path}`,
  };
}

export function getDocLinks(docIds: readonly DocId[]): DocLink[] {
  return dedupeDocIds(docIds).map((docId) => getDocLink(docId));
}

export function getDocsForSettingsSection(section: string | null | undefined): DocId[] {
  if (section == null) {
    return cloneDocIds(DEFAULT_SETTINGS_DOCS);
  }

  return cloneDocIds(SETTINGS_SECTION_DOCS[section] ?? DEFAULT_SETTINGS_DOCS);
}

export function getDocsForPath(pathname: string): DocId[] {
  const normalizedPath = normalizePath(pathname);
  if (normalizedPath.startsWith('/settings')) {
    return getDocsForSettingsSection(normalizedPath.split('/')[2] ?? null);
  }

  const matchedRule = PATH_DOC_RULES.find((rule) => rule.matches(normalizedPath));
  if (matchedRule != null) {
    return cloneDocIds(matchedRule.docs);
  }

  return ['overview'];
}

export function getPrimaryDocForPath(pathname: string): DocLink | null {
  const docs = getDocLinks(getDocsForPath(pathname));
  return docs[0] ?? null;
}

export interface DocsRouteMatch {
  doc: DocLink;
  renderLabel: () => ReactElement | string;
}
