import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ChatInput from './ChatInput';
import { uploadImages } from '../../api/uploads';

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

  it('shows advisory notice for missing required command args', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByPlaceholderText(/Type a message/i);
    fireEvent.change(textarea, { target: { value: '/plan' } });

    const advisory = await screen.findByTestId('composer-notice-advisory');
    expect(advisory).toHaveTextContent(/Missing args:/i);
  });

  it('shows command mode indicator for slash command input', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByPlaceholderText(/Type a message/i);
    fireEvent.change(textarea, { target: { value: '/pl' } });

    expect(await screen.findByText(/Command mode/i)).toBeInTheDocument();
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

  it('does not submit unknown slash token without args on Enter', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByPlaceholderText(/Type a message/i);
    fireEvent.change(textarea, { target: { value: '/unknown' } });
    fireEvent.keyDown(textarea, { key: 'Enter' });

    await waitFor(() => {
      expect(onSend).not.toHaveBeenCalled();
    });
  });

  it('dismisses autocomplete on Escape', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByRole('combobox');
    fireEvent.change(textarea, { target: { value: '/pl' } });
    expect(await screen.findByRole('listbox')).toBeInTheDocument();

    fireEvent.keyDown(textarea, { key: 'Escape' });

    await waitFor(() => {
      expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    });
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

  it('shows file type validation error for non-image upload as recoverable notice', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['abc'], 'test.txt', { type: 'text/plain' });

    fireEvent.change(fileInput, {
      target: { files: [file] },
    });

    const alert = await screen.findByTestId('composer-notice-recoverable');
    expect(alert).toHaveTextContent(/Only image files are supported/i);
    expect(alert).toHaveTextContent(/Please adjust input and try again/i);
  });

  it('marks failed upload and allows retry', async () => {
    const mockedUpload = vi.mocked(uploadImages);
    mockedUpload
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce([{ id: 'img2', url: '/api/uploads/images/img2', mimeType: 'image/png', size: 100 }]);

    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    const image = new File(['img'], 'a.png', { type: 'image/png' });

    fireEvent.change(fileInput, { target: { files: [image] } });

    expect(await screen.findByLabelText(/Retry attachment/i)).toBeInTheDocument();

    const retryBtn = screen.getByLabelText(/Retry attachment/i);
    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(screen.queryByLabelText(/Retry attachment/i)).not.toBeInTheDocument();
    });
  });

  it('sets combobox aria attributes for command suggestions', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByRole('combobox');
    fireEvent.change(textarea, { target: { value: '/pl' } });

    expect(textarea).toHaveAttribute('aria-controls', 'chat-command-listbox');
    expect(textarea).toHaveAttribute('aria-expanded', 'true');
    expect(textarea).toHaveAttribute('aria-haspopup', 'listbox');
    expect(await screen.findByRole('listbox')).toBeInTheDocument();
  });
});
