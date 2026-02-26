import { type UseQueryResult, useQuery } from '@tanstack/react-query';
import { type PluginSettingsSectionSchema, getPluginSettingsSchemas } from '../api/plugins';

export function usePluginSettingsSchemas(): UseQueryResult<PluginSettingsSectionSchema[], unknown> {
  return useQuery({ queryKey: ['plugin-settings-schemas'], queryFn: getPluginSettingsSchemas });
}
