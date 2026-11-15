# CLI Task Chat

A chat app that turns a plain-English request into a **real command-line run**. Ask it to crawl a website and it starts the `sitecrawl` CLI as a separate process on the server, streams the CLI's log into the chat as it runs, checks the result, and answers with a summary plus structured JSON.

Built for **BuildSprint 2026, Round 2: CLI-Driven AI Chat Execution for Complex Tasks**.

**Live demo:** https://cli-task-chat.onrender.com (free Render instance: the first request after it has been idle takes about a minute to wake up).

> "Crawl https://quotes.toscrape.com, extract the important information, give me a summary, and return the extracted data in JSON format."

The chat shows the plan it understood, the exact command it ran, every log line the CLI printed, the exit code, process id and duration, then the summary and the JSON.

## How it works

```
 Browser chat ──POST /api/tasks──▶ IntentService ──▶ TaskIntent (validated, fixed shape)
     ▲                              (Gemini, or rule-based fallback)        │
     │                                                                      ▼
     │  Server-sent events                                          CrawlerCommand
     │  plan · started · log · result                       builds argv for the ONE approved tool
     │                                                                      │
     │                                                                      ▼
     │                                         CliRunner ── ProcessBuilder (no shell, empty env)
     │                                            │   streams stderr live, captures stdout,
     │                                            │   exit code, timeout and cancel
     │                                            ▼
     │                                   sitecrawl CLI (separate JVM process)
     │                                   java -jar sitecrawl.jar --url … --max-pages …
     │                                   stdout: one JSON report · stderr: progress · exit code
     │                                            │
     └──────────── TaskOutcome ◀── ResultProcessor + Summarizer
                                   exit code must match the report; summary from the real data
```

| Part | What it does | Code |
|---|---|---|
| Chat UI | Message box, example prompts, live log, status badge, summary, JSON viewer with copy and download, cancel button | `chat-server/src/main/resources/static/` |
| Intent layer | Reads the request: task type, URL, page and depth limits, summary or JSON, focus (e.g. contact details). Uses Gemini when `GEMINI_API_KEY` is set, otherwise a rule-based parser. Both answers go through the same validation | `intent/IntentService`, `intent/RuleBasedIntentParser` |
| Orchestration | Builds the command for the approved tool, runs it on a virtual thread, publishes events, limits concurrent runs | `exec/TaskService`, `exec/CrawlerCommand` |
| Process supervision | `ProcessBuilder` with an argument list (no shell), empty environment, temp working directory, live stderr streaming, stdout size cap, timeout with kill, cancel | `exec/CliRunner` |
| The CLI tool | `sitecrawl`: breadth-first crawl of one site with jsoup, robots.txt, redirects checked hop by hop, page/depth/time limits, JSON report on stdout | `crawler-cli/` |
| Output processing | Parses the JSON, checks it agrees with the exit code, refuses "success" with no pages, builds the clean JSON and the summary | `result/ResultProcessor`, `result/Summarizer` |

### Why a CLI, and why this one

`sitecrawl` is a small, purpose-built crawler packaged as its own executable jar. Running it as a separate process (not a library call) gives the isolation the brief asks for: the server only ever starts that one binary with a fixed set of flags, the child has its own memory limit (`-Xmx128m`), and it can be killed without affecting the server. It also works on its own from a terminal:

```bash
java -jar crawler-cli/target/sitecrawl.jar --url https://quotes.toscrape.com --max-pages 3
```

| Flag | Meaning | Range (default) |
|---|---|---|
| `--url` | Start page, http or https only | required |
| `--max-pages` | Pages to fetch | 1–25 (5) |
| `--max-depth` | Link depth from the start page | 0–3 (1) |
| `--timeout` | Seconds per request | 1–30 (10) |
| `--max-seconds` | Total time budget; stops early with a partial result | 5–120 (45) |

stdout is always exactly one JSON document, even on errors. stderr carries human-readable progress. Exit codes:

| Code | Meaning |
|---|---|
| 0 | success: every page fetched |
| 2 | partial: some pages failed, were skipped, or the time budget ran out |
| 3 | failed: the start page could not be fetched (host not found, HTTP error, timeout) |
| 4 | invalid input: bad URL, private/local host, out-of-range limit, unknown flag |
| 5 | blocked: robots.txt disallows the start page |
| 1 | unexpected error |

### From prompt to command

1. The message is interpreted into a `TaskIntent` with fixed fields: `action`, `url`, `maxPages`, `maxDepth`, `wantSummary`, `wantJson`, `focus`.
2. `IntentService.sanitize` is the gate: the URL must be a clean http(s) URL with no spaces, quotes or credentials; pages are capped at 10 and depth at 2 on the server; focus text is stripped to letters, numbers and simple punctuation.
3. `CrawlerCommand` builds the argument list from those fields only, for example
   `["java", "-Xmx128m", …, "-jar", "/app/sitecrawl.jar", "--url", "https://quotes.toscrape.com", "--max-pages", "5", …]`.
   The user's text never becomes a command, and each value is one `argv` entry passed straight to the OS, so `;`, `&&` or `$(…)` mean nothing.
