import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Card, Col, Form, OverlayTrigger, Row, Tooltip } from 'react-bootstrap';
import { FiHelpCircle } from 'react-icons/fi';
import toast from 'react-hot-toast';
import { useBrowserHealthPing } from '../../hooks/useSystem';
import { useUpdateTools } from '../../hooks/useSettings';
import type { ImapConfig, SmtpConfig, ToolsConfig } from '../../api/settings';

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

interface ToolsTabProps {
  config: ToolsConfig;
  mode?: ToolsMode;
}

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

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

export default function ToolsTab({ config, mode = 'all' }: ToolsTabProps): ReactElement {
  const updateTools = useUpdateTools();
  const browserHealthPing = useBrowserHealthPing();
  const [form, setForm] = useState<ToolsConfig>({ ...config });
  const isToolsDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => { setForm({ ...config }); }, [config]);

  const tools = [
    { key: 'filesystemEnabled' as const, label: 'Filesystem', desc: 'Read/write files in sandbox', tip: 'Allows the bot to create, read, and modify files in the sandboxed workspace directory' },
    { key: 'shellEnabled' as const, label: 'Shell', desc: 'Execute shell commands', tip: 'Allows the bot to run shell commands (ls, grep, python, etc.) in the sandboxed workspace' },
    { key: 'braveSearchEnabled' as const, label: 'Brave Search', desc: 'Web search via Brave API', tip: 'Enables web search using Brave Search API. Requires a valid API key (free tier: 2000 queries/month)' },
    { key: 'skillManagementEnabled' as const, label: 'Skill Management', desc: 'Create/edit skills', tip: 'Allows the LLM to create, list, and delete skill definitions programmatically' },
    { key: 'skillTransitionEnabled' as const, label: 'Skill Transition', desc: 'LLM-initiated skill switching', tip: 'Allows the LLM to transition between skills during a conversation pipeline' },
    { key: 'tierEnabled' as const, label: 'Tier Tool', desc: 'LLM-initiated tier switching', tip: 'Allows the LLM to upgrade/downgrade model tier within a session' },
    { key: 'goalManagementEnabled' as const, label: 'Goal Management', desc: 'Auto mode goal management', tip: 'Allows the LLM to create, update, and complete goals in autonomous mode' },
  ];

  const keyByMode: Partial<Record<ToolsMode, typeof tools[number]['key']>> = {
    filesystem: 'filesystemEnabled',
    shell: 'shellEnabled',
    brave: 'braveSearchEnabled',
    skills: 'skillManagementEnabled',
    skillTransition: 'skillTransitionEnabled',
    tier: 'tierEnabled',
    goals: 'goalManagementEnabled',
  };

  const selectedToolKey = keyByMode[mode];
  const selectedTool = selectedToolKey != null ? tools.find((t) => t.key === selectedToolKey) : null;

  const showToolToggles = mode === 'all';
  const showBraveApiKey = mode === 'all' || mode === 'brave';
  const showImap = mode === 'all' || mode === 'email';
  const showSmtp = mode === 'all' || mode === 'email';
  const showBrowserInfo = mode === 'browser';
  const showAutomationGroup = mode === 'automation';
  const showInlineEnableToggles = mode === 'all';
  const automationTools = tools.filter((t) =>
    t.key === 'skillManagementEnabled' || t.key === 'skillTransitionEnabled' || t.key === 'tierEnabled');

  const handleSave = async (): Promise<void> => {
    await updateTools.mutateAsync(form);
    toast.success('Tools settings saved');
  };

  const updateImap = (partial: Partial<ImapConfig>): void => {
    setForm({ ...form, imap: { ...form.imap, ...partial } });
  };

  const updateSmtp = (partial: Partial<SmtpConfig>): void => {
    setForm({ ...form, smtp: { ...form.smtp, ...partial } });
  };

  return (
    <>
      {showToolToggles && (
        <Card className="settings-card mb-3"><Card.Body><Card.Title className="h6 mb-3">Tool Toggles</Card.Title><div className="mb-0">
          {tools.map(({ key, label, desc, tip }) => (
            <div key={key} className="d-flex align-items-start py-2 border-bottom"><Form.Check type="switch" checked={form[key] ?? true} onChange={(e) => setForm({ ...form, [key]: e.target.checked })} className="me-3" /><div><div className="fw-medium small">{label} <Tip text={tip} /></div><div className="meta-text">{desc}</div></div></div>
          ))}
        </div></Card.Body></Card>
      )}

      {!showToolToggles && selectedTool != null && mode !== 'brave' && (
        <Card className="settings-card mb-3"><Card.Body><Card.Title className="h6 mb-3">{selectedTool.label}</Card.Title><div className="mb-2"><Badge bg={(form[selectedTool.key] ?? true) ? 'success' : 'secondary'}>{(form[selectedTool.key] ?? true) ? 'Enabled' : 'Disabled'}</Badge></div><div className="meta-text mt-2">{selectedTool.desc}</div><div className="meta-text mt-2">Enable/disable is managed in the Automation Tools card.</div></Card.Body></Card>
      )}

      {mode === 'brave' && (
        <Card className="settings-card mb-3"><Card.Body><Card.Title className="h6 mb-3">Brave Search</Card.Title><Form.Check type="switch" label={<>Enable Brave Search <Tip text="Enable Brave as active browser search provider" /></>} checked={form.braveSearchEnabled ?? true} onChange={(e) => setForm({ ...form, braveSearchEnabled: e.target.checked })} className="mb-3" /><Form.Group><Form.Label className="small fw-medium">Brave Search API Key <Tip text="Get your free API key at brave.com/search/api" /></Form.Label><Form.Control size="sm" type="password" value={form.braveSearchApiKey ?? ''} onChange={(e) => setForm({ ...form, braveSearchApiKey: toNullableString(e.target.value) })} placeholder="BSA-..." /></Form.Group></Card.Body></Card>
      )}

      {showAutomationGroup && (
        <Card className="settings-card mb-3"><Card.Body><Card.Title className="h6 mb-3">Automation Tools</Card.Title><div className="small text-body-secondary mb-3">All tool enable/disable flags are managed here.</div>
          <div className="d-flex align-items-start py-2 border-bottom"><Form.Check type="switch" checked={form.filesystemEnabled ?? true} onChange={(e) => setForm({ ...form, filesystemEnabled: e.target.checked })} className="me-3" /><div><div className="fw-medium small">Filesystem <Tip text="Read/write files in sandboxed workspace" /></div><div className="meta-text">Enable filesystem tool access.</div></div></div>
          <div className="d-flex align-items-start py-2 border-bottom"><Form.Check type="switch" checked={form.shellEnabled ?? true} onChange={(e) => setForm({ ...form, shellEnabled: e.target.checked })} className="me-3" /><div><div className="fw-medium small">Shell <Tip text="Execute shell commands in sandbox" /></div><div className="meta-text">Enable shell tool access.</div></div></div>
          {automationTools.map(({ key, label, desc, tip }) => (<div key={key} className="d-flex align-items-start py-2 border-bottom"><Form.Check type="switch" checked={form[key] ?? true} onChange={(e) => setForm({ ...form, [key]: e.target.checked })} className="me-3" /><div><div className="fw-medium small">{label} <Tip text={tip} /></div><div className="meta-text">{desc}</div></div></div>))}
          <div className="d-flex align-items-start py-2 border-bottom"><Form.Check type="switch" checked={form.goalManagementEnabled ?? true} onChange={(e) => setForm({ ...form, goalManagementEnabled: e.target.checked })} className="me-3" /><div><div className="fw-medium small">Goal Management <Tip text="Auto mode goal management operations" /></div><div className="meta-text">Enable goal management tool.</div></div></div>
          <div className="d-flex align-items-start py-2 border-bottom"><Form.Check type="switch" checked={form.imap?.enabled ?? false} onChange={(e) => setForm({ ...form, imap: { ...form.imap, enabled: e.target.checked } })} className="me-3" /><div><div className="fw-medium small">IMAP <Tip text="Email reading integration" /></div><div className="meta-text">Enable IMAP settings and tool operations.</div></div></div>
          <div className="d-flex align-items-start py-2"><Form.Check type="switch" checked={form.smtp?.enabled ?? false} onChange={(e) => setForm({ ...form, smtp: { ...form.smtp, enabled: e.target.checked } })} className="me-3" /><div><div className="fw-medium small">SMTP <Tip text="Email sending integration" /></div><div className="meta-text">Enable SMTP settings and tool operations.</div></div></div>
        </Card.Body></Card>
      )}

      {showBrowserInfo && (
        <Card className="settings-card mb-3"><Card.Body><Card.Title className="h6 mb-2">Browser Tool</Card.Title><Row className="g-3"><Col md={6}><Form.Group><Form.Label className="small fw-medium">Browser Engine <Tip text="Browser engine implementation." /></Form.Label><Form.Select size="sm" value={form.browserType ?? 'playwright'} onChange={(e) => setForm({ ...form, browserType: e.target.value })}><option value="playwright">Playwright</option></Form.Select></Form.Group></Col><Col md={6}><Form.Group><Form.Label className="small fw-medium">Browser API Provider <Tip text="Active provider for browser/search tools." /></Form.Label><Form.Select size="sm" value={form.browserApiProvider ?? 'brave'} onChange={(e) => setForm({ ...form, browserApiProvider: e.target.value })}><option value="brave">Brave</option></Form.Select></Form.Group></Col><Col md={6}><Form.Group><Form.Label className="small fw-medium">Timeout (ms) <Tip text="Page operation timeout in milliseconds." /></Form.Label><Form.Control size="sm" type="number" min={1000} max={120000} step={500} value={form.browserTimeout ?? 30000} onChange={(e) => setForm({ ...form, browserTimeout: Number(e.target.value) })} /></Form.Group></Col><Col md={6}><Form.Check type="switch" label={<>Headless Browser <Tip text="Run browser automation in headless mode" /></>} checked={form.browserHeadless ?? true} onChange={(e) => setForm({ ...form, browserHeadless: e.target.checked })} className="mt-md-4 mt-2" /></Col><Col md={12}><Form.Group><Form.Label className="small fw-medium">User-Agent <Tip text="User-Agent string used for browser sessions." /></Form.Label><Form.Control size="sm" value={form.browserUserAgent ?? ''} onChange={(e) => setForm({ ...form, browserUserAgent: toNullableString(e.target.value) })} /></Form.Group></Col></Row><div className="mt-3 pt-3 border-top"><div className="d-flex align-items-center gap-2 mb-2"><Button variant="secondary" size="sm" onClick={() => browserHealthPing.mutate()} disabled={browserHealthPing.isPending}>{browserHealthPing.isPending ? 'Pinging...' : 'Ping Browser'}</Button>{browserHealthPing.data != null && (<Badge bg={browserHealthPing.data.ok ? 'success' : 'danger'}>{browserHealthPing.data.ok ? 'Healthy' : 'Failed'}</Badge>)}</div>{browserHealthPing.data != null && (<div className="meta-text">{browserHealthPing.data.message}</div>)}</div></Card.Body></Card>
      )}

      {showBraveApiKey && mode !== 'brave' && (form.braveSearchEnabled ?? false) && (
        <Card className="settings-card mb-3"><Card.Body><Card.Title className="h6 mb-3">Brave Search Credentials</Card.Title><Form.Group><Form.Label className="small fw-medium">Brave Search API Key <Tip text="Get your free API key at brave.com/search/api" /></Form.Label><Form.Control size="sm" type="password" value={form.braveSearchApiKey ?? ''} onChange={(e) => setForm({ ...form, braveSearchApiKey: toNullableString(e.target.value) })} placeholder="BSA-..." /></Form.Group></Card.Body></Card>
      )}

      {showImap && <Card className="settings-card mb-3"><Card.Body><Card.Title className="h6 mb-3">IMAP (Email Reading) <Tip text="Read emails from an IMAP mailbox. The bot can search, read, and list emails." /></Card.Title>{!showInlineEnableToggles && (<div className="mb-3"><Badge bg={(form.imap?.enabled ?? false) ? 'success' : 'secondary'}>{(form.imap?.enabled ?? false) ? 'Enabled' : 'Disabled'}</Badge><div className="meta-text mt-2">Enable/disable is managed in the Automation Tools card.</div></div>)}{showInlineEnableToggles && (<Form.Check type="switch" label="Enable IMAP" checked={form.imap?.enabled ?? false} onChange={(e) => updateImap({ enabled: e.target.checked })} className="mb-3" />)}{form.imap?.enabled === true && (<><Row className="g-3 mb-3"><Col md={6}><Form.Group><Form.Label className="small fw-medium">Host <Tip text="IMAP server hostname (e.g. imap.gmail.com)" /></Form.Label><Form.Control size="sm" value={form.imap?.host ?? ''} onChange={(e) => updateImap({ host: toNullableString(e.target.value) })} placeholder="imap.gmail.com" /></Form.Group></Col><Col md={3}><Form.Group><Form.Label className="small fw-medium">Port <Tip text="IMAP port (993 for SSL, 143 for plain/STARTTLS)" /></Form.Label><Form.Control size="sm" type="number" value={form.imap?.port ?? 993} onChange={(e) => updateImap({ port: toNullableInt(e.target.value) })} /></Form.Group></Col><Col md={3}><Form.Group><Form.Label className="small fw-medium">Security <Tip text="Connection security: ssl (port 993), starttls (port 143), or none" /></Form.Label><Form.Select size="sm" value={form.imap?.security ?? 'ssl'} onChange={(e) => updateImap({ security: e.target.value })}><option value="ssl">SSL</option><option value="starttls">STARTTLS</option><option value="none">None</option></Form.Select></Form.Group></Col></Row><Row className="g-3 mb-3"><Col md={6}><Form.Group><Form.Label className="small fw-medium">Username</Form.Label><Form.Control size="sm" value={form.imap?.username ?? ''} onChange={(e) => updateImap({ username: toNullableString(e.target.value) })} placeholder="user@example.com" /></Form.Group></Col><Col md={6}><Form.Group><Form.Label className="small fw-medium">Password <Tip text="For Gmail, use an App Password (not your regular password)" /></Form.Label><Form.Control size="sm" type="password" value={form.imap?.password ?? ''} onChange={(e) => updateImap({ password: toNullableString(e.target.value) })} /></Form.Group></Col></Row><Row className="g-3"><Col md={4}><Form.Group><Form.Label className="small fw-medium">Max Body Length <Tip text="Maximum number of characters to read from email body" /></Form.Label><Form.Control size="sm" type="number" value={form.imap?.maxBodyLength ?? 50000} onChange={(e) => updateImap({ maxBodyLength: toNullableInt(e.target.value) })} /></Form.Group></Col><Col md={4}><Form.Group><Form.Label className="small fw-medium">Default Message Limit <Tip text="Max emails returned per listing request" /></Form.Label><Form.Control size="sm" type="number" value={form.imap?.defaultMessageLimit ?? 20} onChange={(e) => updateImap({ defaultMessageLimit: toNullableInt(e.target.value) })} /></Form.Group></Col><Col md={4}><Form.Group><Form.Label className="small fw-medium">SSL Trust <Tip text="SSL certificate trust configuration. Leave blank for default, or set to '*' to trust all." /></Form.Label><Form.Control size="sm" value={form.imap?.sslTrust ?? ''} onChange={(e) => updateImap({ sslTrust: toNullableString(e.target.value) })} placeholder="*" /></Form.Group></Col></Row></>)} </Card.Body></Card>}

      {showSmtp && <Card className="settings-card mb-3"><Card.Body><Card.Title className="h6 mb-3">SMTP (Email Sending) <Tip text="Send emails via SMTP. The bot can compose and send emails on your behalf." /></Card.Title>{!showInlineEnableToggles && (<div className="mb-3"><Badge bg={(form.smtp?.enabled ?? false) ? 'success' : 'secondary'}>{(form.smtp?.enabled ?? false) ? 'Enabled' : 'Disabled'}</Badge><div className="meta-text mt-2">Enable/disable is managed in the Automation Tools card.</div></div>)}{showInlineEnableToggles && (<Form.Check type="switch" label="Enable SMTP" checked={form.smtp?.enabled ?? false} onChange={(e) => updateSmtp({ enabled: e.target.checked })} className="mb-3" />)}{form.smtp?.enabled === true && (<><Row className="g-3 mb-3"><Col md={6}><Form.Group><Form.Label className="small fw-medium">Host <Tip text="SMTP server hostname (e.g. smtp.gmail.com)" /></Form.Label><Form.Control size="sm" value={form.smtp?.host ?? ''} onChange={(e) => updateSmtp({ host: toNullableString(e.target.value) })} placeholder="smtp.gmail.com" /></Form.Group></Col><Col md={3}><Form.Group><Form.Label className="small fw-medium">Port <Tip text="SMTP port (587 for STARTTLS, 465 for SSL, 25 for plain)" /></Form.Label><Form.Control size="sm" type="number" value={form.smtp?.port ?? 587} onChange={(e) => updateSmtp({ port: toNullableInt(e.target.value) })} /></Form.Group></Col><Col md={3}><Form.Group><Form.Label className="small fw-medium">Security <Tip text="Connection security: starttls (port 587), ssl (port 465), or none" /></Form.Label><Form.Select size="sm" value={form.smtp?.security ?? 'starttls'} onChange={(e) => updateSmtp({ security: e.target.value })}><option value="starttls">STARTTLS</option><option value="ssl">SSL</option><option value="none">None</option></Form.Select></Form.Group></Col></Row><Row className="g-3 mb-3"><Col md={6}><Form.Group><Form.Label className="small fw-medium">Username</Form.Label><Form.Control size="sm" value={form.smtp?.username ?? ''} onChange={(e) => updateSmtp({ username: toNullableString(e.target.value) })} placeholder="user@example.com" /></Form.Group></Col><Col md={6}><Form.Group><Form.Label className="small fw-medium">Password <Tip text="For Gmail, use an App Password (not your regular password)" /></Form.Label><Form.Control size="sm" type="password" value={form.smtp?.password ?? ''} onChange={(e) => updateSmtp({ password: toNullableString(e.target.value) })} /></Form.Group></Col></Row><Row className="g-3"><Col md={6}><Form.Group><Form.Label className="small fw-medium">SSL Trust <Tip text="SSL certificate trust configuration. Leave blank for default." /></Form.Label><Form.Control size="sm" value={form.smtp?.sslTrust ?? ''} onChange={(e) => updateSmtp({ sslTrust: toNullableString(e.target.value) })} placeholder="*" /></Form.Group></Col></Row></>)} </Card.Body></Card>}

      {mode !== 'browser' && (
        <div className="d-flex align-items-center gap-2"><Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isToolsDirty || updateTools.isPending}>{updateTools.isPending ? 'Saving...' : 'Save Tool Settings'}</Button><SaveStateHint isDirty={isToolsDirty} /></div>
      )}
    </>
  );
}
