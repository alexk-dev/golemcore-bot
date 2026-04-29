import { useEffect, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { ChatRuntimeController } from '../../chat/ChatRuntimeController';
import { useThemeStore } from '../../../store/themeStore';
import { useInspectorStore } from '../../../store/inspectorStore';
import { useCommandPaletteShortcut } from '../../commandPalette/useCommandPaletteShortcut';
import { useSessionExport } from '../../commandPalette/useSessionExport';
import CommandPalette from '../../commandPalette/CommandPalette';
import IconRail from './IconRail';
import { useRailVersionLabel } from './useRailVersionLabel';
import SecondarySidebar from './SecondarySidebar';
import HarnessTopBar from './HarnessTopBar';
import InspectorPanel from './InspectorPanel';

interface HarnessShellProps {
  children: ReactNode;
}

function isShellChromeRoute(pathname: string): boolean {
  if (pathname === '/' || pathname.startsWith('/chat')) {
    return true;
  }
  if (pathname.startsWith('/workspace') || pathname.startsWith('/ide')) {
    return true;
  }
  return false;
}

export default function HarnessShell({ children }: HarnessShellProps) {
  const { pathname } = useLocation();
  const theme = useThemeStore((s) => s.theme);
  const inspectorOpen = useInspectorStore((s) => s.panelOpen);
  const versionLabel = useRailVersionLabel();
  useCommandPaletteShortcut();
  useSessionExport();
  const showInspector = inspectorOpen && (pathname === '/' || pathname.startsWith('/chat'));
  const mainClassName = isShellChromeRoute(pathname)
    ? 'harness-main harness-main--flex'
    : 'harness-main harness-main--scroll';

  // Sync the harness theme attribute with the user's theme preference so
  // the design tokens defined in harness.scss switch alongside the legacy palette.
  useEffect(() => {
    const target = document.documentElement;
    target.setAttribute('data-harness', theme);
    return () => {
      target.removeAttribute('data-harness');
    };
  }, [theme]);

  return (
    <div className="harness-shell" data-harness-theme={theme}>
      <a href="#main-content" className="skip-link">Skip to main content</a>
      <IconRail versionLabel={versionLabel} />
      <SecondarySidebar />
      <div className="harness-body">
        <ChatRuntimeController />
        <HarnessTopBar />
        <main id="main-content" className={mainClassName}>
          {children}
        </main>
      </div>
      {showInspector && <InspectorPanel />}
      <CommandPalette />
    </div>
  );
}
