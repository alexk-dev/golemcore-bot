import type { ReactElement } from 'react';
import { Card, Button } from '../../components/ui/tailwind-components';

import { DocsLinkAnchor } from '../../components/common/DocsLinkAnchor';
import { getDocLinks, type DocId, type DocLink } from '../../lib/docsLinks';

export interface SettingsIntentCard {
  key: string;
  title: string;
  description: string;
  routeKey: string;
  docs: DocLink[];
}

export interface SettingsIntentCardsProps {
  onOpenSection: (routeKey: string) => void;
}

function buildIntentCards(): SettingsIntentCard[] {
  const cardDefinitions: Array<{
    key: string;
    title: string;
    description: string;
    routeKey: string;
    docs: DocId[];
  }> = [
    {
      key: 'connect-model',
      title: 'Connect my first model',
      description: 'Add a provider key and make the runtime ready for its first chat turn.',
      routeKey: 'llm-providers',
      docs: ['quickstart', 'configuration'],
    },
    {
      key: 'route-models',
      title: 'Configure routing and fallbacks',
      description: 'Choose which concrete models power routing, coding, and deep analysis tiers.',
      routeKey: 'models',
      docs: ['model-routing', 'configuration'],
    },
    {
      key: 'tune-memory',
      title: 'Tune long-term memory',
      description: 'Control recall depth, budgets, and presets before memory becomes noisy.',
      routeKey: 'memory',
      docs: ['memory', 'memory-tuning'],
    },
    {
      key: 'install-skills',
      title: 'Install skills and MCP tools',
      description: 'Use the marketplace and skill runtime controls to add focused capabilities.',
      routeKey: 'skills',
      docs: ['skills', 'mcp'],
    },
    {
      key: 'enable-auto-mode',
      title: 'Enable autonomous runs',
      description: 'Turn on goals, schedules, and delayed follow-ups for recurring workflows.',
      routeKey: 'auto',
      docs: ['auto-mode', 'delayed-actions'],
    },
  ];

  return cardDefinitions.map((card) => ({
    key: card.key,
    title: card.title,
    description: card.description,
    routeKey: card.routeKey,
    docs: getDocLinks(card.docs),
  }));
}

const SETTINGS_INTENT_CARDS = buildIntentCards();

export function SettingsIntentCards({ onOpenSection }: SettingsIntentCardsProps): ReactElement {
  return (
    <div className="mb-4">
      <div className="mb-2">
        <h2 className="h6 mb-1">What are you trying to do?</h2>
        <p className="text-body-secondary small mb-0">
          Jump straight to the right settings section and the matching documentation guide.
        </p>
      </div>
      <div className="row g-3">
        {SETTINGS_INTENT_CARDS.map((card) => (
          <div key={card.key} className="col-sm-6 col-xl-4">
            <Card className="settings-card h-100">
              <Card.Body className="d-flex flex-column gap-3">
                <div>
                  <h3 className="h6 mb-2">{card.title}</h3>
                  <p className="text-body-secondary small mb-0">{card.description}</p>
                </div>
                <div className="d-flex flex-wrap gap-2 mt-auto">
                  <Button type="button" size="sm" variant="primary" onClick={() => onOpenSection(card.routeKey)}>
                    Open section
                  </Button>
                  {card.docs.map((doc) => (
                    <DocsLinkAnchor key={doc.id} doc={doc} />
                  ))}
                </div>
              </Card.Body>
            </Card>
          </div>
        ))}
      </div>
    </div>
  );
}
