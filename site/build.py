"""Builds the zizaikeyboard.com pages into site/public/.

The privacy page is generated from docs/privacy.md, so the policy has one source. Run this after editing
either, then deploy:  python site/build.py  and  npx wrangler deploy  (from site/).
"""

import html
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
OUT = ROOT / "public"
CONTACT = "feedback@zizaikeyboard.com"
GITHUB = "https://github.com/zizaiAndroid/zizai"


def inline(text: str) -> str:
    """Markdown inline syntax used in privacy.md: `code`, **bold**, *italic*, bare URLs."""
    parts = re.split(r"(`[^`]+`)", text)
    out = []
    for part in parts:
        if part.startswith("`") and part.endswith("`") and len(part) > 1:
            out.append(f"<code>{html.escape(part[1:-1])}</code>")
            continue
        p = html.escape(part, quote=False)
        p = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", p)
        p = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<em>\1</em>", p)
        p = re.sub(r"(https?://[^\s<)]+[^\s<).,;:])", r'<a href="\1">\1</a>', p)
        p = re.sub(r"\b([\w.+-]+@zizaikeyboard\.com)\b", r'<a href="mailto:\1">\1</a>', p)
        out.append(p)
    return "".join(out)


def slug(text: str) -> str:
    m = re.match(r"(\d+(?:\.\d+)?)", text)
    return "s" + m.group(1).replace(".", "-") if m else re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def markdown(md: str) -> tuple[str, list[tuple[str, str]]]:
    """Converts privacy.md. Returns the body and the list of level-2 headings for the contents."""
    lines = md.splitlines()
    out, toc = [], []
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
        elif line.startswith("# "):
            i += 1  # the page supplies its own title
        elif line.startswith("## ") or line.startswith("### "):
            level = 2 if line.startswith("## ") else 3
            text = line[level + 1:].strip()
            anchor = slug(text)
            if level == 2:
                toc.append((anchor, text))
            out.append(f'<h{level} id="{anchor}">{inline(text)}</h{level}>')
            i += 1
        elif line.strip() == "---":
            i += 1
        elif line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-+:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            head, body = rows[0], rows[1:]
            t = ['<div class="table"><table><thead><tr>']
            t += [f"<th>{inline(c)}</th>" for c in head]
            t.append("</tr></thead><tbody>")
            for r in body:
                t.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>")
            t.append("</tbody></table></div>")
            out.append("".join(t))
        elif line.startswith("- "):
            items = []
            while i < len(lines) and (lines[i].startswith("- ") or lines[i].startswith("  ")):
                if lines[i].startswith("- "):
                    items.append(lines[i][2:].strip())
                else:
                    items[-1] += " " + lines[i].strip()
                i += 1
            out.append("<ul>" + "".join(f"<li>{inline(x)}</li>" for x in items) + "</ul>")
        else:
            para = []
            while i < len(lines) and lines[i].strip() and not re.match(r"(#|- |\||---)", lines[i]):
                para.append(lines[i].strip())
                i += 1
            # "**Effective date:** …" lines keep their breaks
            if all(p.startswith("**") for p in para):
                out.append("<p>" + "<br>".join(inline(p) for p in para) + "</p>")
            else:
                out.append(f"<p>{inline(' '.join(para))}</p>")
    return "\n".join(out), toc


def page(title: str, description: str, body: str, current: str) -> str:
    nav = "".join(
        f'<a href="{href}"{" aria-current=\"page\"" if href == current else ""}>{label}</a>'
        for href, label in (("/", "Home"), ("/privacy/", "Privacy"), ("/delete/", "Delete account"))
    )
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<meta name="description" content="{html.escape(description)}">
<link rel="stylesheet" href="/style.css">
<link rel="icon" href="/icon.svg" type="image/svg+xml">
</head>
<body>
<header class="bar"><div class="inner">
  <a class="mark" href="/"><span class="zi" lang="zh">字在</span> Zizai</a>
  <nav aria-label="Site">{nav}</nav>
</div></header>
{body}
<footer class="foot"><div class="inner">
  <span>© 2026 Zizai Keyboard · <a href="mailto:{CONTACT}">{CONTACT}</a></span>
  <span><a href="{GITHUB}">Source code</a> · Apache License 2.0</span>
</div></footer>
</body>
</html>
"""


def build() -> None:
    OUT.mkdir(exist_ok=True)

    privacy_md = (REPO / "docs" / "privacy.md").read_text(encoding="utf-8")
    body, toc = markdown(privacy_md)
    contents = "".join(f'<li><a href="#{a}">{inline(t)}</a></li>' for a, t in toc)
    (OUT / "privacy").mkdir(exist_ok=True)
    (OUT / "privacy" / "index.html").write_text(page(
        "Privacy Policy · Zizai",
        "How the Zizai keyboard handles your voice, text and settings.",
        f"""<main class="doc inner">
<h1>Privacy Policy for <span lang="zh">字在</span> Zizai</h1>
<nav class="toc" aria-label="Contents"><ol>{contents}</ol></nav>
<article>{body}</article>
</main>""",
        "/privacy/",
    ), encoding="utf-8")

    for name in ("index.html", "delete/index.html"):
        src = ROOT / "pages" / name
        target = OUT / name
        target.parent.mkdir(exist_ok=True)
        text = src.read_text(encoding="utf-8")
        title, description, current, content = text.split("\n---\n", 3)
        target.write_text(page(title.strip(), description.strip(), content, current.strip()), encoding="utf-8")


if __name__ == "__main__":
    build()
    print("built", OUT)
