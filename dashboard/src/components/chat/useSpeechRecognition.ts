import { useCallback, useEffect, useRef, useState } from 'react';

interface SpeechRecognitionAlternativeLike {
  transcript: string;
}

interface SpeechRecognitionResultLike {
  0: SpeechRecognitionAlternativeLike;
}

interface SpeechRecognitionEventLike {
  results: ArrayLike<SpeechRecognitionResultLike>;
}

interface SpeechRecognitionLike {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onend: (() => void) | null;
  onerror: (() => void) | null;
  start: () => void;
  stop: () => void;
}

interface WindowWithSpeechRecognition extends Window {
  SpeechRecognition?: new () => SpeechRecognitionLike;
  webkitSpeechRecognition?: new () => SpeechRecognitionLike;
}

interface SpeechRecognitionHook {
  isRecording: boolean;
  isSupported: boolean;
  toggleRecording: (onTranscript: (transcript: string) => void) => void;
}

export function useSpeechRecognition(): SpeechRecognitionHook {
  const [isRecording, setIsRecording] = useState(false);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);

  const isSupported =
    typeof window !== 'undefined' &&
    (((window as WindowWithSpeechRecognition).SpeechRecognition !== undefined &&
      (window as WindowWithSpeechRecognition).SpeechRecognition !== null) ||
     ((window as WindowWithSpeechRecognition).webkitSpeechRecognition !== undefined &&
      (window as WindowWithSpeechRecognition).webkitSpeechRecognition !== null));

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (recognitionRef.current !== null) {
        recognitionRef.current.stop();
      }
    };
  }, []);

  const toggleRecording = useCallback((onTranscript: (transcript: string) => void) => {
    if (isRecording) {
      recognitionRef.current?.stop();
      setIsRecording(false);
      return;
    }

    const speechWindow = window as WindowWithSpeechRecognition;
    const SpeechRecognitionCtor =
      speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition;

    if (SpeechRecognitionCtor === undefined || SpeechRecognitionCtor === null) {
      return;
    }

    const recognition = new SpeechRecognitionCtor();
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.lang = navigator.language.length > 0 ? navigator.language : 'en-US';

    recognition.onresult = (event: SpeechRecognitionEventLike) => {
      let transcript = '';
      for (let i = 0; i < event.results.length; i += 1) {
        transcript += event.results[i][0].transcript;
      }
      onTranscript(transcript.trim());
    };

    recognition.onend = () => {
      setIsRecording(false);
      recognitionRef.current = null;
    };

    recognition.onerror = () => {
      setIsRecording(false);
      recognitionRef.current = null;
    };

    recognitionRef.current = recognition;
    recognition.start();
    setIsRecording(true);
  }, [isRecording]);

  return { isRecording, isSupported, toggleRecording };
}