4. `CliRunner` starts the process, streams each stderr line to the browser as a server-sent event, collects stdout (max 4 MB), and waits up to 75 seconds. On timeout it sends SIGTERM, then SIGKILL.
5. `ResultProcessor` parses stdout, checks the report's `status` against the exit code, and only then builds the answer. The status shown in the chat always comes from the CLI, never from the LLM.

## Security and reliability

- **No shell, one approved tool.** The server can only start `sitecrawl`, with arguments it built itself.
- **Validated input, twice.** The server validates the intent; the CLI validates its own arguments again and rejects unknown flags.
- **SSRF protection.** The CLI resolves every host and refuses loopback, private, link-local (including `169.254.169.254` cloud metadata), carrier-NAT and reserved addresses. Redirects are followed by hand so every hop is checked.
- **Limits.** Pages, depth, per-request timeout, total time budget, 2 MB per page, 4 MB of stdout, 500 log lines, 2 concurrent runs, 75 s hard kill.
- **Secrets stay out.** The CLI process gets an empty environment (only `PATH` and `LANG`), so `GEMINI_API_KEY` never reaches it. The key is sent to Gemini in a header, not the URL, and is never logged or shown.
- **Website content is untrusted.** When Gemini summarises, the crawl data is wrapped in `<crawl_data>` tags with an instruction to treat it only as data. The LLM's own intent answer is validated like user input.
- **Polite crawling.** robots.txt is respected, requests are sequential with a short delay, and the user agent identifies the tool.
- **Honest status.** Success, partial, failed, blocked, rejected, timeout and cancelled are all shown differently. If the exit code and the report disagree, or a "success" has no pages, the result is not shown as a success.

## Run it

Prerequisites: Java 21 and Maven 3.9+.

```bash
mvn package                      # builds both jars and runs all tests
cd chat-server
java -jar target/chat-server.jar # http://localhost:8080
```

Or with Docker:

```bash
docker build -t cli-task-chat .
docker run -p 8080:8080 cli-task-chat
```

| Environment variable | Default | Purpose |
|---|---|---|
| `GEMINI_API_KEY` | empty | Optional. Enables Gemini for intent parsing and summaries. Without it the app uses the rule-based parser and an extractive summary, and everything still works |
| `GEMINI_MODEL` | `gemini-3.6-flash` | Gemini model name |
| `CRAWLER_JAR` | `../crawler-cli/target/sitecrawl.jar` | Path to the CLI jar |
| `CRAWLER_PROCESS_TIMEOUT` | `75` | Seconds before the CLI process is killed |
| `CRAWLER_MAX_CONCURRENT` | `2` (`1` in the Docker image) | CLI runs allowed at once |
| `PORT` | `8080` | HTTP port |

## Try the demo

1. Open the app and click the first example prompt (the mandatory use case), or type:
   `Crawl https://quotes.toscrape.com, extract the important information, give me a summary, and return the extracted data in JSON format.`
2. Watch the plan, the exact `sitecrawl` command, and the live log.
3. Read the summary and the JSON; use **Show raw CLI report** to see exactly what the CLI printed.
4. Edge cases:
   - `Crawl http://localhost:8080/admin`: refused by the CLI, exit code 4.
   - `Crawl https://no-such-host-zz12.invalid`: host not found, exit code 3.
   - `Crawl https://github.com/settings`: blocked by robots.txt, exit code 5.
   - Start a crawl and press **Cancel**: the process is stopped and the chat says so.

## API

| Method and path | Purpose |
|---|---|
| `POST /api/tasks` `{"message": "..."}` | Interpret the message. Returns `202` with a `taskId` if it starts a CLI run, or `200` with a `reply` (help, need a URL, unsupported) |
| `GET /api/tasks/{id}/events` | Server-sent events: `plan`, `started`, `log` (one per CLI stderr line), `result` |
| `POST /api/tasks/{id}/cancel` | Stop a running task |
| `GET /api/health` | Whether the CLI jar is installed and whether Gemini is configured |

## Tests

`mvn test` runs 38 tests:

- **Crawler** (local HTTP server, no internet): same-site crawl, robots.txt, 404s, depth and page limits, emails and social links, CLI exit codes and JSON output.
- **Input safety**: private and local hosts, other schemes, credentials in URLs, out-of-range limits, unknown flags, robots.txt rule precedence.
- **Process supervision**: stdout/stderr/exit code capture, timeout kill, cancel, and a check that the child does not inherit environment variables.
- **Intent**: the mandatory prompt, bare domains, limit clamping, shell syntax in a message never reaching `argv`, validation of LLM answers.
- **Result processing**: exit code and report must agree, non-JSON output, empty "success", timeout and cancel, refused input.

## Tech stack and AI disclosure

- Java 21, Spring Boot 3.5 (web, SSE), jsoup 1.18, Jackson, JUnit 5; plain HTML, CSS and JavaScript for the UI.
- **Gemini** (`gemini-3.6-flash`, optional at runtime) for intent parsing and summaries.
- **Claude Code** (Anthropic) was used as an AI coding assistant while building this project. The design, the CLI contract and the safety rules were reviewed and tested by me, and I can explain every part.
