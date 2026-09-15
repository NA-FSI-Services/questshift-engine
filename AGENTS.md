# Agent notes — questshift-engine

Canonical map:

- GitHub: https://github.com/NA-FSI-Services/questshift/blob/main/AGENTS.md
- Local: `/Users/dtorresf/Documents/GitHub/na-fsi-services/questshift/questshift/AGENTS.md`

Read essentials first:

- GitHub: https://github.com/NA-FSI-Services/questshift/blob/main/docs/ARCHITECTURE-ESSENTIALS.md
- Local: `/Users/dtorresf/Documents/GitHub/na-fsi-services/questshift/questshift/docs/ARCHITECTURE-ESSENTIALS.md`

API shapes:

- GitHub: https://github.com/NA-FSI-Services/questshift/blob/main/docs/API-CONTRACT.md
- Local: `/Users/dtorresf/Documents/GitHub/na-fsi-services/questshift/questshift/docs/API-CONTRACT.md`

## This repo

Quarkus 3.39 / Java 21. Package `io.questshift.{api,campaign,engine,llm,session}`. `%dev` sets `questshift.llm.enabled=false`. Campaigns load from `../questshift-campaigns/campaigns` (sibling) or classpath `campaigns/campaign-devops-dungeon.yaml`.

- Do not add routes beyond `GameResource` / `GameSocket`.
- Do not execute player commands. `CommandEvaluator` is the only scorer.
- Keep YAML `expected_command_pattern`; never let the LLM rewrite it.
- JVM only. No native image. No Ollama client.
- Keep committed `questshift.llm.api-key=none`. Put a real key only in untracked `application-local.properties` (copy `application-local.properties.example`) or the cluster, never in git. Committed model stays `ibm-granite/granite-3.1-8b-instruct`.
- Run: `./mvnw quarkus:dev`, `./mvnw test` (unit + `@QuarkusTest`), `./mvnw verify` (Spotless, PMD, integration tests, JaCoCo 80% line / 70% branch).
- Pre-commit: `./.githooks/install` (sets local `core.hooksPath`). Hook runs `./mvnw -Ppre-commit verify` (Spotless, tests, PMD, JaCoCo; skips ITs). Overrides in gitignored `.githooks/config`.
- CI: `.github/workflows/quality.yml` runs `./mvnw verify` on PRs and pushes to `main` (Spotless, PMD, tests + ITs, JaCoCo 80/70). Dependabot: `.github/dependabot.yml` (weekly GitHub Actions).
