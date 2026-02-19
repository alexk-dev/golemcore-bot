import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateVoice } from '../../hooks/useSettings';
import type { VoiceConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

interface VoiceTabProps {
  config: VoiceConfig;
}

export default function VoiceTab({ config }: VoiceTabProps): ReactElement {
  const updateVoice = useUpdateVoice();
  const [form, setForm] = useState<VoiceConfig>({ ...config });
  const [showKey, setShowKey] = useState(false);
  const isVoiceDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => { setForm({ ...config }); }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateVoice.mutateAsync(form);
    toast.success('Voice settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Voice (ElevenLabs)" />
        <Form.Check type="switch" label={<>Enable Voice <HelpTip text="Enable speech-to-text and text-to-speech via ElevenLabs API" /></>}
          checked={form.enabled ?? false}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })} className="mb-3" />

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            API Key <HelpTip text="Your ElevenLabs API key from elevenlabs.io/app/settings/api-keys" />
          </Form.Label>
          <InputGroup size="sm">
            <Form.Control type={showKey ? 'text' : 'password'} value={form.apiKey ?? ''}
              onChange={(e) => setForm({ ...form, apiKey: toNullableString(e.target.value) })} />
            <Button type="button" variant="secondary" onClick={() => setShowKey(!showKey)}>
              {showKey ? 'Hide' : 'Show'}
            </Button>
          </InputGroup>
        </Form.Group>

        <Row className="g-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Voice ID <HelpTip text="ElevenLabs voice identifier. Find voices at elevenlabs.io/voice-library" />
              </Form.Label>
              <Form.Control size="sm" value={form.voiceId ?? ''}
                onChange={(e) => setForm({ ...form, voiceId: toNullableString(e.target.value) })} />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Speed: {form.speed?.toFixed(1) ?? '1.0'}
                <HelpTip text="Voice playback speed multiplier (0.5 = half speed, 2.0 = double speed)" />
              </Form.Label>
              <Form.Range min={0.5} max={2.0} step={0.1} value={form.speed ?? 1.0}
                onChange={(e) => setForm({ ...form, speed: parseFloat(e.target.value) })} />
            </Form.Group>
          </Col>
        </Row>

        <Row className="g-3 mt-1">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                TTS Model <HelpTip text="Text-to-speech model. eleven_multilingual_v2 supports 29 languages." />
              </Form.Label>
              <Form.Control size="sm" value={form.ttsModelId ?? ''}
                onChange={(e) => setForm({ ...form, ttsModelId: toNullableString(e.target.value) })}
                placeholder="eleven_multilingual_v2" />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                STT Model <HelpTip text="Speech-to-text model for transcribing voice messages." />
              </Form.Label>
              <Form.Control size="sm" value={form.sttModelId ?? ''}
                onChange={(e) => setForm({ ...form, sttModelId: toNullableString(e.target.value) })}
                placeholder="scribe_v1" />
            </Form.Group>
          </Col>
        </Row>

        <SettingsSaveBar className="mt-3">
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isVoiceDirty || updateVoice.isPending}>
            {updateVoice.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isVoiceDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
