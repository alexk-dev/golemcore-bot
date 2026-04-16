import { Suspense, lazy, useEffect, type ComponentType, type LazyExoticComponent, type ReactElement } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './store/authStore';
import DashboardLayout from './components/layout/DashboardLayout';
import { TelemetryBootstrap } from './components/telemetry/TelemetryBootstrap';
import { loadCodeEditor } from './components/ide/LazyCodeEditor';

interface PageModule {
  default: ComponentType;
}

type PageLoader = () => Promise<PageModule>;
type WarmupLoader = () => Promise<unknown>;
type LazyPageComponent = LazyExoticComponent<ComponentType>;

const ROUTE_WARMUP_IDLE_TIMEOUT_MS = 5_000;
const ROUTE_WARMUP_STEP_DELAY_MS = 1_500;

const loadLoginPage: PageLoader = () => import('./pages/LoginPage');
const loadChatPage: PageLoader = () => import('./pages/ChatPage');
const loadSetupPage: PageLoader = () => import('./pages/SetupPage');
const loadAnalyticsPage: PageLoader = () => import('./pages/AnalyticsPage');
const loadSelfEvolvingPage: PageLoader = () => import('./pages/SelfEvolvingPage');
const loadSettingsPage: PageLoader = () => import('./pages/SettingsPage');
const loadTierFallbacksPage: PageLoader = () => import('./pages/TierFallbacksPage');
const loadPromptsPage: PageLoader = () => import('./pages/PromptsPage');
const loadSkillsPage: PageLoader = () => import('./pages/SkillsPage');
const loadSessionsPage: PageLoader = () => import('./pages/SessionsPage');
const loadSessionDetailsPage: PageLoader = () => import('./pages/SessionDetailsPage');
const loadDiagnosticsPage: PageLoader = () => import('./pages/DiagnosticsPage');
const loadIdePage: PageLoader = () => import('./pages/IdePage');
const loadLogsPage: PageLoader = () => import('./pages/LogsPage');
const loadGoalsPage: PageLoader = () => import('./pages/GoalsPage');
const loadSchedulerPage: PageLoader = () => import('./pages/SchedulerPage');
const loadWebhooksPage: PageLoader = () => import('./pages/WebhooksPage');

const LoginPage: LazyPageComponent = lazy(loadLoginPage);
const ChatPage: LazyPageComponent = lazy(loadChatPage);
const SetupPage: LazyPageComponent = lazy(loadSetupPage);
const AnalyticsPage: LazyPageComponent = lazy(loadAnalyticsPage);
const SelfEvolvingPage: LazyPageComponent = lazy(loadSelfEvolvingPage);
const SettingsPage: LazyPageComponent = lazy(loadSettingsPage);
const TierFallbacksPage: LazyPageComponent = lazy(loadTierFallbacksPage);
const PromptsPage: LazyPageComponent = lazy(loadPromptsPage);
const SkillsPage: LazyPageComponent = lazy(loadSkillsPage);
const SessionsPage: LazyPageComponent = lazy(loadSessionsPage);
const SessionDetailsPage: LazyPageComponent = lazy(loadSessionDetailsPage);
const DiagnosticsPage: LazyPageComponent = lazy(loadDiagnosticsPage);
const IdePage: LazyPageComponent = lazy(loadIdePage);
const LogsPage: LazyPageComponent = lazy(loadLogsPage);
const GoalsPage: LazyPageComponent = lazy(loadGoalsPage);
const SchedulerPage: LazyPageComponent = lazy(loadSchedulerPage);
const WebhooksPage: LazyPageComponent = lazy(loadWebhooksPage);

const SPECIALIZED_ROUTE_LOADERS: WarmupLoader[] = [
  loadIdePage,
  loadCodeEditor,
  loadAnalyticsPage,
];

function RouteFallback(): ReactElement {
  return <div className="dashboard-main text-secondary">Loading...</div>;
}

function ProtectedRoute({ children }: { children: React.ReactNode }): ReactElement {
  const token = useAuthStore((s) => s.accessToken);
  if (!token) {return <Navigate to="/login" replace />;}
  return <>{children}</>;
}

function HiveSsoCallbackRoute(): React.ReactElement {
  return <Navigate to={`/login${window.location.search}`} replace />;
}

function handleRouteWarmupError(error: unknown): void {
  console.error('Failed to warm dashboard route chunk.', error);
}

function scheduleIdleTask(callback: () => void): () => void {
  if (typeof window.requestIdleCallback === 'function') {
    const idleId = window.requestIdleCallback(callback, { timeout: ROUTE_WARMUP_IDLE_TIMEOUT_MS });
    return () => window.cancelIdleCallback(idleId);
  }

  const timeoutId = window.setTimeout(callback, ROUTE_WARMUP_IDLE_TIMEOUT_MS);
  return () => window.clearTimeout(timeoutId);
}

function scheduleSpecializedRouteWarmup(): () => void {
  const timeoutIds: number[] = [];
  const cancelIdleTask = scheduleIdleTask(() => {
    SPECIALIZED_ROUTE_LOADERS.forEach((loader, index) => {
      const timeoutId = window.setTimeout(() => {
        void loader().catch(handleRouteWarmupError);
      }, index * ROUTE_WARMUP_STEP_DELAY_MS);
      timeoutIds.push(timeoutId);
    });
  });

  return () => {
    cancelIdleTask();
    timeoutIds.forEach((timeoutId) => window.clearTimeout(timeoutId));
  };
}

function DashboardRouteShell(): ReactElement {
  useEffect(() => {
    // Warm specialized chunks after the authenticated shell has painted.
    return scheduleSpecializedRouteWarmup();
  }, []);

  return (
    <DashboardLayout>
      <TelemetryBootstrap>
        <Suspense fallback={<RouteFallback />}>
          <Routes>
            <Route path="/" element={<ChatPage />} />
            <Route path="/chat" element={<ChatPage />} />
            <Route path="/setup" element={<SetupPage />} />
            <Route path="/analytics" element={<AnalyticsPage />} />
            <Route path="/self-evolving" element={<SelfEvolvingPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/settings/models/:tier" element={<TierFallbacksPage />} />
            <Route path="/settings/:section" element={<SettingsPage />} />
            <Route path="/prompts" element={<PromptsPage />} />
            <Route path="/skills" element={<SkillsPage />} />
            <Route path="/sessions" element={<SessionsPage />} />
            <Route path="/sessions/:sessionId" element={<SessionDetailsPage />} />
            <Route path="/sessions/:sessionId/:tab" element={<SessionDetailsPage />} />
            <Route path="/goals" element={<GoalsPage />} />
            <Route path="/diagnostics" element={<DiagnosticsPage />} />
            <Route path="/ide" element={<IdePage />} />
            <Route path="/logs" element={<LogsPage />} />
            <Route path="/scheduler" element={<SchedulerPage />} />
            <Route path="/webhooks" element={<WebhooksPage />} />
          </Routes>
        </Suspense>
      </TelemetryBootstrap>
    </DashboardLayout>
  );
}

export default function App(): ReactElement {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <Suspense fallback={<RouteFallback />}>
            <LoginPage />
          </Suspense>
        }
      />
      <Route
        path="/api/auth/hive/callback"
        element={<HiveSsoCallbackRoute />}
      />
      <Route
        path="/*"
        element={
          <ProtectedRoute>
            <DashboardRouteShell />
          </ProtectedRoute>
        }
      />
    </Routes>
  );
}
