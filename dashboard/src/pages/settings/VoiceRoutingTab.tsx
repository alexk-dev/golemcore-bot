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
const DEFAULT_STT_PROVIDER = 'golemcore/elevenlabs';
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

function normalizeProviderId(value: string | null | undefined, fallback: string): string {
  if (value == null || value.trim().length === 0) {
    return fallback;
  }
  const normalized = value.trim().toLowerCase();
  if (normalized === LEGACY_ELEVENLABS_PROVIDER) {
    return DEFAULT_STT_PROVIDER;
  }
  if (normalized === LEGACY_WHISPER_PROVIDER) {
    return CANONICAL_WHISPER_PROVIDER;
  }
  return normalized;
}

function buildProviderOptions(
  providers: Record<string, string> | undefined,
  fallback: string,
  routeByPluginId: Map<string, string>,
): ProviderOption[] {
  const ids = new Set<string>(Object.keys(providers ?? {}));
  ids.add(fallback);
  return Array.from(ids)
    .sort((left, right) => humanizeProviderId(left).localeCompare(humanizeProviderId(right)))
    .map((id) => ({
      id,
      label: humanizeProviderId(id),
      routeKey: routeByPluginId.get(id) ?? null,
    }));
}

export default function VoiceRoutingTab({ config }: VoiceRoutingTabProps): ReactElement {
  const navigate = useNavigate();
  const updateVoice = useUpdateVoice();
  const { data: providers } = useVoiceProviders();
  const { data: pluginCatalog = [] } = usePluginSettingsCatalog();
  const routeByPluginId = useMemo(() => new Map(pluginCatalog.map((item) => [item.pluginId, item.routeKey])), [pluginCatalog]);
  const sttOptions = useMemo(
    () => buildProviderOptions(providers?.stt, normalizeProviderId(config.sttProvider, DEFAULT_STT_PROVIDER), routeByPluginId),
    [providers?.stt, config.sttProvider, routeByPluginId],
  );
  const ttsOptions = useMemo(
    () => buildProviderOptions(providers?.tts, normalizeProviderId(config.ttsProvider, DEFAULT_TTS_PROVIDER), routeByPluginId),
    [providers?.tts, config.ttsProvider, routeByPluginId],
  );

  const [form, setForm] = useState<VoiceConfig>({
    ...config,
    sttProvider: normalizeProviderId(config.sttProvider, DEFAULT_STT_PROVIDER),
    ttsProvider: normalizeProviderId(config.ttsProvider, DEFAULT_TTS_PROVIDER),
  });

  useEffect(() => {
    setForm({
      ...config,
      sttProvider: normalizeProviderId(config.sttProvider, DEFAULT_STT_PROVIDER),
      ttsProvider: normalizeProviderId(config.ttsProvider, DEFAULT_TTS_PROVIDER),
    });
  }, [config]);

  const isDirty = useMemo(() => hasDiff(form, {
    ...config,
    sttProvider: normalizeProviderId(config.sttProvider, DEFAULT_STT_PROVIDER),
    ttsProvider: normalizeProviderId(config.ttsProvider, DEFAULT_TTS_PROVIDER),
  }), [form, config]);

  const selectedProviderRoutes = useMemo(() => {
    const entries = new Map<string, { id: string; label: string; routeKey: string }>();
    const sttProvider = form.sttProvider != null ? String(form.sttProvider) : DEFAULT_STT_PROVIDER;
    const ttsProvider = form.ttsProvider != null ? String(form.ttsProvider) : DEFAULT_TTS_PROVIDER;
    [sttProvider, ttsProvider].forEach((providerId) => {
      const routeKey = routeByPluginId.get(providerId);
      if (routeKey != null) {
        entries.set(providerId, {
          id: providerId,
          label: humanizeProviderId(providerId),
          routeKey,
        });
      }
    });
    return Array.from(entries.values());
  }, [form.sttProvider, form.ttsProvider, routeByPluginId]);

  const handleSave = async (): Promise<void> => {
    await updateVoice.mutateAsync({
      ...config,
      ...form,
      sttProvider: normalizeProviderId(form.sttProvider, DEFAULT_STT_PROVIDER),
      ttsProvider: normalizeProviderId(form.ttsProvider, DEFAULT_TTS_PROVIDER),
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
          <Badge bg="primary">STT: {humanizeProviderId(normalizeProviderId(form.sttProvider, DEFAULT_STT_PROVIDER))}</Badge>
          <Badge bg="primary">TTS: {humanizeProviderId(normalizeProviderId(form.ttsProvider, DEFAULT_TTS_PROVIDER))}</Badge>
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
                value={normalizeProviderId(form.sttProvider, DEFAULT_STT_PROVIDER)}
                disabled={sttOptions.length === 0}
                onChange={(event) => setForm((prev) => ({ ...prev, sttProvider: event.target.value }))}
              >
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
                value={normalizeProviderId(form.ttsProvider, DEFAULT_TTS_PROVIDER)}
                disabled={ttsOptions.length === 0}
                onChange={(event) => setForm((prev) => ({ ...prev, ttsProvider: event.target.value }))}
              >
                {ttsOptions.map((option) => (
                  <option key={option.id} value={option.id}>{option.label}</option>
                ))}
              </Form.Select>
            </Form.Group>
          </Col>
        </Row>

        <Form.Text className="text-muted d-block mt-3">
          Provider-specific credentials and endpoint configuration now live in plugin pages. Configure the provider first if it needs additional secrets or URLs.
        </Form.Text>

        {selectedProviderRoutes.length > 0 && (
          <Row className="g-2 mt-1 voice-provider-links">
            {selectedProviderRoutes.map((provider) => (
              <Col xs={12} sm="auto" key={provider.id}>
                <Button
                  type="button"
                  size="sm"
                  className="voice-route-shortcut"
                  variant="secondary"
                  onClick={() => navigate(`/settings/${provider.routeKey}`)}
                >
                  Open {provider.label}
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
