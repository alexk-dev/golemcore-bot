import { useLocation } from 'react-router-dom';
import { ChatRuntimeController } from '../chat/ChatRuntimeController';
import Sidebar from './Sidebar';
import Topbar from './Topbar';

interface DashboardLayoutProps {
  children: React.ReactNode;
}

export default function DashboardLayout({ children }: DashboardLayoutProps) {
  const { pathname } = useLocation();
  const isChat = pathname === '/' || pathname.startsWith('/chat');
  const isIde = pathname.startsWith('/ide');
  const isWorkspace = pathname.startsWith('/workspace');
  const isShellRoute = isChat || isIde || isWorkspace;

  const mainClassName = isShellRoute
    ? 'dashboard-main-shell flex-grow-1 d-flex overflow-hidden'
    : 'dashboard-main flex-grow-1 overflow-auto';

  return (
    <div className="d-flex app-shell overflow-hidden">
      <a href="#main-content" className="skip-link">Skip to main content</a>
      <Sidebar />
      <div className="dashboard-shell-body flex-grow-1 d-flex flex-column overflow-hidden">
        <ChatRuntimeController />
        <Topbar />
        <main id="main-content" className={mainClassName}>
          {children}
        </main>
      </div>
    </div>
  );
}
