import client from './client';

export interface PromptSection {
  name: string;
  description: string;
  order: number;
  enabled: boolean;
  content: string;
}

export async function listPrompts(): Promise<PromptSection[]> {
  const { data } = await client.get<PromptSection[]>('/prompts');
  return data;
}

export async function getPrompt(name: string): Promise<PromptSection> {
  const { data } = await client.get<PromptSection>(`/prompts/${name}`);
  return data;
}

export async function updatePrompt(name: string, section: Partial<PromptSection>): Promise<PromptSection> {
  const { data } = await client.put<PromptSection>(`/prompts/${name}`, section);
  return data;
}

export async function previewPrompt(name: string): Promise<{ rendered: string }> {
  const { data } = await client.post<{ rendered: string }>(`/prompts/${name}/preview`);
  return data;
}

export async function reloadPrompts(): Promise<void> {
  await client.post('/prompts/reload');
}
