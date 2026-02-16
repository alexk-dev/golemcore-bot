import client from './client';

export interface PromptSection {
  name: string;
  description: string;
  order: number;
  enabled: boolean;
  content: string;
}

export async function listPrompts(): Promise<PromptSection[]> {
  const { data } = await client.get('/prompts');
  return data;
}

export async function getPrompt(name: string): Promise<PromptSection> {
  const { data } = await client.get(`/prompts/${name}`);
  return data;
}

export async function updatePrompt(name: string, section: Partial<PromptSection>) {
  const { data } = await client.put(`/prompts/${name}`, section);
  return data;
}

export async function previewPrompt(name: string) {
  const { data } = await client.post(`/prompts/${name}/preview`);
  return data as { rendered: string };
}

export async function reloadPrompts() {
  await client.post('/prompts/reload');
}
