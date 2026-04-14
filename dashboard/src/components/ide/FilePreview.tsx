import type { ReactElement } from 'react';
import { FiDownload, FiFile, FiImage } from 'react-icons/fi';
import type { FileContent } from '../../api/files';
import { Button } from '../ui/button';

export interface FilePreviewProps {
  file: FileContent;
  downloadUrl: string;
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

export function FilePreview({ file, downloadUrl }: FilePreviewProps): ReactElement {
  if (file.image) {
    return (
      <div className="ide-file-preview flex h-full min-h-0 flex-col items-center justify-center gap-4 p-6">
        <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <FiImage size={16} />
          Image preview
        </div>
        <img className="ide-file-preview-image" src={downloadUrl} alt={file.path} />
        <div className="text-center text-xs text-muted-foreground">
          <div>{file.path}</div>
          <div>{file.mimeType ?? 'image'} · {formatFileSize(file.size)}</div>
        </div>
        <a href={downloadUrl} download>
          <Button size="sm" variant="secondary">
            <FiDownload size={14} />
            Download
          </Button>
        </a>
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
      <a href={downloadUrl} download>
        <Button size="sm" variant="secondary">
          <FiDownload size={14} />
          Download
        </Button>
      </a>
    </div>
  );
}
