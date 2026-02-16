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

    const textarea = screen.getByPlaceholderText(/Type a message/i);
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

  it('renders command suggestion list with listbox semantics', async () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    const textarea = screen.getByPlaceholderText(/Type a message/i);
    fireEvent.change(textarea, { target: { value: '/pl' } });

    expect(await screen.findByRole('listbox')).toBeInTheDocument();
  });

  it('shows transcript confirmation and inserts text only after explicit confirm', async () => {
    class MockMediaRecorder {
      ondataavailable: ((ev: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      mimeType = 'audio/webm';
      constructor(_stream: MediaStream) {}
      start() {}
      stop() {
        this.ondataavailable?.({ data: new Blob(['audio'], { type: 'audio/webm' }) });
        this.onstop?.();
      }
    }

    vi.stubGlobal('MediaRecorder', MockMediaRecorder as unknown as typeof MediaRecorder);
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: vi.fn() }] })),
      },
    });

    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    fireEvent.click(screen.getByLabelText(/Start voice recording/i));
    const stopBtn = await screen.findByLabelText(/Stop voice recording/i);
    fireEvent.click(stopBtn);

    expect(await screen.findByTestId('transcript-confirm')).toBeInTheDocument();
    const textarea = screen.getByPlaceholderText(/Type a message/i) as HTMLTextAreaElement;
    expect(textarea.value).toBe('');

    fireEvent.click(screen.getByRole('button', { name: /Insert transcript/i }));

    await waitFor(() => {
      expect((screen.getByPlaceholderText(/Type a message/i) as HTMLTextAreaElement).value).toContain('hello from voice');
    });
  });

  it('discards transcript confirmation without mutating text', async () => {
    class MockMediaRecorder {
      ondataavailable: ((ev: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      mimeType = 'audio/webm';
      constructor(_stream: MediaStream) {}
      start() {}
      stop() {
        this.ondataavailable?.({ data: new Blob(['audio'], { type: 'audio/webm' }) });
        this.onstop?.();
      }
    }

    vi.stubGlobal('MediaRecorder', MockMediaRecorder as unknown as typeof MediaRecorder);
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: vi.fn() }] })),
      },
    });

    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    fireEvent.click(screen.getByLabelText(/Start voice recording/i));
    const stopBtn = await screen.findByLabelText(/Stop voice recording/i);
    fireEvent.click(stopBtn);

    expect(await screen.findByTestId('transcript-confirm')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Discard transcript/i }));

    await waitFor(() => {
      expect(screen.queryByTestId('transcript-confirm')).not.toBeInTheDocument();
    });

    expect((screen.getByPlaceholderText(/Type a message/i) as HTMLTextAreaElement).value).toBe('');
  });

  it('shows recoverable notice when microphone permission is denied', async () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: vi.fn(async () => {
          const err = new DOMException('Permission denied', 'NotAllowedError');
          throw err;
        }),
      },
    });

    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    fireEvent.click(screen.getByLabelText(/Start voice recording/i));

    const notice = await screen.findByTestId('composer-notice-recoverable');
    expect(notice).toHaveTextContent(/Microphone access denied/i);
  });


  it('cancel recording does not show transcript confirmation', async () => {
    class MockMediaRecorder {
      ondataavailable: ((ev: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      mimeType = 'audio/webm';
      constructor(_stream: MediaStream) {}
      start() {}
      stop() {
        this.ondataavailable?.({ data: new Blob(['audio'], { type: 'audio/webm' }) });
        this.onstop?.();
      }
    }

    vi.stubGlobal('MediaRecorder', MockMediaRecorder as unknown as typeof MediaRecorder);
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: vi.fn() }] })),
      },
    });

    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);

    fireEvent.click(screen.getByLabelText(/Start voice recording/i));
    const cancelBtn = await screen.findByLabelText(/Cancel voice recording/i);
    fireEvent.click(cancelBtn);

    await waitFor(() => {
      expect(screen.queryByTestId('transcript-confirm')).not.toBeInTheDocument();
    });
  });
});
