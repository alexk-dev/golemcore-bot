import { useLocation } from 'react-router-dom';
import Sidebar from './Sidebar';
import Topbar from './Topbar';

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const { pathname } = useLocation();
  const isChat = pathname === '/' || pathname.startsWith('/chat');
  const isIde = pathname.startsWith('/ide');

  const mainClassName = isChat || isIde
    ? 'flex-grow-1 d-flex overflow-hidden'
    : 'dashboard-main flex-grow-1 overflow-auto';

  return (
    <div className="d-flex vh-100 overflow-hidden">
      <Sidebar />
      <div className="flex-grow-1 d-flex flex-column h-100 overflow-hidden">
        <Topbar />
        <main className={mainClassName}>
          {children}
        </main>
      </div>
    </div>
  );
}
