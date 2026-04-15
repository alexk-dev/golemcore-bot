import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MutableRefObject,
  type ReactElement,
} from 'react';
import { getFileTree, type FileTreeNode } from '../../api/files';
import { FileMentionMenu } from './FileMentionMenu';
import {
  filterFilesByQuery,
  findMentionTrigger,
  flattenFileTree,
  insertMentionPath,
  type MentionFileEntry,
  type MentionTrigger,
} from './fileMentions';

const MAX_SUGGESTIONS = 8;
const INITIAL_FETCH_DEPTH = 2;
const LAZY_FETCH_DEPTH = 2;

function extractDirectoryPrefix(query: string): string | null {
  const lastSlash = query.lastIndexOf('/');
  if (lastSlash < 0) {
    return null;
  }
  return query.slice(0, lastSlash);
}

interface UseFileMentionsArgs {
  text: string;
  setText: (value: string) => void;
  textareaRef: MutableRefObject<HTMLTextAreaElement | null>;
}

interface UseFileMentionsResult {
  menu: ReactElement | null;
  isOpen: boolean;
}

export function useFileMentions({
  text,
  setText,
  textareaRef,
}: UseFileMentionsArgs): UseFileMentionsResult {
  const [files, setFiles] = useState<MentionFileEntry[]>([]);
  const [initialLoaded, setInitialLoaded] = useState(false);
  const rootFetchedRef = useRef(false);
  const fetchedDirsRef = useRef<Set<string>>(new Set());
  const inFlightDirsRef = useRef<Set<string>>(new Set());

  const caret = resolveCaret(textareaRef.current, text);
  const trigger = useMemo<MentionTrigger | null>(() => findMentionTrigger(text, caret), [text, caret]);

  const mergeTree = useCallback((tree: FileTreeNode[]): void => {
    const entries = flattenFileTree(tree);
    setFiles((previous) => {
      const byPath = new Map<string, MentionFileEntry>();
      for (const entry of previous) {
        byPath.set(entry.path, entry);
      }
      for (const entry of entries) {
        byPath.set(entry.path, entry);
      }
      return Array.from(byPath.values());
    });
  }, []);

  useEffect(() => {
    // Fetch a shallow root snapshot on the first @-mention trigger.
    if (!trigger || rootFetchedRef.current) {
      return;
    }
    rootFetchedRef.current = true;
    fetchedDirsRef.current.add('');
    getFileTree('', { depth: INITIAL_FETCH_DEPTH })
      .then((tree) => {
        mergeTree(tree);
        setInitialLoaded(true);
      })
      .catch((error: unknown) => {
        console.error('[file-mentions] failed to load file tree', error);
        setInitialLoaded(true);
      });
  }, [trigger, mergeTree]);

  useEffect(() => {
    // Lazily fetch a directory subtree when the user types a deeper path segment.
    if (!trigger) {
      return;
    }
    const dirPrefix = extractDirectoryPrefix(trigger.query);
    if (dirPrefix == null || dirPrefix.length === 0) {
      return;
    }
    if (fetchedDirsRef.current.has(dirPrefix) || inFlightDirsRef.current.has(dirPrefix)) {
      return;
    }
    inFlightDirsRef.current.add(dirPrefix);
    getFileTree(dirPrefix, { depth: LAZY_FETCH_DEPTH })
      .then((tree) => {
        fetchedDirsRef.current.add(dirPrefix);
        mergeTree(tree);
      })
      .catch((error: unknown) => {
        console.error('[file-mentions] failed to lazy-load directory', dirPrefix, error);
      })
      .finally(() => {
        inFlightDirsRef.current.delete(dirPrefix);
      });
  }, [trigger, mergeTree]);

  const suggestions = useMemo<MentionFileEntry[]>(() => {
    if (!trigger) {
      return [];
    }
    return filterFilesByQuery(files, trigger.query, MAX_SUGGESTIONS);
  }, [trigger, files]);

  const handleSelect = useCallback(
    (path: string): void => {
      if (!trigger) {
        return;
      }
      const result = insertMentionPath(text, trigger, path);
      setText(result.text);
      const textarea = textareaRef.current;
      if (textarea) {
        textarea.focus();
        requestAnimationFrame(() => {
          textarea.selectionStart = result.caret;
          textarea.selectionEnd = result.caret;
        });
      }
    },
    [trigger, text, setText, textareaRef],
  );

  if (!trigger) {
    return { menu: null, isOpen: false };
  }

  if (!initialLoaded && files.length === 0) {
    return { menu: null, isOpen: false };
  }

  return {
    menu: <FileMentionMenu suggestions={suggestions} onSelect={handleSelect} />,
    isOpen: true,
  };
}

function resolveCaret(textarea: HTMLTextAreaElement | null, fallbackText: string): number {
  if (textarea && typeof textarea.selectionEnd === 'number') {
    return textarea.selectionEnd;
  }
  return fallbackText.length;
}
