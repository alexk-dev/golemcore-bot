import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ChatInput from './ChatInput';

vi.mock('../../hooks/useCommands', () => ({
  useCommands: () => ({
    data: [
      { name: 'plan', description: 'Plan mode', usage: '/plan <on|off|status>' },
      { name: 'help', description: 'Help', usage: '/help' },
    ],
  }),
}));

vi.mock('../../api/uploads', () => ({
  uploadImages: vi.fn(async () => [{ id: 'img1', url: '/api/uploads/images/img1', mimeType: 'image/png', size: 100 }]),
}));

vi.mock('../../api/voice', () => ({
  transcribeVoice: vi.fn(async () => ({ text: 'hello from voice', language: 'en', confidence: 0.99 })),
}));

describe('ChatInput', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('shows warning for missing required command args', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByPlaceholderText(/Type a message/i);
    fireEvent.change(textarea, { target: { value: '/plan' } });

    expect(await screen.findByText(/Missing args:/i)).toBeInTheDocument();
  });

  it('supports command autocomplete via Tab', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByPlaceholderText(/Type a message/i);
    fireEvent.change(textarea, { target: { value: '/pl' } });
    fireEvent.keyDown(textarea, { key: 'Tab' });

    await waitFor(() => {
      expect((textarea as HTMLTextAreaElement).value).toBe('/plan ');
    });
  });

  it('accepts selected command on Enter when autocomplete is visible', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByPlaceholderText(/Type a message/i);
    fireEvent.change(textarea, { target: { value: '/pl' } });
    fireEvent.keyDown(textarea, { key: 'Enter' });

    await waitFor(() => {
      expect((textarea as HTMLTextAreaElement).value).toBe('/plan ');
    });
    expect(onSend).not.toHaveBeenCalled();
  });

  it('submits user message on Enter', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByPlaceholderText(/Type a message/i);
    fireEvent.change(textarea, { target: { value: 'hello' } });
    fireEvent.keyDown(textarea, { key: 'Enter' });

    await waitFor(() => {
      expect(onSend).toHaveBeenCalledWith('hello');
    });
  });

  it('shows file type validation error for non-image upload', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['abc'], 'test.txt', { type: 'text/plain' });

    fireEvent.change(fileInput, {
      target: { files: [file] },
    });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/Only image files are supported/i);
  });

  it('sets combobox aria attributes for command suggestions', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByRole('combobox');
    fireEvent.change(textarea, { target: { value: '/pl' } });

    expect(textarea).toHaveAttribute('aria-controls', 'chat-command-listbox');
    expect(textarea).toHaveAttribute('aria-expanded', 'true');
    expect(await screen.findByRole('listbox')).toBeInTheDocument();
  });
});
