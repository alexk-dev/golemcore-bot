import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { FileContent } from '../../api/files';
import { FilePreview } from './FilePreview';

describe('FilePreview', () => {
  it('renders image files as previews with download action', () => {
    const file: FileContent = {
      path: 'images/logo.png',
      content: null,
      size: 120,
      updatedAt: '2026-04-14T00:00:00Z',
      mimeType: 'image/png',
      binary: true,
      image: true,
      editable: false,
      downloadUrl: '/api/files/download?path=images%2Flogo.png',
    };

    const html = renderToStaticMarkup(<FilePreview file={file} downloadUrl="/download/logo.png" />);

    expect(html).toContain('Image preview');
    expect(html).toContain('src="/download/logo.png"');
    expect(html).toContain('Download');
  });

  it('renders binary files as non-editable download cards', () => {
    const file: FileContent = {
      path: 'archives/app.zip',
      content: null,
      size: 4096,
      updatedAt: '2026-04-14T00:00:00Z',
      mimeType: 'application/zip',
      binary: true,
      image: false,
      editable: false,
      downloadUrl: '/api/files/download?path=archives%2Fapp.zip',
    };

    const html = renderToStaticMarkup(<FilePreview file={file} downloadUrl="/download/app.zip" />);

    expect(html).toContain('Binary file');
    expect(html).toContain('This file cannot be edited inline.');
    expect(html).toContain('Download');
  });
});
