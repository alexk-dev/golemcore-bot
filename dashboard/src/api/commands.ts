import client from './client';

export interface CommandSpec {
  name: string;
  description: string;
  usage: string;
}

export async function getCommands(): Promise<CommandSpec[]> {
  const { data } = await client.get('/commands');
  return data;
}
