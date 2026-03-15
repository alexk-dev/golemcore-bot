import { describe, expect, it } from 'vitest';
import { buildIdeTabLabels, getFilename } from './ideTabLabels';

describe('ideTabLabels', () => {
  it('keeps single tabs compact', () => {
    const labels = buildIdeTabLabels(['src/App.tsx']);
    expect(labels.get('src/App.tsx')).toEqual({
      path: 'src/App.tsx',
      title: 'App.tsx',
      context: null,
      fullTitle: 'App.tsx',
    });
  });

  it('adds the shortest unique parent suffix when filenames collide', () => {
    const labels = buildIdeTabLabels([
      'src/app/index.ts',
      'src/api/index.ts',
      'index.ts',
    ]);

    expect(labels.get('src/app/index.ts')?.fullTitle).toBe('index.ts · app');
    expect(labels.get('src/api/index.ts')?.fullTitle).toBe('index.ts · api');
    expect(labels.get('index.ts')?.fullTitle).toBe('index.ts · root');
  });

  it('expands to multiple parent segments when one segment is still ambiguous', () => {
    const labels = buildIdeTabLabels([
      'src/web/app/index.ts',
      'src/mobile/app/index.ts',
      'src/api/index.ts',
    ]);

    expect(labels.get('src/web/app/index.ts')?.fullTitle).toBe('index.ts · web/app');
    expect(labels.get('src/mobile/app/index.ts')?.fullTitle).toBe('index.ts · mobile/app');
    expect(labels.get('src/api/index.ts')?.fullTitle).toBe('index.ts · api');
  });

  it('extracts filenames from nested paths', () => {
    expect(getFilename('src/features/ide/EditorTabs.tsx')).toBe('EditorTabs.tsx');
  });
});
