import client from './client';

export interface ImageUploadResponse {
  id: string;
  url: string;
  mimeType: string;
  size: number;
  width?: number | null;
  height?: number | null;
}

export async function uploadImages(files: File[]): Promise<ImageUploadResponse[]> {
  const form = new FormData();
  files.forEach((f) => form.append('files', f));
  const { data } = await client.post('/uploads/images', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
}
