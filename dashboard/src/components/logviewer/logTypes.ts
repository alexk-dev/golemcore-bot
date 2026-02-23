import type { LogEntryResponse } from '../../api/system';

export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export type LogLevelFilter = Record<LogLevel, boolean>;

export interface LogBatchPayload {
  type?: string;
  items?: unknown;
}

export interface LogsViewRow {
  entry: LogEntryResponse;
  isSelected: boolean;
}
