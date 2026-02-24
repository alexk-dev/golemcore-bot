import client from './client';

export type PluginSettingsFieldType = 'switch' | 'text' | 'password' | 'number' | 'select' | 'url';

export interface PluginSettingsFieldOption {
  value: string;
  label: string;
}

export interface PluginSettingsFieldSchema {
  key: string;
  label: string;
  help: string;
  type: PluginSettingsFieldType;
  placeholder: string | null;
  min: number | null;
  max: number | null;
  step: number | null;
  options: PluginSettingsFieldOption[];
}

export interface PluginSettingsSectionSchema {
  pluginId: string;
  sectionKey: string;
  pluginName: string;
  description: string;
  fields: PluginSettingsFieldSchema[];
}

export async function getPluginSettingsSchemas(): Promise<PluginSettingsSectionSchema[]> {
  const { data } = await client.get<PluginSettingsSectionSchema[]>('/settings/plugins/schemas');
  return data;
}
