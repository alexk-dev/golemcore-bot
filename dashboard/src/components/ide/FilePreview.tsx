import type { ReactElement } from 'react';
import { FiDownload, FiFile, FiImage } from 'react-icons/fi';
import type { FileContent } from '../../api/files';
import { useProtectedFileObjectUrl } from '../../hooks/useProtectedFileObjectUrl';
import { useProtectedFileDownload } from '../../hooks/useProtectedFileDownload';
import { Button } from '../ui/button';

export interface FilePreviewProps {
  file: FileContent;
}

function formatFileSize(fileSizeBytes: number): string {
  if (fileSizeBytes < 1024) {
    return `${fileSizeBytes} B`;
  }
  if (fileSizeBytes < 1024 * 1024) {
    return `${(fileSizeBytes / 1024).toFixed(1)} KB`;
  }
  return `${(fileSizeBytes / (1024 * 1024)).toFixed(2)} MB`;
}

export function FilePreview({ file }: FilePreviewProps): ReactElement {
  const preview = useProtectedFileObjectUrl(file.path);
  const download = useProtectedFileDownload();
  const handleDownload = (): void => {
    void download.downloadFile(file.path);
  };

  if (file.image) {
    return (
      <div className="ide-file-preview flex h-full min-h-0 flex-col items-center justify-center gap-4 p-6">
        <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <FiImage size={16} />
          Image preview
        </div>
        {preview.objectUrl != null ? (
          <img className="ide-file-preview-image" src={preview.objectUrl} alt={file.path} />
        ) : (
          <div className="rounded-2xl border border-border/80 bg-muted/30 px-4 py-3 text-sm text-muted-foreground">
            {preview.error ? 'Failed to load preview.' : 'Loading preview...'}
          </div>
        )}
        <div className="text-center text-xs text-muted-foreground">
          <div>{file.path}</div>
          <div>{file.mimeType ?? 'image'} · {formatFileSize(file.size)}</div>
        </div>
        <Button size="sm" variant="secondary" onClick={handleDownload} disabled={download.isDownloading}>
          <FiDownload size={14} />
          Download
        </Button>
      </div>
    );
  }

  return (
    <div className="ide-file-preview flex h-full flex-col items-center justify-center gap-4 p-6 text-center">
      <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <FiFile size={16} />
        Binary file
      </div>
      <div className="max-w-md text-sm text-muted-foreground">
        This file cannot be edited inline. Download it to inspect or modify it with a local tool.
      </div>
      <div className="text-xs text-muted-foreground">
        <div>{file.path}</div>
        <div>{file.mimeType ?? 'application/octet-stream'} · {formatFileSize(file.size)}</div>
      </div>
      <Button size="sm" variant="secondary" onClick={handleDownload} disabled={download.isDownloading}>
        <FiDownload size={14} />
        Download
      </Button>
    </div>
  );
}
