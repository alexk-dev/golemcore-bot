import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MutableRefObject,
  type ReactElement,
} from 'react';
import { getFileTree } from '../../api/files';
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
  const [filesLoaded, setFilesLoaded] = useState(false);
  const fetchedRef = useRef(false);

  const caret = resolveCaret(textareaRef.current, text);
  const trigger = useMemo<MentionTrigger | null>(() => findMentionTrigger(text, caret), [text, caret]);

  useEffect(() => {
    // Lazy-load the file tree the first time the user opens a @-mention.
    if (!trigger || fetchedRef.current) {
      return;
    }
    fetchedRef.current = true;
    getFileTree('')
      .then((tree) => {
        setFiles(flattenFileTree(tree));
        setFilesLoaded(true);
      })
      .catch((error: unknown) => {
        console.error('[file-mentions] failed to load file tree', error);
        setFilesLoaded(true);
      });
  }, [trigger]);

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

  if (!filesLoaded && files.length === 0) {
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
