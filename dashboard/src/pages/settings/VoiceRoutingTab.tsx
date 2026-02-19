import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import { useNavigate } from 'react-router-dom';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import type { VoiceConfig } from '../../api/settings';
import { useUpdateVoice } from '../../hooks/useSettings';
import {
  STT_PROVIDER_ELEVENLABS,
  STT_PROVIDER_WHISPER,
  TTS_PROVIDER_ELEVENLABS,
  isValidHttpUrl,
  resolveSttProvider,
  resolveTtsProvider,
} from './voiceProviders';

interface VoiceRoutingTabProps {
  config: VoiceConfig;
}

interface WhisperRoutingValidation {
  hasError: boolean;
  message: string;
}

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function buildWhisperRoutingValidation(
  isWhisperStt: boolean,
  whisperSttUrl: string | null | undefined,
): WhisperRoutingValidation {
  if (!isWhisperStt) {
    return { hasError: false, message: '' };
  }
  if (whisperSttUrl == null || whisperSttUrl.trim().length === 0) {
    return {
      hasError: true,
      message: 'Whisper STT URL is required before you can save this provider selection.',
    };
  }
  if (!isValidHttpUrl(whisperSttUrl)) {
    return {
      hasError: true,
      message: 'Whisper STT URL must be a valid http(s) URL.',
    };
  }
  return { hasError: false, message: '' };
}

function getSttStatusLabel(isWhisperStt: boolean): string {
  return isWhisperStt ? 'Whisper' : 'ElevenLabs';
}

function getSttStatusVariant(isWhisperStt: boolean): 'primary' | 'secondary' {
  return isWhisperStt ? 'secondary' : 'primary';
}

function getSttHintText(isWhisperStt: boolean): string {
  return isWhisperStt
    ? 'Whisper STT is selected. Configure URL and API key in Voice: Whisper STT.'
    : 'ElevenLabs STT is selected. Configure credentials in Voice: ElevenLabs.';
}

export default function VoiceRoutingTab({ config }: VoiceRoutingTabProps): ReactElement {
  const navigate = useNavigate();
  const updateVoice = useUpdateVoice();
  const [form, setForm] = useState<VoiceConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const sttProvider = resolveSttProvider(form.sttProvider);
  const ttsProvider = resolveTtsProvider(form.ttsProvider);
  const isWhisperStt = sttProvider === STT_PROVIDER_WHISPER;
  const whisperValidation = buildWhisperRoutingValidation(isWhisperStt, form.whisperSttUrl);
  const saveDisabled = !isDirty || updateVoice.isPending || whisperValidation.hasError;

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
        <SettingsCardTitle title="Voice Routing" />
        <Form.Text className="text-muted d-block mb-3">
          Step 1: choose routing for STT/TTS. Step 2: open provider cards to configure credentials and endpoint details.
        </Form.Text>
        <div className="d-flex align-items-center gap-2 mb-3">
          <Badge bg={getSttStatusVariant(isWhisperStt)}>
            STT: {getSttStatusLabel(isWhisperStt)}
          </Badge>
          <Badge bg="primary">TTS: ElevenLabs</Badge>
        </div>
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
                disabled
              >
                <option value={TTS_PROVIDER_ELEVENLABS}>ElevenLabs</option>
              </Form.Select>
              <Form.Text className="text-muted">Whisper TTS is not supported yet.</Form.Text>
            </Form.Group>
          </Col>
        </Row>

        <Form.Text className="text-muted d-block mt-3">
          {getSttHintText(isWhisperStt)}
        </Form.Text>
        {whisperValidation.hasError && (
          <Form.Text className="text-danger d-block mt-2">
            {whisperValidation.message}
          </Form.Text>
        )}

        <div className="d-flex flex-wrap gap-2 mt-3">
          <Button
            type="button"
            size="sm"
            variant={isWhisperStt ? 'primary' : 'secondary'}
            onClick={() => navigate('/settings/voice-whisper')}
          >
            Open Voice: Whisper STT
          </Button>
          <Button
            type="button"
            size="sm"
            variant={!isWhisperStt ? 'primary' : 'secondary'}
            onClick={() => navigate('/settings/voice-elevenlabs')}
          >
            Open Voice: ElevenLabs
          </Button>
        </div>

        <SettingsSaveBar className="mt-3">
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => { void handleSave(); }}
            disabled={saveDisabled}
          >
            {updateVoice.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
