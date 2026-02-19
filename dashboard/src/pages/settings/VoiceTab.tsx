import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import { SecretStatusBadges } from '../../components/common/SecretStatusBadges';
import { getSecretInputType, getSecretPlaceholder, getSecretToggleLabel } from '../../components/common/secretInputUtils';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateVoice } from '../../hooks/useSettings';
import type { VoiceConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

const STT_PROVIDER_ELEVENLABS = 'elevenlabs';
const STT_PROVIDER_WHISPER = 'whisper';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

function getSaveButtonLabel(isPending: boolean): string {
  return isPending ? 'Saving...' : 'Save';
}

function normalizeVoiceSpeed(speed: number | null): number {
  return speed ?? 1.0;
}

function formatVoiceSpeed(speed: number | null): string {
  return normalizeVoiceSpeed(speed).toFixed(1);
}

function getSttProvider(value: string | null): string {
  return value ?? STT_PROVIDER_ELEVENLABS;
}

interface SecretFieldProps {
  label: string;
  helpText: string;
  value: string | null;
  hasStoredSecret: boolean;
  willUpdateSecret: boolean;
  showSecret: boolean;
  placeholder: string;
  onToggle: () => void;
  onChange: (value: string | null) => void;
}

function SecretField({ label, helpText, value, hasStoredSecret, willUpdateSecret, showSecret, placeholder, onToggle, onChange }: SecretFieldProps): ReactElement {
  return (
    <Form.Group className="mb-3">
      <Form.Label className="small fw-medium d-flex align-items-center gap-2">
        {label} <HelpTip text={helpText} />
        <SecretStatusBadges hasStoredSecret={hasStoredSecret} willUpdateSecret={willUpdateSecret} />
      </Form.Label>
      <InputGroup size="sm">
        <Form.Control
          type={getSecretInputType(showSecret)}
          value={value ?? ''}
          onChange={(e) => onChange(toNullableString(e.target.value))}
          placeholder={getSecretPlaceholder(hasStoredSecret, placeholder)}
          autoComplete="new-password"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
        />
        <Button type="button" variant="secondary" onClick={onToggle}>
          {getSecretToggleLabel(showSecret)}
        </Button>
      </InputGroup>
    </Form.Group>
  );
}

interface SttSectionProps {
  form: VoiceConfig;
  config: VoiceConfig;
  showElevenLabsKey: boolean;
  showWhisperKey: boolean;
  onFormChange: (patch: Partial<VoiceConfig>) => void;
  onToggleElevenLabsKey: () => void;
  onToggleWhisperKey: () => void;
}

function SttSection({ form, config, showElevenLabsKey, showWhisperKey, onFormChange, onToggleElevenLabsKey, onToggleWhisperKey }: SttSectionProps): ReactElement {
  const sttProvider = getSttProvider(form.sttProvider);
  const isElevenLabsStt = sttProvider === STT_PROVIDER_ELEVENLABS;
  const isWhisperStt = sttProvider === STT_PROVIDER_WHISPER;

  return (
    <>
      <h6 className="fw-semibold mt-3 mb-2">Speech-to-Text (STT)</h6>

      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          STT Provider <HelpTip text="Choose which service handles speech-to-text transcription" />
        </Form.Label>
        <Form.Select size="sm" value={sttProvider}
          onChange={(e) => onFormChange({ sttProvider: e.target.value })}>
          <option value={STT_PROVIDER_ELEVENLABS}>ElevenLabs</option>
          <option value={STT_PROVIDER_WHISPER}>Whisper-compatible</option>
        </Form.Select>
      </Form.Group>

      {isElevenLabsStt && (
        <>
          <SecretField
            label="ElevenLabs API Key"
            helpText="Your ElevenLabs API key from elevenlabs.io/app/settings/api-keys"
            value={form.apiKey}
            hasStoredSecret={config.apiKeyPresent === true}
            willUpdateSecret={(form.apiKey?.length ?? 0) > 0}
            showSecret={showElevenLabsKey}
            placeholder="Enter API key"
            onToggle={onToggleElevenLabsKey}
            onChange={(val) => onFormChange({ apiKey: val })}
          />
          <Form.Group className="mb-3">
            <Form.Label className="small fw-medium">
              STT Model <HelpTip text="ElevenLabs speech-to-text model for transcribing voice messages." />
            </Form.Label>
            <Form.Control size="sm" value={form.sttModelId ?? ''}
              onChange={(e) => onFormChange({ sttModelId: toNullableString(e.target.value) })}
              placeholder="scribe_v1" />
          </Form.Group>
        </>
      )}

      {isWhisperStt && (
        <>
          <Form.Group className="mb-3">
            <Form.Label className="small fw-medium">
              Whisper STT URL <HelpTip text="Base URL of your Whisper-compatible STT server (e.g. http://localhost:5092 for Parakeet, https://api.openai.com for OpenAI)" />
            </Form.Label>
            <Form.Control size="sm" value={form.whisperSttUrl ?? ''}
              onChange={(e) => onFormChange({ whisperSttUrl: toNullableString(e.target.value) })}
              placeholder="http://parakeet:5092" />
          </Form.Group>
          <SecretField
            label="Whisper API Key (optional)"
            helpText="API key for authentication. Required for OpenAI, optional for local servers."
            value={form.whisperSttApiKey}
            hasStoredSecret={config.whisperSttApiKeyPresent === true}
            willUpdateSecret={(form.whisperSttApiKey?.length ?? 0) > 0}
            showSecret={showWhisperKey}
            placeholder="Enter API key (optional)"
            onToggle={onToggleWhisperKey}
            onChange={(val) => onFormChange({ whisperSttApiKey: val })}
          />
        </>
      )}
    </>
  );
}

interface TtsSectionProps {
  form: VoiceConfig;
  config: VoiceConfig;
  isWhisperStt: boolean;
  showElevenLabsKey: boolean;
  onFormChange: (patch: Partial<VoiceConfig>) => void;
  onToggleElevenLabsKey: () => void;
}

function TtsSection({ form, config, isWhisperStt, showElevenLabsKey, onFormChange, onToggleElevenLabsKey }: TtsSectionProps): ReactElement {
  const speedLabel = formatVoiceSpeed(form.speed);
  const speedValue = normalizeVoiceSpeed(form.speed);
  const hasStoredApiKey = config.apiKeyPresent === true;

  return (
    <>
      <h6 className="fw-semibold mt-4 mb-2">Text-to-Speech (TTS)</h6>

      {isWhisperStt ? (
        <SecretField
          label="ElevenLabs API Key"
          helpText="Your ElevenLabs API key for TTS."
          value={form.apiKey}
          hasStoredSecret={hasStoredApiKey}
          willUpdateSecret={(form.apiKey?.length ?? 0) > 0}
          showSecret={showElevenLabsKey}
          placeholder="Enter API key"
          onToggle={onToggleElevenLabsKey}
          onChange={(val) => onFormChange({ apiKey: val })}
        />
      ) : (
        <Form.Text className="text-muted d-block mb-3">Using the same ElevenLabs API key as STT above</Form.Text>
      )}

      <Row className="g-3">
        <Col md={6}>
          <Form.Group>
            <Form.Label className="small fw-medium">
              Voice ID <HelpTip text="ElevenLabs voice identifier. Find voices at elevenlabs.io/voice-library" />
            </Form.Label>
            <Form.Control size="sm" value={form.voiceId ?? ''}
              onChange={(e) => onFormChange({ voiceId: toNullableString(e.target.value) })} />
          </Form.Group>
        </Col>
        <Col md={6}>
          <Form.Group>
            <Form.Label className="small fw-medium">
              Speed: {speedLabel}
              <HelpTip text="Voice playback speed multiplier (0.5 = half speed, 2.0 = double speed)" />
            </Form.Label>
            <Form.Range min={0.5} max={2.0} step={0.1} value={speedValue}
              onChange={(e) => onFormChange({ speed: parseFloat(e.target.value) })} />
          </Form.Group>
        </Col>
      </Row>

      <Form.Group className="mt-3">
        <Form.Label className="small fw-medium">
          TTS Model <HelpTip text="ElevenLabs text-to-speech model. eleven_multilingual_v2 supports 29 languages." />
        </Form.Label>
        <Form.Control size="sm" value={form.ttsModelId ?? ''}
          onChange={(e) => onFormChange({ ttsModelId: toNullableString(e.target.value) })}
          placeholder="eleven_multilingual_v2" />
      </Form.Group>
    </>
  );
}

interface VoiceTabProps {
  config: VoiceConfig;
}

export default function VoiceTab({ config }: VoiceTabProps): ReactElement {
  const updateVoice = useUpdateVoice();
  const [form, setForm] = useState<VoiceConfig>({ ...config });
  const [showElevenLabsKey, setShowElevenLabsKey] = useState(false);
  const [showWhisperKey, setShowWhisperKey] = useState(false);
  const isVoiceDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const saveLabel = getSaveButtonLabel(updateVoice.isPending);
  const isWhisperStt = getSttProvider(form.sttProvider) === STT_PROVIDER_WHISPER;

  // Reset local draft when backend config changes (e.g. after successful save/refetch).
  useEffect(() => { setForm({ ...config }); }, [config]);

  const handleFormChange = (patch: Partial<VoiceConfig>): void => {
    setForm((prev) => ({ ...prev, ...patch }));
  };

  const handleSave = async (): Promise<void> => {
    await updateVoice.mutateAsync(form);
    toast.success('Voice settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Voice" />
        <Form.Check type="switch" label={<>Enable Voice <HelpTip text="Enable speech-to-text and text-to-speech" /></>}
          checked={form.enabled ?? false}
          onChange={(e) => handleFormChange({ enabled: e.target.checked })} className="mb-3" />

        <SttSection
          form={form} config={config}
          showElevenLabsKey={showElevenLabsKey} showWhisperKey={showWhisperKey}
          onFormChange={handleFormChange}
          onToggleElevenLabsKey={() => setShowElevenLabsKey(!showElevenLabsKey)}
          onToggleWhisperKey={() => setShowWhisperKey(!showWhisperKey)}
        />

        <TtsSection
          form={form} config={config}
          isWhisperStt={isWhisperStt}
          showElevenLabsKey={showElevenLabsKey}
          onFormChange={handleFormChange}
          onToggleElevenLabsKey={() => setShowElevenLabsKey(!showElevenLabsKey)}
        />

        <SettingsSaveBar className="mt-3">
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isVoiceDirty || updateVoice.isPending}>
            {saveLabel}
          </Button>
          <SaveStateHint isDirty={isVoiceDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
