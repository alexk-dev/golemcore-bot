import Sidebar from './Sidebar';
import Topbar from './Topbar';

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="d-flex">
      <Sidebar />
      <div className="flex-grow-1 d-flex flex-column">
        <Topbar />
        <main className="p-4 flex-grow-1">{children}</main>
      </div>
    </div>
  );
}
