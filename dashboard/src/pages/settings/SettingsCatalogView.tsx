import type { ReactElement } from 'react';
import { Badge, Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import { FiSearch, FiX } from 'react-icons/fi';

import type { SettingsSectionMeta } from './settingsCatalog';

export interface CatalogCardItem {
  key: string;
  routeKey: string;
  title: string;
  description: string;
  icon: SettingsSectionMeta['icon'];
  badgeLabel?: string;
  badgeVariant?: string;
  metaText?: string;
}

export interface CatalogBlockView {
  key: string;
  title: string;
  description: string;
  items: CatalogCardItem[];
}

interface SettingsCatalogViewProps {
  catalogSearch: string;
  filteredCatalogBlocks: CatalogBlockView[];
  onSearchChange: (value: string) => void;
  onClearSearch: () => void;
  onOpenSection: (routeKey: string) => void;
}

export function SettingsCatalogView({
  catalogSearch,
  filteredCatalogBlocks,
  onSearchChange,
  onClearSearch,
  onOpenSection,
}: SettingsCatalogViewProps): ReactElement {
  return (
    <div>
      <div className="page-header"><h4>Settings</h4><p className="text-body-secondary mb-0">Select a settings category</p></div>
      <Card className="settings-card mb-4"><Card.Body><SettingsCatalogSearch catalogSearch={catalogSearch} onSearchChange={onSearchChange} onClearSearch={onClearSearch} /></Card.Body></Card>
      {filteredCatalogBlocks.length === 0 ? (
        <Card className="settings-card"><Card.Body><h2 className="h6 mb-2">Nothing found</h2><p className="text-body-secondary small mb-0">No settings match `{catalogSearch}`. Try another name or clear the search.</p></Card.Body></Card>
      ) : filteredCatalogBlocks.map((block) => <SettingsCatalogBlock key={block.key} block={block} onOpenSection={onOpenSection} />)}
    </div>
  );
}

function SettingsCatalogSearch({
  catalogSearch,
  onSearchChange,
  onClearSearch,
}: Pick<SettingsCatalogViewProps, 'catalogSearch' | 'onSearchChange' | 'onClearSearch'>): ReactElement {
  return (
    <Form.Group controlId="settings-catalog-search" className="mb-0">
      <Form.Label className="small fw-medium">Search settings</Form.Label>
      <InputGroup>
        <InputGroup.Text aria-hidden="true"><FiSearch size={16} /></InputGroup.Text>
        <Form.Control type="search" placeholder="Search by name" value={catalogSearch} onChange={(event) => onSearchChange(event.target.value)} />
        {catalogSearch.trim().length > 0 && (
          <Button type="button" variant="secondary" onClick={onClearSearch}><FiX size={16} className="me-1" />Clear</Button>
        )}
      </InputGroup>
      <Form.Text className="text-muted">Start typing to quickly find the setting you need.</Form.Text>
    </Form.Group>
  );
}

function SettingsCatalogBlock({ block, onOpenSection }: { block: CatalogBlockView; onOpenSection: (routeKey: string) => void }): ReactElement {
  return (
    <div className="mb-4">
      <div className="mb-2"><h2 className="h6 mb-1">{block.title}</h2><p className="text-body-secondary small mb-0">{block.description}</p></div>
      <Row className="g-3">
        {block.items.map((item) => <SettingsCatalogCard key={item.key} item={item} onOpenSection={onOpenSection} />)}
      </Row>
    </div>
  );
}

function SettingsCatalogCard({ item, onOpenSection }: { item: CatalogCardItem; onOpenSection: (routeKey: string) => void }): ReactElement {
  return (
    <Col sm={6} lg={4} xl={3}>
      <Card className="settings-card h-100"><Card.Body className="d-flex flex-column">
        <div className="d-flex align-items-start justify-content-between gap-2 mb-2">
          <h3 className="h6 mb-0 settings-catalog-title"><span className="text-primary"><item.icon size={18} /></span><span>{item.title}</span></h3>
          {item.badgeLabel != null && item.badgeVariant != null && <Badge bg={item.badgeVariant}>{item.badgeLabel}</Badge>}
        </div>
        <Card.Text className="text-body-secondary small mb-3">{item.description}</Card.Text>
        {item.metaText != null && <div className="small text-body-secondary mb-3">{item.metaText}</div>}
        <div className="mt-auto"><Button type="button" size="sm" variant="primary" onClick={() => onOpenSection(item.routeKey)}>Open</Button></div>
      </Card.Body></Card>
    </Col>
  );
}
