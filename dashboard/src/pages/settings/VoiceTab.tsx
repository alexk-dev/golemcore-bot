import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import { SecretStatusBadges } from '../../components/common/SecretStatusBadges';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { getSecretInputType, getSecretPlaceholder, getSecretToggleLabel } from '../../components/common/secretInputUtils';
import type { VoiceConfig } from '../../api/settings';
import { useUpdateVoice } from '../../hooks/useSettings';
import { STT_PROVIDER_WHISPER, TTS_PROVIDER_ELEVENLABS, resolveSttProvider, resolveTtsProvider } from './voiceProviders';

interface VoiceTabProps {
  config: VoiceConfig;
}

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

function normalizeVoiceSpeed(speed: number | null): number {
  return speed ?? 1.0;
}

function formatVoiceSpeed(speed: number | null): string {
  return normalizeVoiceSpeed(speed).toFixed(1);
}

export default function VoiceTab({ config }: VoiceTabProps): ReactElement {
  const updateVoice = useUpdateVoice();
  const [form, setForm] = useState<VoiceConfig>({ ...config });
  const [showKey, setShowKey] = useState(false);
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const hasStoredApiKey = config.apiKeyPresent === true;
  const willUpdateApiKey = (form.apiKey?.length ?? 0) > 0;
  const speedLabel = formatVoiceSpeed(form.speed);
  const speedValue = normalizeVoiceSpeed(form.speed);
  const sttProvider = resolveSttProvider(form.sttProvider);
  const ttsProvider = resolveTtsProvider(form.ttsProvider);
  const isWhisperStt = sttProvider === STT_PROVIDER_WHISPER;
  const isElevenLabsStt = !isWhisperStt;
  const isElevenLabsTts = ttsProvider === TTS_PROVIDER_ELEVENLABS;

  // Keep local draft in sync after server-side updates/refetch.
  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateVoice.mutateAsync({
      ...form,
      sttProvider: resolveSttProvider(form.sttProvider),
      ttsProvider: resolveTtsProvider(form.ttsProvider),
    });
    toast.success('ElevenLabs settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="ElevenLabs" />
        <div className="d-flex align-items-center gap-2 mb-3">
          <Badge bg={isElevenLabsStt ? 'primary' : 'secondary'}>
            STT: {isElevenLabsStt ? 'Active' : 'Inactive'}
          </Badge>
          <Badge bg={isElevenLabsTts ? 'primary' : 'secondary'}>
            TTS: {isElevenLabsTts ? 'Active' : 'Inactive'}
          </Badge>
        </div>

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium d-flex align-items-center gap-2">
            ElevenLabs API Key <HelpTip text="Used for TTS and for STT when STT provider is set to ElevenLabs" />
            <SecretStatusBadges hasStoredSecret={hasStoredApiKey} willUpdateSecret={willUpdateApiKey} />
          </Form.Label>
          <InputGroup size="sm">
            <Form.Control
              type={getSecretInputType(showKey)}
              autoComplete="new-password"
              value={form.apiKey ?? ''}
              onChange={(event) => setForm((prev) => ({ ...prev, apiKey: toNullableString(event.target.value) }))}
              placeholder={getSecretPlaceholder(hasStoredApiKey, 'Enter API key')}
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
            />
            <Button type="button" variant="secondary" onClick={() => setShowKey(!showKey)}>
              {getSecretToggleLabel(showKey)}
            </Button>
          </InputGroup>
        </Form.Group>

        <Row className="g-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Voice ID <HelpTip text="ElevenLabs voice identifier. Find voices at elevenlabs.io/voice-library" />
              </Form.Label>
              <Form.Control
                size="sm"
                value={form.voiceId ?? ''}
                onChange={(event) => setForm((prev) => ({ ...prev, voiceId: toNullableString(event.target.value) }))}
              />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Speed: {speedLabel}
                <HelpTip text="Voice playback speed multiplier (0.5 = half speed, 2.0 = double speed)" />
              </Form.Label>
              <Form.Range
                min={0.5}
                max={2.0}
                step={0.1}
                value={speedValue}
                onChange={(event) => setForm((prev) => ({ ...prev, speed: parseFloat(event.target.value) }))}
              />
            </Form.Group>
          </Col>
        </Row>

        <Row className="g-3 mt-1">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                TTS Model <HelpTip text="ElevenLabs text-to-speech model. eleven_multilingual_v2 supports 29 languages." />
              </Form.Label>
              <Form.Control
                size="sm"
                value={form.ttsModelId ?? ''}
                onChange={(event) => setForm((prev) => ({ ...prev, ttsModelId: toNullableString(event.target.value) }))}
                placeholder="eleven_multilingual_v2"
              />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                STT Model <HelpTip text="Used only when STT provider is ElevenLabs" />
              </Form.Label>
              <Form.Control
                size="sm"
                value={form.sttModelId ?? ''}
                disabled={isWhisperStt}
                onChange={(event) => setForm((prev) => ({ ...prev, sttModelId: toNullableString(event.target.value) }))}
                placeholder="scribe_v1"
              />
            </Form.Group>
          </Col>
        </Row>

        {isWhisperStt && (
          <Form.Text className="text-muted d-block mt-2">
            STT provider is set to Whisper in Tools -&gt; Voice, so ElevenLabs STT model is currently ignored.
          </Form.Text>
        )}

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
