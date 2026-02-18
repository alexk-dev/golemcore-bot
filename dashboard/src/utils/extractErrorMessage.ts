/**
 * Extract a human-readable error message from an unknown error.
 * Checks response.data.message first (matches ApiErrorResponse),
 * then Error.message, then falls back to "Unknown error".
 */
export function extractErrorMessage(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const maybeResponse = (error as { response?: { data?: unknown } }).response;
    const data = maybeResponse?.data;
    if (typeof data === 'object' && data !== null && 'message' in data) {
      const message = (data as { message?: unknown }).message;
      if (typeof message === 'string' && message.trim().length > 0) {
        return message;
      }
    }
  }

  if (error instanceof Error && error.message.trim().length > 0) {
    return error.message;
  }

  return 'Unknown error';
}
