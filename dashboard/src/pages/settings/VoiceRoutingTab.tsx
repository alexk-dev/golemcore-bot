import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import type { VoiceConfig } from '../../api/settings';
import { useUpdateVoice } from '../../hooks/useSettings';
import {
  STT_PROVIDER_ELEVENLABS,
  STT_PROVIDER_WHISPER,
  TTS_PROVIDER_ELEVENLABS,
  resolveSttProvider,
  resolveTtsProvider,
} from './voiceProviders';

interface VoiceRoutingTabProps {
  config: VoiceConfig;
}

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

export default function VoiceRoutingTab({ config }: VoiceRoutingTabProps): ReactElement {
  const updateVoice = useUpdateVoice();
  const [form, setForm] = useState<VoiceConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const sttProvider = resolveSttProvider(form.sttProvider);
  const ttsProvider = resolveTtsProvider(form.ttsProvider);
  const isWhisperStt = sttProvider === STT_PROVIDER_WHISPER;

  // Keep local draft in sync after server-side updates/refetch.
  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateVoice.mutateAsync({
      ...form,
      sttProvider,
      ttsProvider,
    });
    toast.success('Voice routing saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Voice" />
        <Form.Check
          type="switch"
          label={<>Enable Voice <HelpTip text="Enable speech-to-text and text-to-speech pipeline" /></>}
          checked={form.enabled ?? false}
          onChange={(event) => setForm((prev) => ({ ...prev, enabled: event.target.checked }))}
          className="mb-3"
        />

        <Row className="g-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                STT Provider <HelpTip text="Provider used for speech-to-text transcription" />
              </Form.Label>
              <Form.Select
                size="sm"
                value={sttProvider}
                onChange={(event) => setForm((prev) => ({ ...prev, sttProvider: event.target.value }))}
              >
                <option value={STT_PROVIDER_ELEVENLABS}>ElevenLabs</option>
                <option value={STT_PROVIDER_WHISPER}>Whisper-compatible</option>
              </Form.Select>
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                TTS Provider <HelpTip text="Provider used for text-to-speech synthesis" />
              </Form.Label>
              <Form.Select
                size="sm"
                value={ttsProvider}
                onChange={(event) => setForm((prev) => ({ ...prev, ttsProvider: event.target.value }))}
              >
                <option value={TTS_PROVIDER_ELEVENLABS}>ElevenLabs</option>
              </Form.Select>
            </Form.Group>
          </Col>
        </Row>

        <Form.Text className="text-muted d-block mt-3">
          {isWhisperStt
            ? 'Whisper STT is selected. Configure URL and API key in Runtime -> Whisper.'
            : 'ElevenLabs STT is selected. Configure credentials in Runtime -> ElevenLabs.'}
        </Form.Text>

        <SettingsSaveBar className="mt-3">
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => { void handleSave(); }}
            disabled={!isDirty || updateVoice.isPending}
          >
            {updateVoice.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
