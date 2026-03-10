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
  modelTier?: string | null;
  sourcePath?: string | null;
  installed: boolean;
  updateAvailable: boolean;
}

export interface SkillMarketplaceCatalogResponse {
  available: boolean;
  message?: string | null;
  sourceDirectory?: string | null;
  items: SkillMarketplaceItem[];
}

export interface SkillInstallResult {
  status: string;
  message: string;
  skill?: SkillMarketplaceItem | null;
}

export async function listSkills(): Promise<SkillInfo[]> {
  const { data } = await client.get<SkillInfo[]>('/skills');
  return data;
}

export async function getSkill(name: string): Promise<SkillInfo> {
  const { data } = await client.get<SkillInfo>(`/skills/${name}`);
  return data;
}

export async function getSkillMarketplace(): Promise<SkillMarketplaceCatalogResponse> {
  const { data } = await client.get<SkillMarketplaceCatalogResponse>('/skills/marketplace');
  return data;
}

export async function installSkillFromMarketplace(skillId: string): Promise<SkillInstallResult> {
  const { data } = await client.post<SkillInstallResult>('/skills/marketplace/install', { skillId });
  return data;
}

export async function createSkill(name: string, content: string): Promise<SkillInfo> {
  const { data } = await client.post<SkillInfo>('/skills', { name, content });
  return data;
}

export async function updateSkill(name: string, content: string): Promise<SkillInfo> {
  const { data } = await client.put<SkillInfo>(`/skills/${name}`, { content });
  return data;
}

export async function deleteSkill(name: string): Promise<void> {
  await client.delete(`/skills/${name}`);
}

export async function reloadSkill(name: string): Promise<void> {
  await client.post(`/skills/${name}/reload`);
}

export async function getMcpStatus(name: string): Promise<{ hasMcp: boolean; running?: boolean; tools?: string[] }> {
  const { data } = await client.get<{ hasMcp: boolean; running?: boolean; tools?: string[] }>(`/skills/${name}/mcp-status`);
  return data;
}
