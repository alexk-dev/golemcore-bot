import client from './client';

export interface FileTreeNode {
  path: string;
  name: string;
  type: 'file' | 'directory';
  size?: number;
  mimeType?: string | null;
  updatedAt?: string | null;
  binary: boolean;
  image: boolean;
  editable: boolean;
  hasChildren: boolean;
  children: FileTreeNode[];
}

export interface FileContent {
  path: string;
  content: string | null;
  size: number;
  updatedAt: string;
  mimeType: string | null;
  binary: boolean;
  image: boolean;
  editable: boolean;
  downloadUrl: string | null;
}

export interface FileRenameResponse {
  sourcePath: string;
  targetPath: string;
}

export interface DownloadedFile {
  objectUrl: string;
  revoke: () => void;
}

export interface GetFileTreeOptions {
  depth?: number;
  includeIgnored?: boolean;
}

export async function getFileTree(path: string, options?: GetFileTreeOptions): Promise<FileTreeNode[]> {
  const { data } = await client.get<FileTreeNode[]>('/files/tree', {
    params: {
      path,
      depth: options?.depth,
      includeIgnored: options?.includeIgnored,
    },
  });
  return data;
}

export async function getFileContent(path: string): Promise<FileContent> {
  const { data } = await client.get<FileContent>('/files/content', { params: { path } });
  return data;
}

export async function createFileContent(path: string, content: string): Promise<FileContent> {
  const { data } = await client.post<FileContent>('/files/content', { path, content });
  return data;
}

export async function saveFileContent(path: string, content: string): Promise<FileContent> {
  const { data } = await client.put<FileContent>('/files/content', { path, content });
  return data;
}

export async function uploadFileContent(path: string, file: File): Promise<FileContent> {
  const formData = new FormData();
  formData.append('file', file);
  const { data } = await client.post<FileContent>('/files/upload', formData, {
    params: { path },
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
}

export async function renameFilePath(sourcePath: string, targetPath: string): Promise<FileRenameResponse> {
  const { data } = await client.post<FileRenameResponse>('/files/rename', { sourcePath, targetPath });
  return data;
}

export async function deleteFilePath(path: string): Promise<void> {
  await client.delete('/files', { params: { path } });
}

export async function fetchProtectedFileObjectUrl(path: string): Promise<DownloadedFile> {
  const response = await client.get<Blob>('/files/download', {
    params: { path },
    responseType: 'blob',
  });
  const blob = response.data;
  const objectUrl = URL.createObjectURL(blob);
  return {
    objectUrl,
    revoke: () => URL.revokeObjectURL(objectUrl),
  };
}
