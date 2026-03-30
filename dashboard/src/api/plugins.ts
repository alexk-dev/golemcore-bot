import client from './client';

export interface PluginSettingsCatalogItem {
  pluginId: string;
  pluginName: string;
  provider: string;
  sectionKey: string;
  routeKey: string;
  title: string;
  description: string;
  blockKey?: string | null;
  blockTitle?: string | null;
  blockDescription?: string | null;
  order?: number | null;
}

export interface PluginSettingsFieldOption {
  value: string;
  label: string;
  description?: string | null;
}

export interface PluginSettingsField {
  key: string;
  type: string;
  label: string;
  description?: string | null;
  placeholder?: string | null;
  required?: boolean | null;
  readOnly?: boolean | null;
  masked?: boolean | null;
  copyable?: boolean | null;
  min?: number | null;
  max?: number | null;
  step?: number | null;
  options?: PluginSettingsFieldOption[];
}

export interface PluginSettingsAction {
  actionId: string;
  label: string;
  variant?: string | null;
  confirmationMessage?: string | null;
}

export interface PluginSettingsTableColumn {
  key: string;
  label: string;
}

export interface PluginSettingsTableRow {
  id: string;
  cells: Record<string, unknown>;
  actions?: PluginSettingsAction[];
}

export interface PluginSettingsBlock {
  type: string;
  key: string;
  title?: string | null;
  description?: string | null;
  variant?: string | null;
  text?: string | null;
  columns?: PluginSettingsTableColumn[];
  rows?: PluginSettingsTableRow[];
}

export interface PluginSettingsSection {
  pluginId: string;
  pluginName: string;
  provider: string;
  sectionKey: string;
  routeKey: string;
  title: string;
  description: string;
  fields: PluginSettingsField[];
  values: Record<string, unknown>;
  blocks: PluginSettingsBlock[];
  actions: PluginSettingsAction[];
}

export interface PluginActionResult {
  status: string;
  message: string;
}

export interface PluginMarketplaceItem {
  id: string;
  provider: string;
  name: string;
  description?: string | null;
  version: string;
  pluginApiVersion?: number | null;
  engineVersion?: string | null;
  sourceUrl?: string | null;
  license?: string | null;
  maintainers: string[];
  official: boolean;
  compatible: boolean;
  artifactAvailable: boolean;
  installed: boolean;
  loaded: boolean;
  updateAvailable: boolean;
  installedVersion?: string | null;
  loadedVersion?: string | null;
  settingsRouteKey?: string | null;
}

export interface PluginMarketplaceCatalogResponse {
  available: boolean;
  message?: string | null;
  sourceDirectory?: string | null;
  items: PluginMarketplaceItem[];
}

export interface PluginInstallResult {
  status: string;
  message: string;
  plugin?: PluginMarketplaceItem | null;
}

export interface PluginUninstallResult {
  status: string;
  message: string;
}

export interface VoiceProvidersResponse {
  stt: Record<string, string>;
  tts: Record<string, string>;
}

export async function getPluginSettingsCatalog(): Promise<PluginSettingsCatalogItem[]> {
  const { data } = await client.get<PluginSettingsCatalogItem[]>('/plugins/settings/catalog');
  return data;
}

export async function getPluginMarketplace(): Promise<PluginMarketplaceCatalogResponse> {
  const { data } = await client.get<PluginMarketplaceCatalogResponse>('/plugins/marketplace');
  return data;
}

export async function getPluginSettingsSection(routeKey: string): Promise<PluginSettingsSection> {
  const { data } = await client.get<PluginSettingsSection>(`/plugins/settings/sections/${routeKey}`);
  return data;
}

export async function installPluginFromMarketplace(
  pluginId: string,
  version?: string | null,
): Promise<PluginInstallResult> {
  const { data } = await client.post<PluginInstallResult>('/plugins/marketplace/install', {
    pluginId,
    version: version ?? null,
  });
  return data;
}

export async function uninstallPluginFromMarketplace(pluginId: string): Promise<PluginUninstallResult> {
  const { data } = await client.post<PluginUninstallResult>('/plugins/marketplace/uninstall', {
    pluginId,
  });
  return data;
}

export async function savePluginSettingsSection(
  routeKey: string,
  values: Record<string, unknown>,
): Promise<PluginSettingsSection> {
  const { data } = await client.put<PluginSettingsSection>(`/plugins/settings/sections/${routeKey}`, values);
  return data;
}

export async function executePluginSettingsAction(
  routeKey: string,
  actionId: string,
  payload: Record<string, unknown> = {},
): Promise<PluginActionResult> {
  const { data } = await client.post<PluginActionResult>(
    `/plugins/settings/sections/${routeKey}/actions/${actionId}`,
    payload,
  );
  return data;
}

export async function getVoiceProviders(): Promise<VoiceProvidersResponse> {
  const { data } = await client.get<VoiceProvidersResponse>('/plugins/voice/providers');
  return data;
}
