import { Suspense, lazy } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './store/authStore';
import DashboardLayout from './components/layout/DashboardLayout';
import { TelemetryBootstrap } from './components/telemetry/TelemetryBootstrap';

const LoginPage = lazy(() => import('./pages/LoginPage'));
const ChatPage = lazy(() => import('./pages/ChatPage'));
const SetupPage = lazy(() => import('./pages/SetupPage'));
const AnalyticsPage = lazy(() => import('./pages/AnalyticsPage'));
const SelfEvolvingPage = lazy(() => import('./pages/SelfEvolvingPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const TierFallbacksPage = lazy(() => import('./pages/TierFallbacksPage'));
const PromptsPage = lazy(() => import('./pages/PromptsPage'));
const SkillsPage = lazy(() => import('./pages/SkillsPage'));
const SessionsPage = lazy(() => import('./pages/SessionsPage'));
const SessionDetailsPage = lazy(() => import('./pages/SessionDetailsPage'));
const DiagnosticsPage = lazy(() => import('./pages/DiagnosticsPage'));
const IdePage = lazy(() => import('./pages/IdePage'));
const LogsPage = lazy(() => import('./pages/LogsPage'));
const GoalsPage = lazy(() => import('./pages/GoalsPage'));
const SchedulerPage = lazy(() => import('./pages/SchedulerPage'));
const WebhooksPage = lazy(() => import('./pages/WebhooksPage'));

function RouteFallback() {
  return <div className="dashboard-main text-secondary">Loading...</div>;
}

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.accessToken);
  if (!token) {return <Navigate to="/login" replace />;}
  return <>{children}</>;
}

function HiveSsoCallbackRoute(): React.ReactElement {
  return <Navigate to={`/login${window.location.search}`} replace />;
}

export default function App() {
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
          </ProtectedRoute>
        }
      />
    </Routes>
  );
}
