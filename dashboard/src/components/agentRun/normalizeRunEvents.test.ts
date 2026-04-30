import { describe, expect, it } from 'vitest';
import { normalizeChatMessages } from './normalizeRunEvents';
import type { ChatMessage } from '../chat/chatRuntimeTypes';

function chat(message: Partial<ChatMessage> & Pick<ChatMessage, 'id' | 'role' | 'content'>): ChatMessage {
  return {
    model: null,
    tier: null,
    skill: null,
    reasoning: null,
    attachments: [],
    persisted: true,
    ...message,
  } as ChatMessage;
}

describe('normalizeChatMessages', () => {
  it('emits user and assistant items in order', () => {
    const out = normalizeChatMessages(
      [
        chat({ id: 'u1', role: 'user', content: 'hello' }),
        chat({ id: 'a1', role: 'assistant', content: 'world' }),
      ],
      'run-1',
    );
    expect(out.map((item) => item.type)).toEqual(['user_message', 'assistant_message']);
  });

  it('extracts tool calls from assistant markers', () => {
    const out = normalizeChatMessages(
      [
        chat({ id: 'a1', role: 'assistant', content: '[Tool: read_file optimizer.yaml][Result: ok]' }),
      ],
      'run-1',
    );
    expect(out).toHaveLength(2);
    expect(out[1].type).toBe('tool_calls');
    if (out[1].type === 'tool_calls') {
      expect(out[1].calls).toHaveLength(1);
      expect(out[1].calls[0].toolName).toBe('read_file');
      expect(out[1].calls[0].displayTarget).toBe('optimizer.yaml');
      expect(out[1].calls[0].status).toBe('success');
    }
  });

  it('marks tool calls failed when result mentions error', () => {
    const out = normalizeChatMessages(
      [chat({ id: 'a1', role: 'assistant', content: '[Tool: run_command python][Result: exit 1 error]' })],
      'run-1',
    );
    if (out[1].type === 'tool_calls') {
      expect(out[1].calls[0].status).toBe('failed');
    }
  });

  it('omits tool_calls item when no markers present', () => {
    const out = normalizeChatMessages(
      [chat({ id: 'a1', role: 'assistant', content: 'plain assistant reply' })],
      'run-1',
    );
    expect(out).toHaveLength(1);
    expect(out[0].type).toBe('assistant_message');
  });
});
