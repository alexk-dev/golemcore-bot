import type { ReactElement } from 'react';
import { ListGroup, Modal } from 'react-bootstrap';
import type { QuickOpenItem } from '../../hooks/useIdeQuickOpen';

export interface QuickOpenModalProps {
  show: boolean;
  query: string;
  items: QuickOpenItem[];
  onClose: () => void;
  onPick: (path: string) => void;
}

export function QuickOpenModal({ show, query, items, onClose, onPick }: QuickOpenModalProps): ReactElement {
  return (
    <Modal show={show} onHide={onClose} centered>
      <Modal.Header closeButton>
        <Modal.Title>Quick Open</Modal.Title>
      </Modal.Header>
      <Modal.Body className="p-0">
        <div className="px-3 py-2 border-bottom small text-body-secondary">
          Query: <strong className="text-body">{query.length > 0 ? query : 'all files'}</strong>
        </div>

        <ListGroup variant="flush" className="ide-quick-open-list">
          {items.length === 0 ? (
            <ListGroup.Item className="text-body-secondary">No matching files.</ListGroup.Item>
          ) : (
            items.map((item) => (
              <ListGroup.Item
                key={item.path}
                action
                onClick={() => onPick(item.path)}
                title={item.path}
              >
                <div className="fw-medium">{item.title}</div>
                <div className="small text-body-secondary text-truncate">{item.path}</div>
              </ListGroup.Item>
            ))
          )}
        </ListGroup>
      </Modal.Body>
    </Modal>
  );
}
