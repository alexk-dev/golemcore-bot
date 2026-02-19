import { useLocation } from 'react-router-dom';
import Sidebar from './Sidebar';
import Topbar from './Topbar';

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const { pathname } = useLocation();
  const isChat = pathname === '/' || pathname.startsWith('/chat');

  return (
    <div className="d-flex app-shell overflow-hidden">
      <a href="#main-content" className="skip-link">Skip to main content</a>
      <Sidebar />
      <div className="flex-grow-1 d-flex flex-column h-100 overflow-hidden">
        <Topbar />
        <main
          id="main-content"
          className={isChat ? 'flex-grow-1 d-flex overflow-hidden' : 'dashboard-main flex-grow-1 overflow-auto'}
        >
          {children}
        </main>
      </div>
    </div>
  );
}
