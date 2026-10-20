# AGENTS.md — Akiat editorial and engineering contract

Project: create original modern literary fairy tales in **natural Tatar (Cyrillic)**, publish quality-checked synthetic narration through Akiat Server / MCP / podcast RSS.

## Default literary workflow (Codex owns it)
1. Research contemporary Russian authored children's stories from legitimate sources, with named author/title/URL. Do not invent sources or rely on a publisher summary as though it were full-text analysis.
2. Extract **abstract emotional trajectories and genres** (humor, vulnerability, pacing, wonder, tenderness, ending). Do not translate or imitate protected expressive plot structures, specific characters, jokes, scene sequences, dialogue or characteristic details.
3. Propose a new autonomous Tatar story world, characters, events and ending; check novelty against catalogue. Aim for subtleness without overt lessons.
4. Draft and critically revise the full Tatar text in natural literary Tatar. Record actual sources, broad emotional inspiration and age recommendation.
5. When ready, call the Akiat MCP tool `submit_tatar_story` with the complete body. Do not ask the Java service to perform literature generation.
6. Make sure a human/authorized editor has approved language before `approve_tatar_text`; call `start_tatar_narration`; poll `get_tatar_narration_job`. The TTS worker renders the audio asynchronously and stores it on the server.
7. Do not approve narration without a listener checking pronunciation, artifacts, pitch, age suitability and sound quality; do not confirm rights without verifying text, voice-model, performance and phonogram permissions.
8. Only on explicit human instruction call `publish_tatar_episode` with exact `PUBLISH:<storyId>`. RSS is managed by Java and gets indexed independently by the publisher (no manual upload for each new podcast episode after feed acceptance).

## Reliability
- Provide one immutable `clientId` for each independently authored story. Retries use the SAME id; changing the body with the same id is rejected, not a new episode.
- Read project status from MCP, never infer successful audio generation from a queued job or RSS visibility from publication intent.
- Keep raw source audio, narrator recordings, API tokens, private drafts and model checkpoints out of public GitHub.
- Java, Python audio worker, RSS and persistence are operations infrastructure; Codex handles searching, critical reading, literary drafting and revisions.
- If MCP is not configured/reachable, explain the blocker; do not claim content has been published or queued.
- The old Java-side story generator and scheduler are legacy and disabled by default. Do not enable them unless asked.
