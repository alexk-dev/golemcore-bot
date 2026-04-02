import type { SystemChannelResponse } from '../api/system';

export interface NormalizedChannel {
  type: string;
  running: boolean;
}

/**
 * Deduplicates, normalizes, and sorts system channels.
 * Filters out channels with empty types and any types in the exclude set.
 * Telegram is always sorted first.
 */
export function filterAndSortChannels(
  channels: SystemChannelResponse[],
  excludeTypes: ReadonlySet<string>,
): NormalizedChannel[] {
  const seen = new Set<string>();
  return channels
    .map((channel) => ({ ...channel, type: channel.type.trim().toLowerCase() }))
    .filter((channel) => channel.type.length > 0 && !excludeTypes.has(channel.type))
    .filter((channel) => {
      if (seen.has(channel.type)) {
        return false;
      }
      seen.add(channel.type);
      return true;
    })
    .sort((left, right) => {
      if (left.type === 'telegram') {
        return -1;
      }
      if (right.type === 'telegram') {
        return 1;
      }
      return left.type.localeCompare(right.type);
    });
}

/**
 * Resolves the linked Telegram user ID from runtime config allowed users.
 * Returns the first non-empty user ID, or null.
 */
export function resolveLinkedTelegramUserId(allowedUsers: string[] | undefined | null): string | null {
  const userId = (allowedUsers ?? []).find((value) => value.trim().length > 0);
  return userId ?? null;
}
