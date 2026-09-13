# Publishing blog 3

| File | Platform | Notes |
|---|---|---|
| `../blog-3-what-a-question-found.md` | Medium (canonical), then dev.to / Hashnode | ~1,600 words. Convert tables for Medium with `../media/md2medium.py`, as for blogs 1 and 2. |
| `blog-3-reddit.md` | r/Rag, r/LocalLLaMA, r/dataengineering | Two variants: retrieval/memory bugs, and the Kafka/CDC design. |
| `blog-3-hackernews.md` | Hacker News | Link post to the canonical URL, then the first comment. |

Order: publish the canonical copy first, set `canonical_url` everywhere else to it, then the short
posts a few days apart, one community at a time. Replace `<canonical URL>` in the Reddit draft.

The person whose question started this is not named or described. Ask before crediting them.
