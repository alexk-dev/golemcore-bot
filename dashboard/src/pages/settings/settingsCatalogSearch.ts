export interface SearchableCatalogItem {
  title: string;
}

export interface SearchableCatalogBlock<TItem extends SearchableCatalogItem> {
  items: TItem[];
}

function normalizeSearchText(value: string): string {
  return value
    .toLowerCase()
    .replace(/[_/-]+/g, ' ')
    .replace(/[^a-z0-9\s]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function allowedDistance(length: number): number {
  if (length <= 4) {
    return 1;
  }
  if (length <= 8) {
    return 2;
  }
  return 3;
}

function levenshteinDistance(left: string, right: string): number {
  if (left === right) {
    return 0;
  }
  if (left.length === 0) {
    return right.length;
  }
  if (right.length === 0) {
    return left.length;
  }

  const previous = Array.from({ length: right.length + 1 }, (_, index) => index);
  const current = new Array<number>(right.length + 1);

  for (let leftIndex = 1; leftIndex <= left.length; leftIndex += 1) {
    current[0] = leftIndex;

    for (let rightIndex = 1; rightIndex <= right.length; rightIndex += 1) {
      const substitutionCost = left[leftIndex - 1] === right[rightIndex - 1] ? 0 : 1;
      current[rightIndex] = Math.min(
        current[rightIndex - 1] + 1,
        previous[rightIndex] + 1,
        previous[rightIndex - 1] + substitutionCost,
      );
    }

    for (let index = 0; index <= right.length; index += 1) {
      previous[index] = current[index];
    }
  }

  return previous[right.length];
}

export function matchesSettingsTitle(title: string, query: string): boolean {
  const normalizedTitle = normalizeSearchText(title);
  const normalizedQuery = normalizeSearchText(query);

  if (normalizedQuery.length === 0) {
    return true;
  }

  if (normalizedTitle.includes(normalizedQuery)) {
    return true;
  }

  const maxDistance = allowedDistance(normalizedQuery.length);
  if (Math.abs(normalizedTitle.length - normalizedQuery.length) <= maxDistance
    && levenshteinDistance(normalizedTitle, normalizedQuery) <= maxDistance) {
    return true;
  }

  const titleTokens = normalizedTitle.split(' ');
  const queryTokens = normalizedQuery.split(' ');

  return queryTokens.every((queryToken) => {
    const tokenDistance = allowedDistance(queryToken.length);
    return titleTokens.some((titleToken) => (
      titleToken.includes(queryToken)
      || queryToken.includes(titleToken)
      || (Math.abs(titleToken.length - queryToken.length) <= tokenDistance
        && levenshteinDistance(titleToken, queryToken) <= tokenDistance)
    ));
  });
}

export function filterCatalogBlocks<
  TItem extends SearchableCatalogItem,
  TBlock extends SearchableCatalogBlock<TItem>,
>(blocks: TBlock[], query: string): TBlock[] {
  const normalizedQuery = normalizeSearchText(query);
  if (normalizedQuery.length === 0) {
    return blocks;
  }

  return blocks.flatMap((block) => {
    const items = block.items.filter((item) => matchesSettingsTitle(item.title, normalizedQuery));
    return items.length > 0 ? [{ ...block, items } as TBlock] : [];
  });
}
