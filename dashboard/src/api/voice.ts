import client from './client';

export interface TranscriptionResponse {
  text: string;
  language: string;
  confidence: number;
}

export async function transcribeVoice(file: Blob): Promise<TranscriptionResponse> {
  const form = new FormData();
  form.append('file', file, 'recording.webm');
  const { data } = await client.post('/voice/transcribe', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
}
