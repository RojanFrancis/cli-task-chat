"use strict";

const chat = document.getElementById("chat");
const form = document.getElementById("composer");
const input = document.getElementById("input");
const send = document.getElementById("send");
const template = document.getElementById("task-template");


// ---------- health badge ----------
fetch("api/health")
  .then((r) => r.json())
  .then((h) => {
    document.getElementById("health").textContent =
      `CLI: ${h.crawlerCli} · LLM: ${h.llm}`;
  })
  .catch(() => (document.getElementById("health").textContent = "server unreachable"));

// ---------- input ----------
document.getElementById("chips").addEventListener("click", (e) => {
  if (e.target.classList.contains("chip")) {
    input.value = e.target.textContent;
    input.focus();
    autosize();
  }
});

input.addEventListener("input", autosize);
input.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    form.requestSubmit();
  }
});

function autosize() {
  input.style.height = "auto";
  input.style.height = Math.min(input.scrollHeight, 160) + "px";
}

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  const message = input.value.trim();
  if (!message) return;
  addUser(message);
  input.value = "";
  autosize();
  send.disabled = true;
  try {
    const res = await fetch("api/tasks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message }),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      addBot(body.error || `Request failed (HTTP ${res.status}).`);
    } else if (body.taskId) {
      await runTask(body.taskId);
    } else {
      addBot(body.reply || "I couldn't work out a task from that.");
    }
  } catch (err) {
    addBot("Could not reach the server. Is it running?");
  } finally {
    send.disabled = false;
    input.focus();
  }
});

// ---------- messages ----------
function addUser(text) {
  const el = document.createElement("div");
  el.className = "msg user";
  el.innerHTML = `<div class="bubble"></div>`;
  el.firstChild.textContent = text;
  chat.appendChild(el);
  scroll();
}

function addBot(text) {
  const el = document.createElement("div");
  el.className = "msg bot";
  el.innerHTML = `<div class="bubble reply"></div>`;
  el.firstChild.textContent = text;
  chat.appendChild(el);
  scroll();
}

function scroll() {
  chat.scrollTop = chat.scrollHeight;
}

// ---------- a running task ----------
function runTask(taskId) {
  const node = template.content.firstElementChild.cloneNode(true);
  chat.appendChild(node);
  const $ = (sel) => node.querySelector(sel);
  const log = $(".log");
  const state = $(".state");
  const timer = $(".timer");
  const cancel = $(".cancel");
  const started = performance.now();
  const tick = setInterval(() => {
    timer.textContent = ((performance.now() - started) / 1000).toFixed(1) + "s";
  }, 100);

  cancel.addEventListener("click", () => {
    cancel.disabled = true;
    fetch(`api/tasks/${taskId}/cancel`, { method: "POST" });
  });

  return new Promise((resolve) => {
    const events = new EventSource(`api/tasks/${taskId}/events`);
    let finished = false;

    const finish = () => {
      if (finished) return;
      finished = true;
      clearInterval(tick);
      cancel.hidden = true;
      events.close();
      resolve();
    };

    events.addEventListener("plan", (e) => renderPlan(node, JSON.parse(e.data)));
    events.addEventListener("started", () => appendLog(log, "[server] CLI process started"));
    events.addEventListener("log", (e) => {
      appendLog(log, e.data);
      scroll();
    });
    events.addEventListener("result", (e) => {
      const outcome = JSON.parse(e.data);
      state.textContent = outcome.status;
      state.className = "state " + outcome.status;
      renderOutcome(node, outcome);
      finish();
      scroll();
    });
    events.onerror = () => {
      if (!finished) {
        state.textContent = "connection lost";
        state.className = "state error";
        appendLog(log, "[server] lost the event stream before a result arrived");
      }
      finish();
    };
  });
}

