# QuestShift Engine

Quarkus 3 / Java 21 backend for [QuestShift](https://github.com/NA-FSI-Services/questshift).

- Session + campaign state
- OpenAI-compatible `LLMService` aimed at vLLM + Granite 3.1 8B Instruct
- `CommandEvaluator` for Linux / Ansible / OpenShift / Java puzzles
- YAML/JSON export and import via `StateSerializer`
- REST + WebSocket for the Phaser/React UI

Voice / TTS is not in this service.

## Run locally

Java 21 and Maven 3.9+.

```bash
# from questshift-engine, with questshift-campaigns as a sibling
export QUESTSHIFT_CAMPAIGNS_DIR=../questshift-campaigns/campaigns
./mvnw quarkus:dev
```

If you do not have the wrapper yet: `mvn quarkus:dev`.

Dev UI: http://localhost:8080/q/swagger-ui

Point the LLM at a reachable vLLM:

```properties
questshift.llm.base-url=http://vllm.example:8000/v1
questshift.llm.model=ibm-granite/granite-3.1-8b-instruct
```

If vLLM is down, narration falls back to the authored campaign YAML so a dry run still works.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/campaigns` | Loaded adventures |
| POST | `/api/sessions` | Start a one-party run |
| GET | `/api/sessions/{id}` | Snapshot |
| POST | `/api/sessions/{id}/commands` | `{ "command": "...", "seatId": "guardian" }` |
| GET | `/api/sessions/{id}/export?format=yaml` | Persist |
| POST | `/api/sessions/import` | Restore YAML or JSON |
| WS | `/ws/sessions/{id}` | Live snapshot push |

## Layout

```text
src/main/java/io/questshift/
  api/GameResource.java
  api/GameSocket.java
  llm/LLMService.java
  session/GameSession.java
  session/StateSerializer.java
  session/SessionService.java
  engine/CommandEvaluator.java
  campaign/Campaign.java
```
