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
./mvnw quarkus:dev
```

Dev UI: http://localhost:8080/q/swagger-ui

Point the LLM at a reachable **vLLM** (workshop freeze: Granite 3.1 8B Instruct):

```properties
questshift.llm.base-url=http://vllm.example:8000/v1
questshift.llm.model=ibm-granite/granite-3.1-8b-instruct
```

Do not put a real API key or a private URL in git. Copy `application-local.properties.example` to gitignored `application-local.properties` and set `%dev.questshift.llm.*` there. `./mvnw quarkus:dev` then loads that overlay; `./mvnw test` stays on `%test` with the LLM off.

If the endpoint is down, narration falls back to the authored campaign YAML so a dry run still works. Do not add Ollama.

## Quality gates

```bash
./mvnw spotless:apply   # Google Java Format (AOSP)
./mvnw test             # unit + @QuarkusTest (surefire); Phase 1 hour proof lives here
./mvnw verify           # also failsafe *IT, Spotless check, PMD (priority ≤ 3, 0 allowed), JaCoCo ≥ 80% lines / 70% branches
```

PMD rules: `pmd/ruleset.xml`. Coverage report: `target/jacoco-report/index.html`.

### Pre-commit hook

Once per clone (repo-local `core.hooksPath`, not global):

```bash
./.githooks/install
```

That runs Spotless, unit/`@QuarkusTest`, PMD, and JaCoCo on commit when Java/Maven files are staged. Integration tests stay on `./mvnw verify`. Local knobs: `.githooks/config` (from `config.example`). Bypass: `SKIP_QUESTSHIFT_HOOKS=1` or `git commit --no-verify`.

PRs into `main` run the same `./mvnw verify` gate in GitHub Actions (workflow **Quality** / job **Format, PMD, coverage**). Mark that check required on `main` so a red run cannot merge. Dependabot opens weekly GitHub Actions update PRs (`.github/dependabot.yml`).

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