function renderPlan(node, plan) {
  node.querySelector(".by").textContent = `interpreted by ${plan.interpretedBy}`;
  const fields = node.querySelector(".plan-fields");
  const items = [
    ["task", "crawl website"],
    ["url", plan.url],
    ["max pages", plan.maxPages],
    ["depth", plan.maxDepth],
    ["output", [plan.wantSummary && "summary", plan.wantJson && "JSON"].filter(Boolean).join(" + ")],
  ];
  if (plan.focus) items.push(["focus", plan.focus]);
  for (const [k, v] of items) {
    const li = document.createElement("li");
    li.innerHTML = `<b></b> <span></span>`;
    li.firstChild.textContent = k + ":";
    li.lastChild.textContent = v;
    fields.appendChild(li);
  }
  node.querySelector(".cmd-text").textContent = plan.command;
  scroll();
}

function appendLog(log, line) {
  const span = document.createElement("span");
  if (line.startsWith("[server]")) span.className = "srv";
  else if (/HTTP 2\d\d|status success|done:/.test(line)) span.className = "ok";
  else if (/redirect|skip|partial|budget used up|cancel/.test(line)) span.className = "warn";
  else if (/error|invalid|refused|cannot|HTTP [45]\d\d|failed|blocked/i.test(line)) span.className = "err";
  span.textContent = line + "\n";
  log.appendChild(span);
  log.scrollTop = log.scrollHeight;
}

function renderOutcome(node, o) {
  const box = node.querySelector(".outcome");
  box.hidden = false;
  const headline = box.querySelector(".headline");
  headline.textContent = o.headline;
  headline.className = "headline " + (o.status === "success" ? "success" : o.status === "partial" ? "partial" : "bad");

  if (o.execution) {
    const x = o.execution;
    box.querySelector(".exec-meta").textContent =
      `exit code ${x.exitCode} (${x.exitMeaning}) · pid ${x.pid} · ${(x.durationMs / 1000).toFixed(1)}s · ${x.logLines} log lines` +
      (x.stdoutTruncated ? " · output truncated" : "");
  }

  if (o.summary) {
    const s = box.querySelector(".summary");
    s.hidden = false;
    s.querySelector(".source").textContent = o.summarySource ? `· ${o.summarySource}` : "";
    s.querySelector(".summary-text").textContent = o.summary;
    const ul = s.querySelector(".points");
    for (const p of o.keyPoints || []) {
      const li = document.createElement("li");
      li.textContent = p;
      ul.appendChild(li);
    }
  }

  const shown = o.result || o.rawReport;
  if (shown) {
    const j = box.querySelector(".json");
    j.hidden = false;
    const pre = j.querySelector(".json-text");
    let raw = !o.result;
    const paint = () => {
      const data = raw ? o.rawReport : o.result;
      pre.innerHTML = highlight(JSON.stringify(data, null, 2));
      j.querySelector(".raw-toggle").textContent = raw ? "Show JSON result" : "Show raw CLI report";
    };
    paint();
    const toggle = j.querySelector(".raw-toggle");
    toggle.hidden = !(o.result && o.rawReport);
    toggle.addEventListener("click", () => {
      raw = !raw;
      paint();
    });
    j.querySelector(".copy").addEventListener("click", (e) => {
      navigator.clipboard.writeText(JSON.stringify(raw ? o.rawReport : o.result, null, 2));
      e.target.textContent = "Copied";
      setTimeout(() => (e.target.textContent = "Copy"), 1200);
    });
    j.querySelector(".download").addEventListener("click", () => {
      const blob = new Blob([JSON.stringify(raw ? o.rawReport : o.result, null, 2)], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "crawl-result.json";
      a.click();
      URL.revokeObjectURL(a.href);
    });
  }
}

function highlight(json) {
  const esc = json.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  return esc.replace(
    /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\b\d+(?:\.\d+)?\b/g,
    (m, str, colon, lit) => {
      if (str) return colon ? `<span class="k">${str}</span>${colon}` : `<span class="s">${str}</span>`;
      return `<span class="n">${m}</span>`;
    }
  );
}
