# Claude / Cursor — questshift-engine

Read `AGENTS.md` first (this repo), then the docs-repo map.

- GitHub: https://github.com/NA-FSI-Services/questshift/blob/main/AGENTS.md
- Local: `/Users/dtorresf/Documents/GitHub/na-fsi-services/questshift/questshift/AGENTS.md`

## Hard rules (v1 freeze)

- Quarkus 3 + Java 21, package `io.questshift`. No native image.
- vLLM only (Granite 3.2 8B Instruct). `%dev` keeps LLM **disabled**. No Ollama.
- Campaign YAML wins. LLM narrates only. Fallback to YAML if vLLM is down.
- Never execute player `oc` / Ansible / Linux / Java against the cluster.
- Many parties per process. Seats are cosmetic.
- Match REST/WebSocket named in API-CONTRACT.md.
- v1 non-goals: TTS, extra Routes, real command execution.
- Never commit API keys. `questshift.llm.api-key` stays `none` in git. Local overlay: gitignored `application-local.properties` (copy from `application-local.properties.example`). Committed model stays `ibm-granite/granite-3.2-8b-instruct`.
