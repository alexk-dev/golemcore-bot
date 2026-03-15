import type { PromptSection, PromptSectionPayload } from '../../api/prompts';

export const PROMPT_NAME_PATTERN = /^[a-z0-9][a-z0-9-]*$/;
export const DEFAULT_PROMPT_ORDER = 100;
const PROMPT_ORDER_STEP = 10;

export interface PromptDraft extends PromptSectionPayload {
  name: string;
  deletable: boolean;
}

export interface PromptReorderRequest {
  name: string;
  section: PromptSectionPayload;
}

export function normalizePromptName(value: string): string {
  return value.trim().toLowerCase();
}

export function isPromptNameValid(value: string): boolean {
  const normalized = normalizePromptName(value);
  return PROMPT_NAME_PATTERN.test(normalized);
}

export function getNextPromptOrder(sections: PromptSection[]): number {
  if (sections.length === 0) {
    return DEFAULT_PROMPT_ORDER;
  }

  return Math.max(...sections.map((section) => section.order)) + PROMPT_ORDER_STEP;
}

export function parsePromptOrder(value: string, fallback: number): number {
  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? fallback : parsed;
}

export function toPromptDraft(section: PromptSection): PromptDraft {
  return {
    name: section.name,
    description: section.description,
    order: section.order,
    enabled: section.enabled,
    deletable: section.deletable,
    content: section.content,
  };
}

export function toPromptPayload(draft: PromptDraft): PromptSectionPayload {
  return {
    description: draft.description,
    order: draft.order,
    enabled: draft.enabled,
    content: draft.content,
  };
}

export function arePromptDraftsEqual(draft: PromptDraft, section: PromptSection): boolean {
  return draft.name === section.name
    && draft.description === section.description
    && draft.order === section.order
    && draft.enabled === section.enabled
    && draft.content === section.content
    && draft.deletable === section.deletable;
}

export function findPriorityConflictName(
  draft: PromptDraft,
  sections: PromptSection[]
): string | null {
  const conflict = sections.find((section) => section.name !== draft.name && section.order === draft.order);
  return conflict?.name ?? null;
}

export function reorderPromptSections(
  sections: PromptSection[],
  sourceName: string,
  targetName: string
): PromptSection[] {
  const sourceIndex = sections.findIndex((section) => section.name === sourceName);
  const targetIndex = sections.findIndex((section) => section.name === targetName);

  if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) {
    return sections;
  }

  const reordered = [...sections];
  const [movedSection] = reordered.splice(sourceIndex, 1);
  reordered.splice(targetIndex, 0, movedSection);

  return reordered.map((section, index) => ({
    ...section,
    order: (index + 1) * PROMPT_ORDER_STEP,
  }));
}

export function buildPromptReorderRequests(
  previousSections: PromptSection[],
  nextSections: PromptSection[]
): PromptReorderRequest[] {
  const previousOrders = new Map(previousSections.map((section) => [section.name, section.order]));
  return nextSections
    .filter((section) => previousOrders.get(section.name) !== section.order)
    .map((section) => ({
      name: section.name,
      section: {
        description: section.description,
        order: section.order,
        enabled: section.enabled,
        content: section.content,
      },
    }));
}
