// parity-cc-tools' only script (CSP script-src 'self'; no inline script anywhere).
// 1. HTMX sends the CSRF token from the page's <meta> tags on every request it makes.
document.addEventListener("htmx:configRequest", function (event) {
  var token = document.querySelector('meta[name="csrf-token"]');
  var header = document.querySelector('meta[name="csrf-header"]');
  if (token && header && token.content && header.content) {
    event.detail.headers[header.content] = token.content;
  }
});
// 2. Live progress: when the SSE stream can't be opened or keeps failing (a proxy that buffers or blocks
//    event streams), the box falls back to polling its fragment every 2 s (PLAN.md §6.2). Neither decides
//    anything: they only refresh what the page shows.
document.addEventListener("htmx:sseError", function (event) {
  var box = event.target && event.target.closest ? event.target.closest("[data-poll-url]") : null;
  if (!box || box.getAttribute("data-polling") === "1") {
    return;
  }
  var poll = document.createElement("div");
  poll.className = box.className;
  poll.setAttribute("data-polling", "1");
  poll.setAttribute("hx-get", box.getAttribute("data-poll-url"));
  poll.setAttribute("hx-trigger", "load, every 2s");
  poll.setAttribute("hx-swap", "innerHTML");
  var inner = box.querySelector("[sse-swap]");
  if (inner) {
    poll.appendChild(inner.cloneNode(true));
  }
  box.parentNode.insertBefore(poll, box);
  htmx.remove(box);
  htmx.process(poll);
});
// 3. A confirmation button is disabled until the typed name matches what the page asks for.
document.addEventListener("input", function (event) {
  var input = event.target;
  if (!input || !input.matches || !input.matches("input[data-expect]")) {
    return;
  }
  var form = input.closest("form");
  var button = form ? form.querySelector("button[data-confirm]") : null;
  if (button) {
    button.disabled = input.value.trim() !== input.getAttribute("data-expect");
  }
});
