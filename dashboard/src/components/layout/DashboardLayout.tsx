import HarnessShell from './harness/HarnessShell';

interface DashboardLayoutProps {
  children: React.ReactNode;
}

export default function DashboardLayout({ children }: DashboardLayoutProps) {
  return <HarnessShell>{children}</HarnessShell>;
}
