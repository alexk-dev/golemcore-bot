import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useBrowserHealthPing } from '../../hooks/useSystem';
import { useUpdateTools } from '../../hooks/useSettings';
import type { ImapConfig, SmtpConfig, ToolsConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

type ToolsMode =
  | 'all'
  | 'browser'
  | 'brave'
  | 'filesystem'
  | 'shell'
  | 'email'
  | 'automation'
  | 'skills'
  | 'skillTransition'
  | 'tier'
  | 'goals';

type ToolFlagKey =
  | 'browserEnabled'
  | 'filesystemEnabled'
  | 'shellEnabled'
  | 'braveSearchEnabled'
  | 'skillManagementEnabled'
  | 'skillTransitionEnabled'
  | 'tierEnabled'
  | 'goalManagementEnabled';

interface ToolsTabProps {
  config: ToolsConfig;
  mode?: ToolsMode;
}

interface ToolMeta {
  key: ToolFlagKey;
  label: string;
  desc: string;
  tip: string;
}

interface ToolToggleRowProps {
  item: ToolMeta;
  checked: boolean;
  onToggle: (enabled: boolean) => void;
  withBorder?: boolean;
}

const TOOL_ITEMS: ToolMeta[] = [
  {
    key: 'browserEnabled',
    label: 'Browser',
    desc: 'Headless browser automation',
    tip: 'Enables browser navigation, extraction, and screenshot capabilities',
  },
  {
    key: 'filesystemEnabled',
    label: 'Filesystem',
    desc: 'Read/write files in sandbox',
    tip: 'Allows the bot to create, read, and modify files in the sandboxed workspace directory',
  },
  {
    key: 'shellEnabled',
    label: 'Shell',
    desc: 'Execute shell commands',
    tip: 'Allows the bot to run shell commands (ls, grep, python, etc.) in the sandboxed workspace',
  },
  {
    key: 'braveSearchEnabled',
    label: 'Brave Search',
    desc: 'Web search via Brave API',
    tip: 'Enables web search using Brave Search API. Requires a valid API key (free tier: 2000 queries/month)',
  },
  {
    key: 'skillManagementEnabled',
    label: 'Skill Management',
    desc: 'Create/edit skills',
    tip: 'Allows the LLM to create, list, and delete skill definitions programmatically',
  },
  {
    key: 'skillTransitionEnabled',
    label: 'Skill Transition',
    desc: 'LLM-initiated skill switching',
    tip: 'Allows the LLM to transition between skills during a conversation pipeline',
  },
  {
    key: 'tierEnabled',
    label: 'Tier Tool',
    desc: 'LLM-initiated tier switching',
    tip: 'Allows the LLM to upgrade/downgrade model tier within a session',
  },
  {
    key: 'goalManagementEnabled',
    label: 'Goal Management',
    desc: 'Auto mode goal management',
    tip: 'Allows the LLM to create, update, and complete goals in autonomous mode',
  },
];

const KEY_BY_MODE: Partial<Record<ToolsMode, ToolFlagKey>> = {
  browser: 'browserEnabled',
  filesystem: 'filesystemEnabled',
  shell: 'shellEnabled',
  brave: 'braveSearchEnabled',
  skills: 'skillManagementEnabled',
  skillTransition: 'skillTransitionEnabled',
  tier: 'tierEnabled',
  goals: 'goalManagementEnabled',
};

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function ToolToggleRow({ item, checked, onToggle, withBorder = true }: ToolToggleRowProps): ReactElement {
  return (
    <div className={`tools-toggle-row d-flex align-items-start py-2${withBorder ? ' border-bottom' : ''}`}>
      <Form.Check
        type="switch"
        checked={checked}
        onChange={(event) => onToggle(event.target.checked)}
        className="me-3"
        aria-label={`Toggle ${item.label}`}
      />
      <div>
        <div className="fw-medium small">
          {item.label} <HelpTip text={item.tip} />
        </div>
        <div className="meta-text tools-row-desc">{item.desc}</div>
      </div>
    </div>
  );
}

export default function ToolsTab({ config, mode = 'all' }: ToolsTabProps): ReactElement {
  const updateTools = useUpdateTools();
  const browserHealthPing = useBrowserHealthPing();
  const [form, setForm] = useState<ToolsConfig>({ ...config });
  const isToolsDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const selectedToolKey = KEY_BY_MODE[mode];
  const selectedTool = selectedToolKey != null
    ? TOOL_ITEMS.find((tool) => tool.key === selectedToolKey) ?? null
    : null;

  const showToolToggles = mode === 'all';
  const showBraveApiKey = mode === 'all' || mode === 'brave';
  const showImap = mode === 'all' || mode === 'email';
  const showSmtp = mode === 'all' || mode === 'email';
  const showBrowserInfo = mode === 'browser';
  const showAutomationGroup = mode === 'automation';
  const isBrowserEnabled = form.browserEnabled ?? true;

  const automationTools = TOOL_ITEMS.filter((tool) => (
    tool.key === 'skillManagementEnabled' || tool.key === 'skillTransitionEnabled' || tool.key === 'tierEnabled'
  ));

  const setToolEnabled = (key: ToolFlagKey, enabled: boolean): void => {
    setForm((prev) => ({ ...prev, [key]: enabled }));
  };

  const updateImap = (partial: Partial<ImapConfig>): void => {
    setForm((prev) => ({
      ...prev,
      imap: {
        ...prev.imap,
        ...partial,
      },
    }));
  };

  const updateSmtp = (partial: Partial<SmtpConfig>): void => {
    setForm((prev) => ({
      ...prev,
      smtp: {
        ...prev.smtp,
        ...partial,
      },
    }));
  };

  const handleSave = async (): Promise<void> => {
    await updateTools.mutateAsync(form);
    toast.success('Tools settings saved');
  };

  return (
    <section className="tools-tab">
      {showToolToggles && (
        <Card className="settings-card tools-card mb-3">
          <Card.Body>
            <SettingsCardTitle title="Tool Toggles" className="tools-card-title" />
            {TOOL_ITEMS.map((tool, index) => (
              <ToolToggleRow
                key={tool.key}
                item={tool}
                checked={form[tool.key] ?? true}
                onToggle={(enabled) => setToolEnabled(tool.key, enabled)}
                withBorder={index < TOOL_ITEMS.length - 1}
              />
            ))}
          </Card.Body>
        </Card>
      )}

      {!showToolToggles && selectedTool != null && mode !== 'brave' && (
        <Card className="settings-card tools-card mb-3">
          <Card.Body>
            <SettingsCardTitle title={selectedTool.label} className="tools-card-title" />
            <Form.Check
              type="switch"
              label={<>Enable {selectedTool.label} <HelpTip text={selectedTool.tip} /></>}
              checked={form[selectedTool.key] ?? true}
              onChange={(event) => setToolEnabled(selectedTool.key, event.target.checked)}
              className="mb-2"
            />
            <div className="meta-text">{selectedTool.desc}</div>
          </Card.Body>
        </Card>
      )}

      {mode === 'brave' && (
        <Card className="settings-card tools-card mb-3">
          <Card.Body>
            <SettingsCardTitle title="Brave Search" className="tools-card-title" />
            <Form.Check
              type="switch"
              label={<>Enable Brave Search <HelpTip text="Enable Brave as active browser search provider" /></>}
              checked={form.braveSearchEnabled ?? true}
              onChange={(event) => setToolEnabled('braveSearchEnabled', event.target.checked)}
              className="mb-3"
            />
            <Form.Group>
              <Form.Label className="small fw-medium">
                Brave Search API Key <HelpTip text="Get your free API key at brave.com/search/api" />
              </Form.Label>
              <Form.Control
                size="sm"
                type="password"
                autoComplete="new-password"
                value={form.braveSearchApiKey ?? ''}
                onChange={(event) => setForm({ ...form, braveSearchApiKey: toNullableString(event.target.value) })}
                placeholder="BSA-..."
              />
            </Form.Group>
          </Card.Body>
        </Card>
      )}

      {showAutomationGroup && (
        <Card className="settings-card tools-card mb-3">
          <Card.Body>
            <SettingsCardTitle title="Automation Tools" className="tools-card-title" />
            <div className="small text-body-secondary mb-3 tools-note">All tool enable/disable flags are managed here.</div>
            <ToolToggleRow
              item={{
                key: 'browserEnabled',
                label: 'Browser',
                desc: 'Enable browser tool access.',
                tip: 'Enable headless browsing for web navigation and extraction',
              }}
              checked={form.browserEnabled ?? true}
              onToggle={(enabled) => setToolEnabled('browserEnabled', enabled)}
            />
            <ToolToggleRow
              item={{
                key: 'filesystemEnabled',
                label: 'Filesystem',
                desc: 'Enable filesystem tool access.',
                tip: 'Read/write files in sandboxed workspace',
              }}
              checked={form.filesystemEnabled ?? true}
              onToggle={(enabled) => setToolEnabled('filesystemEnabled', enabled)}
            />
            <ToolToggleRow
              item={{
                key: 'shellEnabled',
                label: 'Shell',
                desc: 'Enable shell tool access.',
                tip: 'Execute shell commands in sandbox',
              }}
              checked={form.shellEnabled ?? true}
              onToggle={(enabled) => setToolEnabled('shellEnabled', enabled)}
            />
            {automationTools.map((tool) => (
              <ToolToggleRow
                key={tool.key}
                item={tool}
                checked={form[tool.key] ?? true}
                onToggle={(enabled) => setToolEnabled(tool.key, enabled)}
              />
            ))}
            <ToolToggleRow
              item={{
                key: 'goalManagementEnabled',
                label: 'Goal Management',
                desc: 'Enable goal management tool.',
                tip: 'Auto mode goal management operations',
              }}
                checked={form.goalManagementEnabled ?? true}
                onToggle={(enabled) => setToolEnabled('goalManagementEnabled', enabled)}
              />
            <div className="tools-toggle-row d-flex align-items-start py-2 border-bottom">
              <Form.Check
                type="switch"
                checked={form.imap?.enabled ?? false}
                onChange={(event) => updateImap({ enabled: event.target.checked })}
                className="me-3"
                aria-label="Toggle IMAP"
              />
              <div>
                <div className="fw-medium small">IMAP <HelpTip text="Email reading integration" /></div>
                <div className="meta-text tools-row-desc">Enable IMAP settings and tool operations.</div>
              </div>
            </div>
            <div className="tools-toggle-row d-flex align-items-start py-2">
              <Form.Check
                type="switch"
                checked={form.smtp?.enabled ?? false}
                onChange={(event) => updateSmtp({ enabled: event.target.checked })}
                className="me-3"
                aria-label="Toggle SMTP"
              />
              <div>
                <div className="fw-medium small">SMTP <HelpTip text="Email sending integration" /></div>
                <div className="meta-text tools-row-desc">Enable SMTP settings and tool operations.</div>
              </div>
            </div>
          </Card.Body>
        </Card>
      )}

      {showBrowserInfo && (
        <Card className="settings-card tools-card mb-3">
          <Card.Body>
            <SettingsCardTitle title="Browser Tool" className="mb-2 tools-card-title" />
            <Row className="g-3">
              <Col md={6}>
                <Form.Group>
                  <Form.Label className="small fw-medium">
                    Browser Engine <HelpTip text="Browser engine implementation." />
                  </Form.Label>
                  <Form.Select
                    size="sm"
                    value={form.browserType ?? 'playwright'}
                    onChange={(event) => setForm({ ...form, browserType: event.target.value })}
                    disabled={!isBrowserEnabled}
                  >
                    <option value="playwright">Playwright</option>
                  </Form.Select>
                </Form.Group>
              </Col>
              <Col md={6}>
                <Form.Group>
                  <Form.Label className="small fw-medium">
                    Browser API Provider <HelpTip text="Active provider for browser/search tools." />
                  </Form.Label>
                  <Form.Select
                    size="sm"
                    value={form.browserApiProvider ?? 'brave'}
                    onChange={(event) => setForm({ ...form, browserApiProvider: event.target.value })}
                    disabled={!isBrowserEnabled}
                  >
                    <option value="brave">Brave</option>
                  </Form.Select>
                </Form.Group>
              </Col>
              <Col md={6}>
                <Form.Group>
                  <Form.Label className="small fw-medium">
                    Timeout (ms) <HelpTip text="Page operation timeout in milliseconds." />
                  </Form.Label>
                  <Form.Control
                    size="sm"
                    type="number"
                    min={1000}
                    max={120000}
                    step={500}
                    value={form.browserTimeout ?? 30000}
                    onChange={(event) => setForm({ ...form, browserTimeout: Number(event.target.value) })}
                    disabled={!isBrowserEnabled}
                  />
                </Form.Group>
              </Col>
              <Col md={6}>
                <Form.Check
                  type="switch"
                  label={<>Headless Browser <HelpTip text="Run browser automation in headless mode" /></>}
                  checked={form.browserHeadless ?? true}
                  onChange={(event) => setForm({ ...form, browserHeadless: event.target.checked })}
                  className="mt-md-4 mt-2"
                  disabled={!isBrowserEnabled}
                />
              </Col>
              <Col md={12}>
                <Form.Group>
                  <Form.Label className="small fw-medium">
                    User-Agent <HelpTip text="User-Agent string used for browser sessions." />
                  </Form.Label>
                  <Form.Control
                    size="sm"
                    value={form.browserUserAgent ?? ''}
                    onChange={(event) => setForm({ ...form, browserUserAgent: toNullableString(event.target.value) })}
                    disabled={!isBrowserEnabled}
                  />
                </Form.Group>
              </Col>
            </Row>
            {!isBrowserEnabled && (
              <div className="meta-text mt-3 tools-note">
                Browser tool is disabled. Enable it above to edit runtime behavior and run health checks.
              </div>
            )}
            <div className="tools-browser-status mt-3 pt-3 border-top" aria-live="polite">
              <div className="d-flex align-items-center gap-2 mb-2">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => browserHealthPing.mutate()}
                  disabled={browserHealthPing.isPending || !isBrowserEnabled}
                >
                  {browserHealthPing.isPending ? 'Pinging...' : 'Ping Browser'}
                </Button>
                {browserHealthPing.data != null && (
                  <Badge bg={browserHealthPing.data.ok ? 'success' : 'danger'}>
                    {browserHealthPing.data.ok ? 'Healthy' : 'Failed'}
                  </Badge>
                )}
              </div>
              {browserHealthPing.data != null && (
                <div className="meta-text">{browserHealthPing.data.message}</div>
              )}
            </div>
          </Card.Body>
        </Card>
      )}

      {showBraveApiKey && mode !== 'brave' && (form.braveSearchEnabled ?? false) && (
        <Card className="settings-card tools-card mb-3">
          <Card.Body>
            <SettingsCardTitle title="Brave Search Credentials" className="tools-card-title" />
            <Form.Group>
              <Form.Label className="small fw-medium">
                Brave Search API Key <HelpTip text="Get your free API key at brave.com/search/api" />
              </Form.Label>
              <Form.Control
                size="sm"
                type="password"
                autoComplete="new-password"
                value={form.braveSearchApiKey ?? ''}
                onChange={(event) => setForm({ ...form, braveSearchApiKey: toNullableString(event.target.value) })}
                placeholder="BSA-..."
              />
            </Form.Group>
          </Card.Body>
        </Card>
      )}

      {showImap && (
        <Card className="settings-card tools-card mb-3">
          <Card.Body>
            <SettingsCardTitle
              title="IMAP (Email Reading)"
              tip="Read emails from an IMAP mailbox. The bot can search, read, and list emails."
              className="tools-card-title"
            />
            <Form.Check
              type="switch"
              label="Enable IMAP"
              checked={form.imap?.enabled ?? false}
              onChange={(event) => updateImap({ enabled: event.target.checked })}
              className="mb-3"
            />

            {form.imap?.enabled === true && (
              <>
                <Row className="g-3 mb-3">
                  <Col md={6}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Host <HelpTip text="IMAP server hostname (e.g. imap.gmail.com)" />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        value={form.imap?.host ?? ''}
                        onChange={(event) => updateImap({ host: toNullableString(event.target.value) })}
                        placeholder="imap.gmail.com"
                      />
                    </Form.Group>
                  </Col>
                  <Col md={3}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Port <HelpTip text="IMAP port (993 for SSL, 143 for plain/STARTTLS)" />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        type="number"
                        value={form.imap?.port ?? 993}
                        onChange={(event) => updateImap({ port: toNullableInt(event.target.value) })}
                      />
                    </Form.Group>
                  </Col>
                  <Col md={3}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Security <HelpTip text="Connection security: ssl (port 993), starttls (port 143), or none" />
                      </Form.Label>
                      <Form.Select
                        size="sm"
                        value={form.imap?.security ?? 'ssl'}
                        onChange={(event) => updateImap({ security: event.target.value })}
                      >
                        <option value="ssl">SSL</option>
                        <option value="starttls">STARTTLS</option>
                        <option value="none">None</option>
                      </Form.Select>
                    </Form.Group>
                  </Col>
                </Row>

                <Row className="g-3 mb-3">
                  <Col md={6}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">Username</Form.Label>
                      <Form.Control
                        size="sm"
                        value={form.imap?.username ?? ''}
                        onChange={(event) => updateImap({ username: toNullableString(event.target.value) })}
                        placeholder="user@example.com"
                      />
                    </Form.Group>
                  </Col>
                  <Col md={6}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Password <HelpTip text="For Gmail, use an App Password (not your regular password)" />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        type="password"
                        autoComplete="new-password"
                        value={form.imap?.password ?? ''}
                        onChange={(event) => updateImap({ password: toNullableString(event.target.value) })}
                      />
                    </Form.Group>
                  </Col>
                </Row>

                <Row className="g-3">
                  <Col md={4}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Max Body Length <HelpTip text="Maximum number of characters to read from email body" />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        type="number"
                        value={form.imap?.maxBodyLength ?? 50000}
                        onChange={(event) => updateImap({ maxBodyLength: toNullableInt(event.target.value) })}
                      />
                    </Form.Group>
                  </Col>
                  <Col md={4}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Default Message Limit <HelpTip text="Max emails returned per listing request" />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        type="number"
                        value={form.imap?.defaultMessageLimit ?? 20}
                        onChange={(event) => updateImap({ defaultMessageLimit: toNullableInt(event.target.value) })}
                      />
                    </Form.Group>
                  </Col>
                  <Col md={4}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        SSL Trust <HelpTip text="SSL certificate trust configuration. Leave blank for default, or set to '*' to trust all." />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        value={form.imap?.sslTrust ?? ''}
                        onChange={(event) => updateImap({ sslTrust: toNullableString(event.target.value) })}
                        placeholder="*"
                      />
                    </Form.Group>
                  </Col>
                </Row>
              </>
            )}
          </Card.Body>
        </Card>
      )}

      {showSmtp && (
        <Card className="settings-card tools-card mb-3">
          <Card.Body>
            <SettingsCardTitle
              title="SMTP (Email Sending)"
              tip="Send emails via SMTP. The bot can compose and send emails on your behalf."
              className="tools-card-title"
            />
            <Form.Check
              type="switch"
              label="Enable SMTP"
              checked={form.smtp?.enabled ?? false}
              onChange={(event) => updateSmtp({ enabled: event.target.checked })}
              className="mb-3"
            />

            {form.smtp?.enabled === true && (
              <>
                <Row className="g-3 mb-3">
                  <Col md={6}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Host <HelpTip text="SMTP server hostname (e.g. smtp.gmail.com)" />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        value={form.smtp?.host ?? ''}
                        onChange={(event) => updateSmtp({ host: toNullableString(event.target.value) })}
                        placeholder="smtp.gmail.com"
                      />
                    </Form.Group>
                  </Col>
                  <Col md={3}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Port <HelpTip text="SMTP port (587 for STARTTLS, 465 for SSL, 25 for plain)" />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        type="number"
                        value={form.smtp?.port ?? 587}
                        onChange={(event) => updateSmtp({ port: toNullableInt(event.target.value) })}
                      />
                    </Form.Group>
                  </Col>
                  <Col md={3}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Security <HelpTip text="Connection security: starttls (port 587), ssl (port 465), or none" />
                      </Form.Label>
                      <Form.Select
                        size="sm"
                        value={form.smtp?.security ?? 'starttls'}
                        onChange={(event) => updateSmtp({ security: event.target.value })}
                      >
                        <option value="starttls">STARTTLS</option>
                        <option value="ssl">SSL</option>
                        <option value="none">None</option>
                      </Form.Select>
                    </Form.Group>
                  </Col>
                </Row>

                <Row className="g-3 mb-3">
                  <Col md={6}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">Username</Form.Label>
                      <Form.Control
                        size="sm"
                        value={form.smtp?.username ?? ''}
                        onChange={(event) => updateSmtp({ username: toNullableString(event.target.value) })}
                        placeholder="user@example.com"
                      />
                    </Form.Group>
                  </Col>
                  <Col md={6}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        Password <HelpTip text="For Gmail, use an App Password (not your regular password)" />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        type="password"
                        autoComplete="new-password"
                        value={form.smtp?.password ?? ''}
                        onChange={(event) => updateSmtp({ password: toNullableString(event.target.value) })}
                      />
                    </Form.Group>
                  </Col>
                </Row>

                <Row className="g-3">
                  <Col md={6}>
                    <Form.Group>
                      <Form.Label className="small fw-medium">
                        SSL Trust <HelpTip text="SSL certificate trust configuration. Leave blank for default." />
                      </Form.Label>
                      <Form.Control
                        size="sm"
                        value={form.smtp?.sslTrust ?? ''}
                        onChange={(event) => updateSmtp({ sslTrust: toNullableString(event.target.value) })}
                        placeholder="*"
                      />
                    </Form.Group>
                  </Col>
                </Row>
              </>
            )}
          </Card.Body>
        </Card>
      )}

      <SettingsSaveBar variant="tools">
        <Button
          type="button"
          variant="primary"
          size="sm"
          onClick={() => {
            void handleSave();
          }}
          disabled={!isToolsDirty || updateTools.isPending}
        >
          {updateTools.isPending ? 'Saving...' : 'Save Tool Settings'}
        </Button>
        <SaveStateHint isDirty={isToolsDirty} />
      </SettingsSaveBar>
    </section>
  );
}
