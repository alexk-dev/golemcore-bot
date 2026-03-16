import client from './client';

export interface PromptSection {
  name: string;
  description: string;
  order: number;
  enabled: boolean;
  deletable: boolean;
  content: string;
}

export interface PromptSectionPayload {
  description: string;
  order: number;
  enabled: boolean;
  content: string;
}

export interface PromptReorderPayload {
  name: string;
  section: PromptSectionPayload;
}

export async function listPrompts(): Promise<PromptSection[]> {
  const { data } = await client.get<PromptSection[]>('/prompts');
  return data;
}

export async function getPrompt(name: string): Promise<PromptSection> {
  const { data } = await client.get<PromptSection>(`/prompts/${name}`);
  return data;
}

export async function createPrompt(
  name: string,
  section: PromptSectionPayload
): Promise<PromptSection> {
  const { data } = await client.post<PromptSection>('/prompts', { name, ...section });
  return data;
}

export async function updatePrompt(
  name: string,
  section: PromptSectionPayload
): Promise<PromptSection> {
  const { data } = await client.put<PromptSection>(`/prompts/${name}`, section);
  return data;
}

export async function previewPrompt(
  name: string,
  section?: PromptSectionPayload
): Promise<{ rendered: string }> {
  const { data } = await client.post<{ rendered: string }>(`/prompts/${name}/preview`, section);
  return data;
}

export async function deletePrompt(name: string): Promise<void> {
  await client.delete(`/prompts/${name}`);
}

export async function reorderPrompts(entries: PromptReorderPayload[]): Promise<void> {
  await Promise.all(entries.map((entry) => updatePrompt(entry.name, entry.section)));
}

export async function reloadPrompts(): Promise<void> {
  await client.post('/prompts/reload');
}
