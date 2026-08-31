"""Markdown -> HTML shaped for Medium's paste importer.

Medium renders a narrow subset. Two things matter here: it has no table element at all, so a
markdown table pasted in arrives as a run of pipes; and its code blocks preserve alignment. Tables
therefore become <pre>, column-aligned, which is the closest honest rendering.
"""
import re, sys, html

def render_table(rows):
    """Render a markdown table for Medium, which has no table element.

    Two constraints learned the hard way. Medium's code blocks scroll rather than wrap, so a
    wrapped cell is fine but a *split* row is unreadable. And its paste importer treats a blank
    line inside a <pre> as a block boundary — a blank line between rows, added to group wrapped
    ones, silently breaks a single table into several code blocks.

    So: two-column tables become native paragraphs, which read better than any monospace grid.
    Wider tables stay preformatted, sized so every row fits one line — alignment is what makes a
    grid legible, and a grid that wraps has given that up anyway.
    """
    def plain(c):
        c = re.sub(r'\*\*([^*]+)\*\*', r'\1', c)
        return c.strip()
    raw = [[c.strip() for c in r.strip().strip('|').split('|')] for r in rows]
    raw = [c for c in raw if not all(set(x) <= set('-: ') for x in c)]
    if not raw:
        return ''
    ncols = max(len(r) for r in raw)

    if ncols == 2:
        # A term and its description is a definition list, not a grid.
        out = []
        for i, row in enumerate(raw):
            left, right = plain(row[0]), row[1] if len(row) > 1 else ''
            if i == 0 and not left:
                continue  # header with an empty first cell carries no information here
            out.append('<p><strong>' + html.escape(left) + '</strong> — ' + inline(right) + '</p>')
        return '\n'.join(out)

    cells = [[plain(c) for c in (r + [''] * (ncols - len(r)))] for r in raw]
    widths = [max(len(r[i]) for r in cells) for i in range(ncols)]
    out = []
    for n, row in enumerate(cells):
        out.append('  '.join(row[i].ljust(widths[i]) for i in range(ncols)).rstrip())
        if n == 0:
            out.append('  '.join('-' * widths[i] for i in range(ncols)))
    return '<pre>' + html.escape('\n'.join(out)) + '</pre>'

def inline(t):
    t = html.escape(t)
    t = re.sub(r'`([^`]+)`', r'<code>\1</code>', t)
    t = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', t)
    t = re.sub(r'(?<![*\w])\*([^*\n]+)\*(?!\*)', r'<em>\1</em>', t)
    t = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', t)
    return t

def convert(md):
    out, lines, i = [], md.split('\n'), 0
    while i < len(lines):
        line = lines[i]
        if line.startswith('```'):
            i += 1; buf = []
            while i < len(lines) and not lines[i].startswith('```'):
                buf.append(lines[i]); i += 1
            i += 1
            out.append('<pre>' + html.escape('\n'.join(buf)) + '</pre>')
        elif line.strip().startswith('|'):
            buf = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                buf.append(lines[i]); i += 1
            out.append(render_table(buf))
        elif line.startswith('### '):
            out.append('<h3>' + inline(line[4:]) + '</h3>'); i += 1
        elif line.startswith('## '):
            out.append('<h2>' + inline(line[3:]) + '</h2>'); i += 1
        elif line.startswith('# '):
            out.append('<h1>' + inline(line[2:]) + '</h1>'); i += 1
        elif line.strip() == '---':
            out.append('<hr>'); i += 1
        elif line.strip().startswith(('- ', '* ')):
            buf = []
            while i < len(lines) and lines[i].strip().startswith(('- ', '* ')):
                buf.append('<li>' + inline(lines[i].strip()[2:]) + '</li>'); i += 1
            out.append('<ul>' + ''.join(buf) + '</ul>')
        elif line.strip() == '':
            i += 1
        else:
            buf = []
            while i < len(lines) and lines[i].strip() and not lines[i].startswith(('#', '```', '|', '- ', '* ')) \
                    and lines[i].strip() != '---':
                buf.append(lines[i].strip()); i += 1
            out.append('<p>' + inline(' '.join(buf)) + '</p>')
    return '\n'.join(out)

src = open(sys.argv[1]).read()
# The H1 becomes Medium's title from the editor, not from the body.
src = re.sub(r'\A# .*\n', '', src, count=1)
# Drop standalone image references. They point at repo-relative paths that would not resolve on
# Medium, and the banner is inserted there as a real upload — left in, they paste as literal
# "![alt](path)" text in the middle of the article.
src = re.sub(r'^!\[[^\]]*\]\([^)]*\)\s*$', '', src, flags=re.M)
sys.stdout.write(convert(src))
