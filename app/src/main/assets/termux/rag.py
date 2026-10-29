#!/usr/bin/env python
"""Offline document search over ~/llm/kb (txt/md/py/csv/json; pdf if pdftotext exists). Keyword-scored;
swap in FAISS + embeddings later without changing the app (it just calls this script)."""
import os, re, subprocess, sys
q = " ".join(sys.argv[1:]).lower()
terms = set(re.findall(r"\w{3,}", q))
root = os.path.expanduser("~/llm/kb")
hits = []
for d, _, fs in os.walk(root):
    for f in fs:
        p = os.path.join(d, f)
        try:
            if f.lower().endswith(".pdf"):
                t = subprocess.run(["pdftotext", p, "-"], capture_output=True, text=True).stdout
            else:
                t = open(p, errors="ignore").read()
        except Exception:
            continue
        for chunk in re.split(r"\n\s*\n", t):
            s = sum(chunk.lower().count(w) for w in terms)
            if s: hits.append((s, f, chunk.strip()[:500]))
hits.sort(reverse=True)
print("\n---\n".join(f"[{f}] {c}" for _, f, c in hits[:4]) or "no matches (put files in ~/llm/kb)")
