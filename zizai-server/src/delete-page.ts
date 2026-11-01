/**
 * `GET /delete` — account deletion for people who no longer have the app, which Google Play requires
 * of any app that creates accounts. The account ID is shown in the app under Zizai Pro.
 */
export const DELETE_PAGE_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Delete your Zizai account</title>
<style>
  :root { color-scheme: light dark; --fg: #1d1d1f; --bg: #ffffff; --muted: #5f6368; --accent: #c0392b; }
  @media (prefers-color-scheme: dark) { :root { --fg: #f1f1f1; --bg: #121212; --muted: #a0a0a0; --accent: #ff6b5b; } }
  body { font: 16px/1.5 system-ui, sans-serif; color: var(--fg); background: var(--bg); margin: 0; padding: 32px 16px; }
  main { max-width: 560px; margin: 0 auto; }
  h1 { font-size: 1.5rem; }
  p, li { color: var(--muted); }
  input { width: 100%; box-sizing: border-box; padding: 10px; font: inherit; margin: 8px 0 16px; }
  button { padding: 10px 18px; font: inherit; color: #fff; background: var(--accent); border: 0; border-radius: 6px; cursor: pointer; }
  #result { margin-top: 16px; font-weight: 600; }
</style>
</head>
<body>
<main>
  <h1>Delete your 字在 Zizai account</h1>
  <p>Zizai creates an anonymous account per device when you first use Zizai AI. It holds no name, email,
     text or audio: only your monthly usage counts and, if you subscribed, your Zizai Pro status.</p>
  <p>If you still have the app, open <strong>Settings → Zizai Pro → Delete account</strong>. Otherwise enter
     the account ID shown on that screen. Deleting is immediate and cannot be undone. It does not cancel a
     Google Play subscription; cancel that in the Play Store.</p>
  <p>Accounts unused for 12 months are deleted automatically.</p>
  <label for="id">Account ID</label>
  <input id="id" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" autocomplete="off">
  <button id="go">Delete account</button>
  <div id="result" role="status"></div>
</main>
<script>
  document.getElementById('go').addEventListener('click', async () => {
    const out = document.getElementById('result');
    const accountId = document.getElementById('id').value.trim();
    if (!accountId) { out.textContent = 'Enter your account ID first.'; return; }
    out.textContent = 'Deleting…';
    try {
      const response = await fetch('/v1/account/delete-by-id', {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ accountId }),
      });
      out.textContent = response.ok ? 'Your account has been deleted.'
        : (await response.json()).error?.message ?? 'Something went wrong.';
    } catch { out.textContent = 'No connection. Please try again.'; }
  });
</script>
</body>
</html>
`;
