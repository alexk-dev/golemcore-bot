import * as React from 'react';
import { createPortal } from 'react-dom';
import { FiX } from 'react-icons/fi';
import { cn } from '../../lib/utils';

interface OverlayContextValue {
  onHide?: () => void;
}

const OverlayContext = React.createContext<OverlayContextValue>({});

function useBodyLock(active: boolean): void {
  React.useEffect(() => {
    if (!active) {
      return;
    }

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [active]);
}

function useEscape(active: boolean, onHide?: () => void): void {
  React.useEffect(() => {
    if (!active || onHide == null) {
      return;
    }

    const handleHide = onHide;

    function handleKeyDown(event: KeyboardEvent): void {
      if (event.key === 'Escape') {
        handleHide();
      }
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [active, onHide]);
}

interface OverlayHeaderProps extends React.HTMLAttributes<HTMLDivElement> {
  closeButton?: boolean;
}

function OverlayHeader({ className, closeButton, children, ...props }: OverlayHeaderProps): React.ReactElement {
  const { onHide } = React.useContext(OverlayContext);
  return (
    <div className={cn('flex items-center justify-between gap-4 border-b border-border/80 px-5 py-4', className)} {...props}>
      <div className="min-w-0 flex-1">{children}</div>
      {closeButton && (
        <button
          type="button"
          className="inline-flex h-9 w-9 items-center justify-center rounded-full border border-border/80 bg-card/80 text-muted-foreground transition-colors hover:text-foreground"
          onClick={onHide}
          aria-label="Close"
        >
          <FiX size={16} />
        </button>
      )}
    </div>
  );
}

function OverlayBody({ className, ...props }: React.HTMLAttributes<HTMLDivElement>): React.ReactElement {
  return <div className={cn('px-5 py-4', className)} {...props} />;
}

function OverlayFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>): React.ReactElement {
  return <div className={cn('flex flex-wrap justify-end gap-2 border-t border-border/80 px-5 py-4', className)} {...props} />;
}

function OverlayTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>): React.ReactElement {
  return <h2 className={cn('text-lg font-semibold tracking-tight', className)} {...props} />;
}

interface ModalComponent extends React.FC<ModalProps> {
  Header: typeof OverlayHeader;
  Body: typeof OverlayBody;
  Footer: typeof OverlayFooter;
  Title: typeof OverlayTitle;
}

interface ModalProps {
  show?: boolean;
  onHide?: () => void;
  centered?: boolean;
  size?: 'sm' | 'lg' | 'xl';
  className?: string;
  children?: React.ReactNode;
}

function modalSizeClass(size?: ModalProps['size']): string {
  if (size === 'sm') {
    return 'max-w-md';
  }
  if (size === 'lg') {
    return 'max-w-4xl';
  }
  if (size === 'xl') {
    return 'max-w-6xl';
  }
  return 'max-w-2xl';
}

const BaseModal: React.FC<ModalProps> = ({ show = false, onHide, centered = false, size, className, children }) => {
  useBodyLock(show);
  useEscape(show, onHide);

  if (!show) {
    return null;
  }

  return createPortal(
    <OverlayContext.Provider value={{ onHide }}>
      <div
        className="fixed inset-0 z-[1100] flex items-start justify-center overflow-y-auto bg-slate-950/55 px-4 py-8 backdrop-blur-sm"
        onMouseDown={(event) => {
          if (event.target === event.currentTarget) {
            onHide?.();
          }
        }}
      >
        <div
          className={cn(
            'relative w-full rounded-[1.5rem] border border-border/80 bg-card/95 text-card-foreground shadow-[0_28px_80px_rgba(2,6,23,0.35)]',
            centered && 'my-auto',
            modalSizeClass(size),
            className
          )}
          onMouseDown={(event) => event.stopPropagation()}
        >
          {children}
        </div>
      </div>
    </OverlayContext.Provider>,
    document.body
  );
};

const Modal = BaseModal as ModalComponent;
Modal.Header = OverlayHeader;
Modal.Body = OverlayBody;
Modal.Footer = OverlayFooter;
Modal.Title = OverlayTitle;

interface OffcanvasProps {
  show?: boolean;
  onHide?: () => void;
  placement?: 'start' | 'end';
  className?: string;
  children?: React.ReactNode;
}

interface OffcanvasComponent extends React.FC<OffcanvasProps> {
  Header: typeof OverlayHeader;
  Body: typeof OverlayBody;
  Title: typeof OverlayTitle;
}

const BaseOffcanvas: React.FC<OffcanvasProps> = ({
  show = false,
  onHide,
  placement = 'end',
  className,
  children,
}) => {
  useBodyLock(show);
  useEscape(show, onHide);

  if (!show) {
    return null;
  }

  const sideClassName = placement === 'start' ? 'justify-start' : 'justify-end';
  const panelClassName = placement === 'start' ? 'translate-x-0' : 'translate-x-0';

  return createPortal(
    <OverlayContext.Provider value={{ onHide }}>
      <div
        className={cn('fixed inset-0 z-[1080] flex bg-slate-950/45 backdrop-blur-sm', sideClassName)}
        onMouseDown={(event) => {
          if (event.target === event.currentTarget) {
            onHide?.();
          }
        }}
      >
        <div
          className={cn(
            'h-full w-full max-w-[28rem] border-l border-border/80 bg-card/95 text-card-foreground shadow-[0_20px_80px_rgba(2,6,23,0.38)]',
            panelClassName,
            className
          )}
          onMouseDown={(event) => event.stopPropagation()}
        >
          {children}
        </div>
      </div>
    </OverlayContext.Provider>,
    document.body
  );
};

const Offcanvas = BaseOffcanvas as OffcanvasComponent;
Offcanvas.Header = OverlayHeader;
Offcanvas.Body = OverlayBody;
Offcanvas.Title = OverlayTitle;

export { Modal, Offcanvas };
