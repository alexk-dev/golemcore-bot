import client from './client';

export interface FileTreeNode {
  path: string;
  name: string;
  type: 'file' | 'directory';
  size?: number;
  children: FileTreeNode[];
}

export interface FileContent {
  path: string;
  content: string;
  size: number;
  updatedAt: string;
}

export async function getFileTree(path: string): Promise<FileTreeNode[]> {
  const { data } = await client.get<FileTreeNode[]>('/files/tree', { params: { path } });
  return data;
}

export async function getFileContent(path: string): Promise<FileContent> {
  const { data } = await client.get<FileContent>('/files/content', { params: { path } });
  return data;
}

export async function saveFileContent(path: string, content: string): Promise<FileContent> {
  const { data } = await client.put<FileContent>('/files/content', { path, content });
  return data;
}
