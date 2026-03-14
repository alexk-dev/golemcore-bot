import client from './client';

export interface SkillInfo {
  name: string;
  description: string;
  available: boolean;
  content?: string;
  modelTier?: string;
  hasMcp: boolean;
  resolvedVariables?: Record<string, string>;
}

export interface SkillMarketplaceItem {
  id: string;
  name: string;
  description?: string | null;
  maintainer?: string | null;
  maintainerDisplayName?: string | null;
  artifactId?: string | null;
  artifactType?: 'skill' | 'pack' | null;
  version?: string | null;
  modelTier?: string | null;
  sourcePath?: string | null;
  skillRefs: string[];
  skillCount: number;
  installed: boolean;
  updateAvailable: boolean;
}

export interface SkillMarketplaceCatalogResponse {
  available: boolean;
  message?: string | null;
  sourceType?: 'repository' | 'directory' | null;
  sourceDirectory?: string | null;
  items: SkillMarketplaceItem[];
}

export interface ClawHubSkillItem {
  slug: string;
  displayName: string;
  summary?: string | null;
  version?: string | null;
  updatedAt?: number | null;
  installed: boolean;
  installedVersion?: string | null;
  runtimeName: string;
}

export interface ClawHubSkillCatalogResponse {
  available: boolean;
  message?: string | null;
  siteUrl?: string | null;
  items: ClawHubSkillItem[];
}

export interface SkillInstallResult {
  status: string;
  message: string;
  skill?: SkillMarketplaceItem | null;
}

export interface ClawHubInstallResult {
  status: string;
  message: string;
  skill?: ClawHubSkillItem | null;
}

export async function listSkills(): Promise<SkillInfo[]> {
  const { data } = await client.get<SkillInfo[]>('/skills');
  return data;
}

export async function getSkill(name: string): Promise<SkillInfo> {
  const { data } = await client.get<SkillInfo>('/skills/detail', {
    params: { name },
  });
  return data;
}

export async function getSkillMarketplace(): Promise<SkillMarketplaceCatalogResponse> {
  const { data } = await client.get<SkillMarketplaceCatalogResponse>('/skills/marketplace');
  return data;
}

export async function getClawHubSkills(query?: string, limit?: number): Promise<ClawHubSkillCatalogResponse> {
  const { data } = await client.get<ClawHubSkillCatalogResponse>('/skills/clawhub', {
    params: {
      q: query != null && query.length > 0 ? query : undefined,
      limit,
    },
  });
  return data;
}

export async function installSkillFromMarketplace(skillId: string): Promise<SkillInstallResult> {
  const { data } = await client.post<SkillInstallResult>('/skills/marketplace/install', { skillId });
  return data;
}

export async function installClawHubSkill(slug: string, version?: string | null): Promise<ClawHubInstallResult> {
  const { data } = await client.post<ClawHubInstallResult>('/skills/clawhub/install', { slug, version });
  return data;
}

export async function createSkill(name: string, content: string): Promise<SkillInfo> {
  const { data } = await client.post<SkillInfo>('/skills', { name, content });
  return data;
}

export async function updateSkill(name: string, content: string): Promise<SkillInfo> {
  const { data } = await client.put<SkillInfo>('/skills/detail', { content }, {
    params: { name },
  });
  return data;
}

export async function deleteSkill(name: string): Promise<void> {
  await client.delete('/skills/detail', {
    params: { name },
  });
}

export async function reloadSkill(name: string): Promise<void> {
  await client.post('/skills/detail/reload', null, {
    params: { name },
  });
}

export async function getMcpStatus(name: string): Promise<{ hasMcp: boolean; running?: boolean; tools?: string[] }> {
  const { data } = await client.get<{ hasMcp: boolean; running?: boolean; tools?: string[] }>('/skills/detail/mcp-status', {
    params: { name },
  });
  return data;
}
