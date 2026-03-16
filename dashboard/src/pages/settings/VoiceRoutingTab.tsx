import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import { useNavigate } from 'react-router-dom';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import type { VoiceConfig } from '../../api/settings';
import { useUpdateVoice } from '../../hooks/useSettings';
import { usePluginSettingsCatalog, useVoiceProviders } from '../../hooks/usePlugins';

interface VoiceRoutingTabProps {
  config: VoiceConfig;
}

interface ProviderOption {
  id: string;
  label: string;
  routeKey: string | null;
}

const DEFAULT_TTS_PROVIDER = 'golemcore/elevenlabs';
const LEGACY_ELEVENLABS_PROVIDER = 'elevenlabs';
const LEGACY_WHISPER_PROVIDER = 'whisper';
const CANONICAL_WHISPER_PROVIDER = 'golemcore/whisper';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function humanizeProviderId(providerId: string): string {
  const value = providerId.includes('/') ? providerId.split('/')[1] : providerId;
  return value
    .split('-')
    .filter((part) => part.length > 0)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join(' ');
}

function normalizeProviderId(value: string | null | undefined): string | null {
  if (value == null || value.trim().length === 0) {
    return null;
  }
  const normalized = value.trim().toLowerCase();
  if (normalized === LEGACY_ELEVENLABS_PROVIDER) {
    return DEFAULT_TTS_PROVIDER;
  }
  if (normalized === LEGACY_WHISPER_PROVIDER) {
    return CANONICAL_WHISPER_PROVIDER;
  }
  return normalized;
}

function buildProviderOptions(
  providers: Record<string, string> | undefined,
  routeByPluginId: Map<string, string>,
): ProviderOption[] {
  return Object.keys(providers ?? {})
    .sort((left, right) => humanizeProviderId(left).localeCompare(humanizeProviderId(right)))
    .map((id) => ({
      id,
      label: humanizeProviderId(id),
      routeKey: routeByPluginId.get(id) ?? null,
    }));
}

function resolveProviderSelection(
  value: string | null | undefined,
  options: ProviderOption[],
): string | null {
  const normalized = normalizeProviderId(value);
  if (normalized != null && options.some((option) => option.id === normalized)) {
    return normalized;
  }
  return options[0]?.id ?? null;
}

function buildFormState(
  config: VoiceConfig,
  sttOptions: ProviderOption[],
  ttsOptions: ProviderOption[],
): VoiceConfig {
  return {
    ...config,
    sttProvider: resolveProviderSelection(config.sttProvider, sttOptions),
    ttsProvider: resolveProviderSelection(config.ttsProvider, ttsOptions),
  };
}

export default function VoiceRoutingTab({ config }: VoiceRoutingTabProps): ReactElement {
  const navigate = useNavigate();
  const updateVoice = useUpdateVoice();
  const { data: providers } = useVoiceProviders();
  const { data: pluginCatalog = [] } = usePluginSettingsCatalog();
  const routeByPluginId = useMemo(() => new Map(pluginCatalog.map((item) => [item.pluginId, item.routeKey])), [pluginCatalog]);
  const sttOptions = useMemo(
    () => buildProviderOptions(providers?.stt, routeByPluginId),
    [providers?.stt, routeByPluginId],
  );
  const ttsOptions = useMemo(
    () => buildProviderOptions(providers?.tts, routeByPluginId),
    [providers?.tts, routeByPluginId],
  );

  const initialForm = useMemo(() => buildFormState(config, sttOptions, ttsOptions), [config, sttOptions, ttsOptions]);
  const [form, setForm] = useState<VoiceConfig>(initialForm);

  // Keep the local routing form aligned with backend config refreshes.
  useEffect(() => {
    setForm(initialForm);
  }, [initialForm]);

  const isDirty = useMemo(() => hasDiff(form, initialForm), [form, initialForm]);

  const providerSettingsRoutes = useMemo(() => {
    const entries = new Map<string, { id: string; label: string; routeKey: string }>();
    [...sttOptions, ...ttsOptions].forEach((option) => {
      if (option.routeKey != null) {
        entries.set(option.id, {
          id: option.id,
          label: option.label,
          routeKey: option.routeKey,
        });
      }
    });
    return Array.from(entries.values());
  }, [sttOptions, ttsOptions]);

  const handleSave = async (): Promise<void> => {
    await updateVoice.mutateAsync({
      ...config,
      ...form,
      sttProvider: normalizeProviderId(form.sttProvider),
      ttsProvider: normalizeProviderId(form.ttsProvider),
    });
    toast.success('Voice routing saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Voice Routing" />
        <Form.Text className="text-muted d-block mb-3">
          Choose the active STT/TTS providers from the plugins currently loaded by the runtime.
        </Form.Text>
        <div className="d-flex align-items-center gap-2 mb-3 voice-status-badges">
          <Badge bg="primary">STT: {sttOptions.find((option) => option.id === form.sttProvider)?.label ?? 'None'}</Badge>
          <Badge bg="primary">TTS: {ttsOptions.find((option) => option.id === form.ttsProvider)?.label ?? 'None'}</Badge>
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
                value={form.sttProvider ?? ''}
                disabled={sttOptions.length === 0}
                onChange={(event) => setForm((prev) => ({ ...prev, sttProvider: event.target.value || null }))}
              >
                {sttOptions.length === 0 && <option value="">No STT providers loaded</option>}
                {sttOptions.map((option) => (
                  <option key={option.id} value={option.id}>{option.label}</option>
                ))}
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
                value={form.ttsProvider ?? ''}
                disabled={ttsOptions.length === 0}
                onChange={(event) => setForm((prev) => ({ ...prev, ttsProvider: event.target.value || null }))}
              >
                {ttsOptions.length === 0 && <option value="">No TTS providers loaded</option>}
                {ttsOptions.map((option) => (
                  <option key={option.id} value={option.id}>{option.label}</option>
                ))}
              </Form.Select>
            </Form.Group>
          </Col>
        </Row>

        <Form.Text className="text-muted d-block mt-3">
          Voice routing stays in runtime config. Provider-specific credentials and model settings live in each provider plugin page.
        </Form.Text>

        {providerSettingsRoutes.length > 0 && (
          <Row className="g-2 mt-1 voice-provider-links">
            {providerSettingsRoutes.map((provider) => (
              <Col xs={12} sm="auto" key={provider.id}>
                <Button
                  type="button"
                  size="sm"
                  className="voice-route-shortcut"
                  variant="secondary"
                  onClick={() => navigate(`/settings/${provider.routeKey}`)}
                >
                  Configure {provider.label}
                </Button>
              </Col>
            ))}
          </Row>
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
