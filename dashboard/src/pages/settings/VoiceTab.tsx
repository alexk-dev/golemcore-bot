import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, InputGroup, OverlayTrigger, Row, Tooltip } from 'react-bootstrap';
import { FiHelpCircle } from 'react-icons/fi';
import toast from 'react-hot-toast';
import { useUpdateVoice } from '../../hooks/useSettings';
import type { VoiceConfig } from '../../api/settings';

function Tip({ text }: { text: string }): ReactElement {
  return (
    <OverlayTrigger placement="top" overlay={<Tooltip>{text}</Tooltip>}>
      <span className="setting-tip"><FiHelpCircle /></span>
    </OverlayTrigger>
  );
}

function SaveStateHint({ isDirty }: { isDirty: boolean }): ReactElement {
  return <small className="text-body-secondary">{isDirty ? 'Unsaved changes' : 'All changes saved'}</small>;
}

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
        <Card.Title className="h6 mb-3">Voice (ElevenLabs)</Card.Title>
        <Form.Check type="switch" label={<>Enable Voice <Tip text="Enable speech-to-text and text-to-speech via ElevenLabs API" /></>}
          checked={form.enabled ?? false}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })} className="mb-3" />

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            API Key <Tip text="Your ElevenLabs API key from elevenlabs.io/app/settings/api-keys" />
          </Form.Label>
          <InputGroup size="sm">
            <Form.Control type={showKey ? 'text' : 'password'} value={form.apiKey ?? ''}
              onChange={(e) => setForm({ ...form, apiKey: toNullableString(e.target.value) })} />
            <Button variant="secondary" onClick={() => setShowKey(!showKey)}>
              {showKey ? 'Hide' : 'Show'}
            </Button>
          </InputGroup>
        </Form.Group>

        <Row className="g-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Voice ID <Tip text="ElevenLabs voice identifier. Find voices at elevenlabs.io/voice-library" />
              </Form.Label>
              <Form.Control size="sm" value={form.voiceId ?? ''}
                onChange={(e) => setForm({ ...form, voiceId: toNullableString(e.target.value) })} />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Speed: {form.speed?.toFixed(1) ?? '1.0'}
                <Tip text="Voice playback speed multiplier (0.5 = half speed, 2.0 = double speed)" />
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
                TTS Model <Tip text="Text-to-speech model. eleven_multilingual_v2 supports 29 languages." />
              </Form.Label>
              <Form.Control size="sm" value={form.ttsModelId ?? ''}
                onChange={(e) => setForm({ ...form, ttsModelId: toNullableString(e.target.value) })}
                placeholder="eleven_multilingual_v2" />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                STT Model <Tip text="Speech-to-text model for transcribing voice messages." />
              </Form.Label>
              <Form.Control size="sm" value={form.sttModelId ?? ''}
                onChange={(e) => setForm({ ...form, sttModelId: toNullableString(e.target.value) })}
                placeholder="scribe_v1" />
            </Form.Group>
          </Col>
        </Row>

        <div className="mt-3 d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isVoiceDirty || updateVoice.isPending}>
            {updateVoice.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isVoiceDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}
