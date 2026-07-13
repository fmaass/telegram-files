# Live release acceptance: WebSocket EXACTLY-ONCE against the DEPLOYED container with a REAL account.
#
# HUMAN / OPERATOR-GATED — NOT run in CI. The synthetic injector is hard-off under prod (both the HTTP
# gateway and TelegramVerticle.injectUpdateForGateway refuse when APP_ENV=prod), so this drives a REAL
# download on the running production stack and observes the live websocket, per the runbook in
# misc/acceptance/ws-exactly-once.md.
#
# Runs via the `browser-harness` skill (CDP to a real Chrome with remote debugging). It automates the
# browser-driving parts; a HUMAN confirms the "no duplicate" judgments it prints. It is intentionally
# read-only against the app (it only observes; it starts a download via the UI if asked).
#
# Usage (inside a browser-harness heredoc; helpers new_tab/goto_url/page_info are pre-imported):
#   browser-harness < misc/acceptance/ws-live-release.py
#
# Preconditions (see ws-exactly-once.md): new image deployed & serving; a Telegram account authorized
# with a chat that has a downloadable file; local Chrome remote-debugging enabled.

BASE = "http://localhost:8979"

def banner(msg):
    print("\n" + "=" * 78)
    print("  " + msg)
    print("=" * 78)


def main():
    banner("WS EXACTLY-ONCE — LIVE RELEASE PROBE (real account, deployed container)")

    # 0) Confirm the harness can reach a real browser tab and the deployed shell is serving.
    ensure_real_tab()  # noqa: F821  (provided by the browser-harness runtime)
    new_tab(f"{BASE}/")  # noqa: F821
    info = page_info()  # noqa: F821
    assert "Telegram Files" in info["title"], f"unexpected title: {info.get('title')!r}"
    print(f"[ok] deployed shell serving: title={info['title']!r}")

    # 1) Observe an ACTIVE download's progress on the dashboard. A human should have a download running
    #    (or start one via the UI now). We poll the DOM for a progress bar that ADVANCES.
    banner("STEP 1 — observe live progress (progress bar must ADVANCE monotonically)")
    print("  -> Ensure a real download is ACTIVE for an authorized account, then observe below.")
    samples = []
    for i in range(8):
        # Read the first visible progress bar's aria-valuenow (Radix sets it) as the progress %.
        val = eval_js(  # noqa: F821
            """
            (() => {
              const bars = [...document.querySelectorAll('[role="progressbar"]')];
              if (!bars.length) return null;
              const v = bars[0].getAttribute('aria-valuenow');
              return v === null ? 'present-no-value' : Number(v);
            })()
            """
        )
        samples.append(val)
        print(f"    progress sample {i}: {val}")
        sleep(1)  # noqa: F821
    numeric = [s for s in samples if isinstance(s, (int, float))]
    if len(numeric) >= 2:
        monotonic = all(b >= a for a, b in zip(numeric, numeric[1:]))
        advanced = numeric[-1] > numeric[0]
        print(f"[{'ok' if monotonic else 'CHECK'}] progress monotonic non-decreasing: {monotonic}")
        print(f"[{'ok' if advanced else 'CHECK'}] progress advanced over the window: {advanced} "
              f"({numeric[0]} -> {numeric[-1]})")
    else:
        print("[CHECK] no numeric progress observed — start a real download and re-run this step.")

    # 2) Duplicate-row / double-count check (human-confirmed): one row per downloading file.
    banner("STEP 2 — no duplicate rows for the same file (HUMAN confirm)")
    names = eval_js(  # noqa: F821
        """
        (() => {
          // File name cells are the stable per-row anchor. Count duplicates.
          const cells = [...document.querySelectorAll('*')]
            .filter(e => e.children.length === 0 && /\\.(bin|mp3|mp4|jpg|png|flac|zip|pdf)$/i.test(e.textContent||''))
            .map(e => e.textContent.trim());
          const counts = {};
          for (const n of cells) counts[n] = (counts[n]||0)+1;
          return counts;
        })()
        """
    )
    dups = {n: c for n, c in (names or {}).items() if c > 1}
    print(f"    file-name row counts: {names}")
    if dups:
        print(f"[CHECK] POSSIBLE DUPLICATE ROWS: {dups} — inspect; a fanout double-delivery would show here.")
    else:
        print("[ok] no duplicate file-name rows detected.")

    # 3) Reconnect the SAME session; the row must keep updating (no eviction after the old socket closes).
    banner("STEP 3 — reconnect same session; row keeps updating (no stale-close eviction)")
    print("  -> Trigger the dashboard reconnect (or toggle network off/on). The websocket reconnects on")
    print("     the SAME tf cookie; the download row MUST keep advancing after reconnect.")
    # Best-effort: click a reconnect control if present; otherwise the human toggles network.
    eval_js(  # noqa: F821
        """
        (() => {
          const btn = [...document.querySelectorAll('button,[role=button]')]
            .find(b => /reconnect/i.test(b.textContent||'') || /reconnect/i.test(b.getAttribute('aria-label')||''));
          if (btn) { btn.click(); return 'clicked-reconnect'; }
          return 'no-reconnect-control-found (toggle network manually)';
        })()
        """
    )
    sleep(3)  # noqa: F821
    post = eval_js(  # noqa: F821
        """
        (() => {
          const bars = [...document.querySelectorAll('[role="progressbar"]')];
          if (!bars.length) return 'no-bar (download may have completed)';
          return Number(bars[0].getAttribute('aria-valuenow'));
        })()
        """
    )
    print(f"    post-reconnect progress: {post}")
    print("[CHECK] confirm the row is STILL updating (or completed) after reconnect — NOT frozen at the")
    print("        pre-reconnect value, which would indicate the reconnected socket lost its session.")

    # 4) Completion exactly once (human-confirmed).
    banner("STEP 4 — completion exactly once (HUMAN confirm)")
    print("  -> Let the download COMPLETE. Confirm the row reaches 'Completed' EXACTLY ONCE:")
    print("     the badge settles on Completed, does NOT flicker back to Downloading, and no duplicate")
    print("     completed row appears for the same file.")
    completed = eval_js(  # noqa: F821
        """(() => [...document.querySelectorAll('*')].filter(e => (e.textContent||'').trim() === 'Completed').length)()"""  # noqa: E501
    )
    print(f"    'Completed' badge count on screen: {completed}")

    banner("DONE — record the four judgments in the release acceptance log (ws-exactly-once.md).")


main()
