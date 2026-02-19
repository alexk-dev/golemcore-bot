import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Card, Form, InputGroup } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import { SecretStatusBadges } from '../../components/common/SecretStatusBadges';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { getSecretInputType, getSecretPlaceholder, getSecretToggleLabel } from '../../components/common/secretInputUtils';
import type { VoiceConfig } from '../../api/settings';
import { useUpdateVoice } from '../../hooks/useSettings';
import { STT_PROVIDER_WHISPER, isValidHttpUrl, resolveSttProvider, resolveTtsProvider } from './voiceProviders';

interface WhisperTabProps {
  config: VoiceConfig;
}

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

interface WhisperUrlValidation {
  hasError: boolean;
  message: string;
}

function buildWhisperUrlValidation(isWhisperStt: boolean, whisperSttUrl: string | null | undefined): WhisperUrlValidation {
  if (!isWhisperStt) {
    return { hasError: false, message: '' };
  }
  if (whisperSttUrl == null || whisperSttUrl.trim().length === 0) {
    return {
      hasError: true,
      message: 'Whisper STT URL is required when Whisper is selected as STT provider.',
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

function getWhisperStatusVariant(isWhisperStt: boolean): string {
  return isWhisperStt ? 'primary' : 'secondary';
}

function getWhisperStatusLabel(isWhisperStt: boolean): string {
  return isWhisperStt ? 'Active' : 'Inactive';
}

export default function WhisperTab({ config }: WhisperTabProps): ReactElement {
  const updateVoice = useUpdateVoice();
  const [form, setForm] = useState<VoiceConfig>({ ...config });
  const [showKey, setShowKey] = useState(false);
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const hasStoredApiKey = config.whisperSttApiKeyPresent === true;
  const willUpdateApiKey = (form.whisperSttApiKey?.length ?? 0) > 0;
  const isWhisperStt = resolveSttProvider(form.sttProvider) === STT_PROVIDER_WHISPER;
  const whisperUrlValidation = buildWhisperUrlValidation(isWhisperStt, form.whisperSttUrl);
  const whisperStatus = getWhisperStatusLabel(isWhisperStt);
  const whisperStatusVariant = getWhisperStatusVariant(isWhisperStt);
  const saveDisabled = !isDirty || updateVoice.isPending || whisperUrlValidation.hasError;

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
    toast.success('Whisper settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Whisper" />
        <div className="d-flex align-items-center gap-2 mb-3">
          <Badge bg={whisperStatusVariant}>
            STT: {whisperStatus}
          </Badge>
        </div>

        {!isWhisperStt && (
          <Form.Text className="text-muted d-block mb-3">
            Whisper is configured but not active. Enable it in Tools -&gt; Voice -&gt; STT Provider.
          </Form.Text>
        )}

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Whisper STT URL <HelpTip text="Base URL of your Whisper-compatible server (e.g. http://localhost:5092, https://api.openai.com)" />
          </Form.Label>
          <Form.Control
            size="sm"
            value={form.whisperSttUrl ?? ''}
            isInvalid={whisperUrlValidation.hasError}
            onChange={(event) => setForm((prev) => ({ ...prev, whisperSttUrl: toNullableString(event.target.value) }))}
            placeholder="http://localhost:5092"
          />
          <Form.Control.Feedback type="invalid">
            {whisperUrlValidation.message}
          </Form.Control.Feedback>
        </Form.Group>

        <Form.Group>
          <Form.Label className="small fw-medium d-flex align-items-center gap-2">
            Whisper API Key (optional) <HelpTip text="Use for providers that require auth, such as OpenAI-compatible endpoints" />
            <SecretStatusBadges hasStoredSecret={hasStoredApiKey} willUpdateSecret={willUpdateApiKey} />
          </Form.Label>
          <InputGroup size="sm">
            <Form.Control
              type={getSecretInputType(showKey)}
              autoComplete="new-password"
              value={form.whisperSttApiKey ?? ''}
              onChange={(event) => setForm((prev) => ({ ...prev, whisperSttApiKey: toNullableString(event.target.value) }))}
              placeholder={getSecretPlaceholder(hasStoredApiKey, 'Enter API key (optional)')}
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
            />
            <Button type="button" variant="secondary" onClick={() => setShowKey(!showKey)}>
              {getSecretToggleLabel(showKey)}
            </Button>
          </InputGroup>
        </Form.Group>

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
