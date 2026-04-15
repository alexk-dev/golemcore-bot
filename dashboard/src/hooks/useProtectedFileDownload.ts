import { useCallback, useState } from 'react';
import { fetchProtectedFileObjectUrl } from '../api/files';

export interface ProtectedFileDownloadState {
  isDownloading: boolean;
  downloadFile: (path: string, filename?: string | null) => Promise<void>;
}

function resolveDownloadName(path: string, filename?: string | null): string {
  if (filename != null && filename.length > 0) {
    return filename;
  }
  const segments = path.split('/').filter((segment) => segment.length > 0);
  return segments[segments.length - 1] ?? 'download';
}

export function useProtectedFileDownload(): ProtectedFileDownloadState {
  const [isDownloading, setDownloading] = useState(false);

  const downloadFile = useCallback(async (path: string, filename?: string | null): Promise<void> => {
    setDownloading(true);
    const download = await fetchProtectedFileObjectUrl(path);
    try {
      const link = document.createElement('a');
      link.href = download.objectUrl;
      link.download = resolveDownloadName(path, filename);
      link.rel = 'noreferrer';
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } finally {
      window.setTimeout(() => download.revoke(), 60_000);
      setDownloading(false);
    }
  }, []);

  return { isDownloading, downloadFile };
}
