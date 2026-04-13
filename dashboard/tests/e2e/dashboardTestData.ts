export const TEST_SESSION_ID = '11111111-1111-4111-8111-111111111111';
export const TEST_SESSION_RECORD_ID = 'session-1';

export const mockHealthResponse = {
  status: 'UP',
  version: 'test',
  gitCommit: 'local',
  buildTime: '2026-04-13T00:00:00Z',
  uptimeMs: 12345,
  channels: {
    web: { type: 'web', running: true, enabled: true },
  },
};

export const mockUpdateStatusResponse = {
  state: 'IDLE',
  enabled: true,
  autoEnabled: false,
  maintenanceWindowEnabled: false,
  maintenanceWindowStartUtc: null,
  maintenanceWindowEndUtc: null,
  serverTimezone: null,
  windowOpen: true,
  busy: false,
  blockedReason: null,
  nextEligibleAt: null,
  current: { version: 'test' },
  target: null,
  staged: null,
  available: null,
  lastCheckAt: null,
  lastError: null,
  progressPercent: null,
  stageTitle: null,
  stageDescription: null,
};

export const mockRuntimeConfig = {
  modelRegistry: { repositoryUrl: null, branch: 'main' },
  llm: {
    providers: {
      openai: {
        apiKey: { present: true },
        baseUrl: 'https://api.openai.com/v1',
        requestTimeoutSeconds: 60,
        apiType: 'openai',
        legacyApi: null,
      },
    },
  },
  modelRouter: {
    temperature: 0.2,
    routing: { model: { provider: 'openai', id: 'gpt-4.1-mini' }, reasoning: null },
    tiers: {
      balanced: { model: { provider: 'openai', id: 'gpt-4.1-mini' }, reasoning: null },
    },
    dynamicTierEnabled: true,
  },
  telemetry: { enabled: false, clientId: 'playwright-client' },
  hive: { enabled: false, sdlc: {} },
  selfEvolving: { enabled: false },
};

export const mockSettings = {
  language: 'en',
  timezone: 'UTC',
  modelTier: 'balanced',
  tierForce: false,
  webhooks: {
    enabled: true,
    token: { present: true },
    maxPayloadSize: 65536,
    defaultTimeoutSeconds: 300,
    mappings: [],
  },
};

export const mockModelsConfig = {
  models: {
    'openai/gpt-4.1-mini': {
      provider: 'openai',
      displayName: 'GPT 4.1 Mini',
      supportsVision: true,
      supportsTemperature: true,
      maxInputTokens: 128000,
      reasoning: null,
    },
  },
  defaults: {
    provider: 'openai',
    displayName: 'Default',
    supportsVision: false,
    supportsTemperature: true,
    maxInputTokens: 64000,
    reasoning: null,
  },
};

export const mockPrompts = [
  {
    name: 'identity',
    description: 'Core assistant identity',
    order: 10,
    enabled: true,
    deletable: false,
    content: 'You are GolemCore.',
  },
  {
    name: 'rules',
    description: 'System rules',
    order: 20,
    enabled: true,
    deletable: false,
    content: 'Follow the rules.',
  },
];

export const mockSkills = [
  {
    name: 'playwright-skill',
    description: 'Skill for dashboard smoke tests',
    available: true,
    hasMcp: false,
    content: '---\ndescription: "Skill for dashboard smoke tests"\n---\n',
  },
];

export const mockPluginMarketplace = {
  available: true,
  message: null,
  sourceDirectory: null,
  items: [],
};

export const mockHiveStatus = {
  state: 'DISABLED',
  enabled: false,
  managedByProperties: false,
  managedJoinCodeAvailable: false,
  autoConnect: false,
  serverUrl: null,
  displayName: null,
  hostLabel: null,
  sessionPresent: false,
  golemId: null,
  controlChannelUrl: null,
  heartbeatIntervalSeconds: null,
  lastConnectedAt: null,
  lastHeartbeatAt: null,
  lastTokenRotatedAt: null,
  controlChannelState: null,
  controlChannelConnectedAt: null,
  controlChannelLastMessageAt: null,
  controlChannelLastError: null,
  lastReceivedCommandId: null,
  lastReceivedCommandAt: null,
  receivedCommandCount: 0,
  bufferedCommandCount: 0,
  pendingCommandCount: 0,
  pendingEventBatchCount: 0,
  pendingEventCount: 0,
  outboxLastError: null,
  lastError: null,
  policyGroupId: null,
  targetPolicyVersion: null,
  appliedPolicyVersion: null,
  policySyncStatus: null,
  lastPolicyErrorDigest: null,
};

export const mockSchedulerState = {
  featureEnabled: true,
  autoModeEnabled: false,
  goals: [],
  standaloneTasks: [],
  schedules: [],
  reportChannelOptions: [],
};

export const mockSystemDiagnostics = {
  storage: {
    configuredBasePath: '/tmp/golemcore',
    resolvedBasePath: '/tmp/golemcore',
    sessionsFiles: 0,
    usageFiles: 0,
  },
  environment: {
    STORAGE_PATH: null,
    TOOLS_WORKSPACE: null,
    SPRING_PROFILES_ACTIVE: null,
  },
  runtime: {
    userDir: '/tmp/golemcore',
    userHome: '/tmp',
  },
};

export const mockLogsPage = {
  items: [],
  oldestSeq: null,
  newestSeq: null,
  hasMore: false,
};
