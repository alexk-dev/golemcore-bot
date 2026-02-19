import type { LogLevelFilter, LogLevel } from './logTypes';

export const LOG_LEVELS: LogLevel[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'];
export const DEFAULT_LEVELS: LogLevelFilter = {
  TRACE: false,
  DEBUG: false,
  INFO: true,
  WARN: true,
  ERROR: true,
};

export const INITIAL_PAGE_SIZE = 300;
export const OLDER_PAGE_SIZE = 250;
export const MAX_IN_MEMORY = 20000;
export const MAX_BUFFERED_WHILE_PAUSED = 5000;
export const LOAD_MORE_THRESHOLD_PX = 80;
export const AUTO_SCROLL_THRESHOLD_PX = 120;
export const ROW_HEIGHT_PX = 28;
export const ROW_OVERSCAN = 30;
export const RECONNECT_DELAY_MS = 1200;
