import client from './client';

const CHAT_CLIENT_INSTANCE_STORAGE_KEY = 'golem-chat-client-instance-id';

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

export interface InlineEditRequest {
  path: string;
  content: string;
  selectionFrom: number;
  selectionTo: number;
  selectedText: string;
  instruction: string;
}

export interface InlineEditResponse {
  path: string;
  replacement: string;
}

function readChatClientInstanceId(): string | null {
  if (typeof window === 'undefined') {
    return null;
  }
  const value = window.localStorage.getItem(CHAT_CLIENT_INSTANCE_STORAGE_KEY);
  return value != null && value.length > 0 ? value : null;
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

export async function createInlineEdit(request: InlineEditRequest): Promise<InlineEditResponse> {
  const clientInstanceId = readChatClientInstanceId();
  const { data } = await client.post<InlineEditResponse>('/files/inline-edit', request, {
    headers: clientInstanceId != null ? { 'X-Golem-Client-Instance-Id': clientInstanceId } : undefined,
  });
  return data;
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
