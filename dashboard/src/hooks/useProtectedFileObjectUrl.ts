import { useEffect, useState } from 'react';
import { fetchProtectedFileObjectUrl, type DownloadedFile } from '../api/files';

export interface ProtectedFileObjectUrlState {
  objectUrl: string | null;
  loading: boolean;
  error: boolean;
}

export function useProtectedFileObjectUrl(path: string | null): ProtectedFileObjectUrlState {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    // Build a bearer-authenticated object URL for protected file preview/download surfaces.
    if (path == null || path.length === 0) {
      setObjectUrl(null);
      setLoading(false);
      setError(false);
      return undefined;
    }

    let cancelled = false;
    let download: DownloadedFile | null = null;
    setLoading(true);
    setError(false);
    setObjectUrl(null);

    void fetchProtectedFileObjectUrl(path)
      .then((result) => {
        if (cancelled) {
          result.revoke();
          return;
        }
        download = result;
        setObjectUrl(result.objectUrl);
      })
      .catch(() => {
        if (!cancelled) {
          setError(true);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
      download?.revoke();
    };
  }, [path]);

  return { objectUrl, loading, error };
}
