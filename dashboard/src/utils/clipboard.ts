interface LegacyClipboardDocument {
  execCommand?: (commandId: string) => boolean;
}

export async function copyTextToClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText != null) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Fall back to legacy copy path below.
    }
  }

  if (document?.body == null) {
    return false;
  }

  const textArea = document.createElement('textarea');
  textArea.value = text;
  textArea.setAttribute('readonly', '');
  textArea.style.position = 'fixed';
  textArea.style.top = '0';
  textArea.style.left = '-9999px';
  textArea.style.opacity = '0';
  textArea.style.pointerEvents = 'none';
  document.body.appendChild(textArea);

  const selection = document.getSelection();
  const selectedRange = selection != null && selection.rangeCount > 0
    ? selection.getRangeAt(0)
    : null;

  textArea.focus();
  textArea.select();
  textArea.setSelectionRange(0, textArea.value.length);

  const legacyClipboardDocument = document as unknown as LegacyClipboardDocument;
  let copied = false;
  try {
    copied = legacyClipboardDocument.execCommand != null
      ? legacyClipboardDocument.execCommand('copy')
      : false;
  } catch {
    copied = false;
  } finally {
    document.body.removeChild(textArea);
  }

  if (selection != null) {
    selection.removeAllRanges();
    if (selectedRange != null) {
      selection.addRange(selectedRange);
    }
  }

  return copied;
}
