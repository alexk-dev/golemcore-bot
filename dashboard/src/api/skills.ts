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

export async function listSkills(): Promise<SkillInfo[]> {
  const { data } = await client.get('/skills');
  return data;
}

export async function getSkill(name: string): Promise<SkillInfo> {
  const { data } = await client.get(`/skills/${name}`);
  return data;
}

export async function updateSkill(name: string, content: string) {
  const { data } = await client.put(`/skills/${name}`, { content });
  return data;
}

export async function reloadSkill(name: string) {
  await client.post(`/skills/${name}/reload`);
}

export async function getMcpStatus(name: string) {
  const { data } = await client.get(`/skills/${name}/mcp-status`);
  return data as { hasMcp: boolean; running?: boolean; tools?: string[] };
}
