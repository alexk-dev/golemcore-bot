# ADR-0008: Voice Recording in Chat with ElevenLabs Processing

- **Status:** Proposed
- **Date:** 2026-02-16
- **Owner:** Voice + Dashboard team

## Context
Users need quick voice input in chat. Requirement: record voice and process it through ElevenLabs pipeline.

Current system supports voice output, but chat input recording and upload flow are not implemented end-to-end in dashboard UX.

## Decision
Implement **in-browser voice recording** and send audio to backend endpoint that uses ElevenLabs processing (STT/transcription or configured voice pipeline), then inject resulting text into chat send flow.

## Scope
### In
- Microphone record button in chat input.
- Record/stop/cancel states + elapsed timer.
- Upload audio blob to backend.
- Backend calls ElevenLabs with configured API key/model.
- Transcribed text appears in input and can be edited before send.

### Out (future)
- Live streaming transcription while recording.
- Speaker diarization.

## Target Architecture

### Frontend
- Use MediaRecorder API.
- Supported mime fallback order:
  - `audio/webm;codecs=opus`
  - `audio/webm`
  - `audio/mp4` (browser-dependent)
- New API call:
  - `POST /api/voice/transcribe` multipart form-data (`file`, optional `language`).
- UX states:
  - idle -> recording -> processing -> ready/error.

### Backend
- Endpoint `POST /api/voice/transcribe`.
- Service `VoiceTranscriptionService` with port abstraction:
  - `VoiceProcessingPort` (ElevenLabs adapter implementation).
- Runtime config reads ElevenLabs credentials from secrets storage.
- Timeout/retry policy for external API.

### Domain/Config
- Add voice processing options:
  - model,
  - language,
  - max duration,
  - timeout.
- Fail gracefully when ElevenLabs not configured.

## UX Contract
- User clicks mic, records voice, clicks stop.
- System shows "Processing...".
- Transcription inserted into input (editable).
- User sends final message explicitly.

## Implementation Plan

### Phase A — Backend voice transcription API
- [ ] Add endpoint + request validation.
- [ ] Add ElevenLabs adapter via port.
- [ ] Add config and secret loading.

### Phase B — Frontend recorder
- [ ] Add mic button and state machine.
- [ ] Record audio and upload blob.
- [ ] Put transcript into text input.

### Phase C — Error handling
- [ ] Permission denied UX.
- [ ] Timeout/failure user-friendly messages.
- [ ] Size/duration constraints.

### Phase D — Tests
- [ ] Backend unit tests with mocked ElevenLabs adapter.
- [ ] Frontend tests for recorder state transitions.
- [ ] Integration test for upload->transcript flow.

## Security & Privacy
- Explicit microphone permission.
- No persistent raw audio unless configured.
- Redact secrets in logs.

## Acceptance Criteria
- Voice recording works in major Chromium browsers.
- ElevenLabs transcription returns editable text.
- Failures are visible and non-destructive.
