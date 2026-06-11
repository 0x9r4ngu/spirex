# spirex

**A fast, multithreaded web crawler in pure Java — zero dependencies.**

`spirex` seeds from one or more URLs and crawls links concurrently across a pool
of worker threads, staying within a defined scope and depth, then prints every
URL it finds. It's built on nothing but the JDK standard library — no Maven, no
Gradle, no third-party JARs.

```text
$ spirex -u https://example.com -d 2 -t 20 -v

             _
   ___ _ __ (_)_ __ _____  __
  / __| '_ \| | '__/ _ \ \/ /
  \__ \ |_) | | | |  __/>  <
  |___/ .__/|_|_|  \___/_/\_\
      |_|
  v1.0.0  -  multithreaded web crawler  -  https://github.com/0x9r4ngu/spirex

  target https://example.com  depth 2  threads 20  strategy depth-first  scope rdn

[200] https://example.com/
[200] https://example.com/about
[301] https://example.com/blog
 ...

  [done] 3.2s   pages 142   urls 142   hosts 1
  status  2xx 130  3xx 11  4xx 1  5xx 0  err 0
```

The banner and summary print to **stderr**; the URLs print to **stdout**, so you
can pipe results anywhere without noise:

```bash
spirex -u https://example.com | sort -u > urls.txt
```

---

## Install

**One line** — clone, build, and install `spirex` system-wide into
`/usr/local/bin`, available for every user (needs a **JDK 21+** and `git`):

```bash
git clone https://github.com/0x9r4ngu/spirex.git && cd spirex && sudo PREFIX=/usr/local ./install.sh
```

`/usr/local/bin` is already on everyone's `PATH`, so you can run it from anywhere:

```bash
spirex --help
spirex -u https://example.com
```

**Uninstall** anytime:

```bash
sudo PREFIX=/usr/local ./uninstall.sh
```

### Run without installing

Prefer not to install anything? Build the jar and run it directly:

```bash
./build.sh
java -jar spirex.jar -u https://example.com
```

---

## Quick start

```bash
spirex -u https://example.com                 # crawl (defaults: depth 3, 10 threads)
spirex -u example.com -d 2 -t 20 -v           # shallower, more threads, show status
spirex -u example.com | sort -u > urls.txt    # pipe clean URL list to a file
spirex -l hosts.txt --json -q                 # many targets, JSON, results-only
spirex --help                                 # full option list
```

---

## Updating

`spirex` checks for new releases the same way `nuclei` does. On every run it
compares its own version against the latest GitHub release and, if you're behind,
prints a one-time notice to **stderr**:

```text
[WRN] A new spirex release is available: v1.1.0 (you have v1.0.0)
[WRN] Run spirex --update to upgrade.
```

Upgrade in place — this downloads the latest release and swaps the installed jar
(keeping a `.bak`):

```bash
spirex --update
```

The check is cached for 6 hours so it never slows you down, and it fails silently
when you're offline. Turn it off entirely with `--disable-update-check`
(`-q`/`--quiet` suppresses it too).

---

## Features

- **Multithreaded engine** — a configurable pool of worker threads fetches pages
  in parallel, coordinated through a thread-safe work queue. ~8× faster at 20
  threads than 1 on network-bound crawls.
- **Two strategies** — depth-first (LIFO) or breadth-first (FIFO), one flag.
- **Scope control** — exact host, root domain + subdomains, a custom regex, or no
  limit; plus include/exclude regex lists.
- **Host exclusion** — skip CDNs, private/internal IPs, specific IPs, or CIDR ranges.
- **Link discovery** — HTML attributes (`href`, `src`, `action`, …), `<base href>`,
  `sitemap.xml`, `robots.txt`, and JavaScript endpoints (`--scripts`). HTML
  entities in URLs are decoded (`&amp;` → `&`).
- **Form extraction** — form actions, methods, and input names as JSON (`--forms`).
- **Technology detection** — fingerprints servers/frameworks (`--tech`).
- **Smart de-duplication** — every URL crawled exactly once; optional query-param
  folding and similar-URL collapsing.
- **Rate limiting** — requests/sec, requests/min, and per-request delay, enforced
  globally across threads.
- **Flexible output** — plain URLs, field projections, custom templates, JSON
  Lines, file output, response storage; a colourised summary with a status-code
  breakdown.

---

## Output

| Mode | Flag | What you get |
|------|------|--------------|
| Plain | *(default)* | one URL per line on stdout |
| Verbose | `-v` | `[status] url` with colourised codes |
| Field | `--field path` | a projection (path, fqdn, dir, query keys/values, …) |
| JSON Lines | `--json` | one JSON object per result — pipe into `jq` |
| Quiet | `-q` | results only, no banner or summary |

JSON example:

```json
{"timestamp":"2026-06-09T14:15:18Z","url":"https://example.com/","source":"seed","content_type":"text/html","depth":1,"status_code":200}
```

Forms (`--forms --json`):

```json
{"type":"form","source":"https://site/login","action":"https://site/login","method":"POST","inputs":["csrf_token","username","password"]}
```

---

## Command-line reference

Single-letter flags use one dash (`-d`), word flags use two (`--depth`).
`--flag=value` works too. List flags take a comma-separated value, a file path,
or `@file` (`-u`, `--skip`, `--ext-only`, …).

### Target
| Flag | Description |
|------|-------------|
| `-u`, `--url <url,...>` | Seed URL(s); comma-separated or `@file` |
| `-l`, `--list <file>` | Read seed URLs from a file |

### Crawl
| Flag | Default | Description |
|------|---------|-------------|
| `-d`, `--depth <n>` | 3 | Maximum crawl depth |
| `-t`, `--threads <n>` | 10 | Worker threads |
| `-b`, `--bfs` | off | Breadth-first (default is depth-first) |
| `--scripts` | off | Mine endpoints from JavaScript |
| `--known-files <set>` | — | Crawl `robots`, `sitemap`, or `all` |
| `--climb` | off | Also crawl parent directories |
| `--max-time <dur>` | — | Stop after `30s` / `5m` / `1h` |
| `--no-query` | off | Treat URLs differing only by query as one |
| `--fold-similar` | off | Fold look-alike URLs (`/p/1` ≈ `/p/2`) |
| `--no-redirect` | off | Don't follow redirects |

### Scope
| Flag | Default | Description |
|------|---------|-------------|
| `--scope <mode>` | domain | `host` (exact), `domain` (root+subdomains), `any` |
| `--in-scope <re>` | — | Only crawl URLs matching this regex |
| `--out-scope <re>` | — | Never crawl URLs matching this regex |
| `--skip <filter>` | — | Skip hosts: `cdn`, `private`, an IP, a CIDR, or a regex |
| `--show-external` | off | List external links without crawling them |

### Network
| Flag | Default | Description |
|------|---------|-------------|
| `--timeout <s>` | 10 | Per-request timeout (seconds) |
| `--retries <n>` | 1 | Retries per failed request |
| `--rate <n>` | 150 | Max requests per second |
| `--delay <s>` | 0 | Delay before each request (seconds) |
| `--max-size <bytes>` | 4 MB | Cap response body size |
| `-H`, `--header <k:v>` | — | Extra request header (repeatable) |
| `-A`, `--agent <ua>` | — | Set the User-Agent |
| `-x`, `--proxy <host:port>` | — | HTTP proxy |

### Filter
| Flag | Description |
|------|-------------|
| `-m`, `--match <re>` | Only output URLs matching this regex |
| `-f`, `--filter <re>` | Drop output URLs matching this regex |
| `--ext-only <list>` | Only output these extensions |
| `--ext-skip <list>` | Drop these extensions |
| `--keep-dupes` | Keep pages with duplicate content |

### Output
| Flag | Description |
|------|-------------|
| `-o`, `--output <file>` | Also write results to a file |
| `--field <name>` | Print one field (url, path, fqdn, rdn, dir, file, key, value, kv, …) |
| `--template <str>` | Custom line: `{url} {status} {depth} {source} {tech}` |
| `--forms` | Extract forms as JSON |
| `--tech` | Detect technologies |
| `--json` | JSON Lines output |
| `--store` / `--store-dir <dir>` | Save raw responses to disk |
| `-v` / `-q` / `--no-color` | Verbose / quiet / no colour |
| `--update` | Update spirex to the latest release |
| `--disable-update-check` | Skip the startup version check |
| `--health` | Self-diagnostic (Java/OS/DNS) |
| `-V`, `--version` / `-h`, `--help` | Version / help |

Run `spirex --help` for the complete, grouped list.

---

## How it works

A pool of `-t` worker threads pulls URLs from a shared **frontier** (work queue):
each thread fetches a page, records it, extracts links, and pushes the in-scope
ones back. The frontier's ordering implements the strategy — depth-first pops the
front, breadth-first pops the back. A `ConcurrentHashMap` guarantees each URL is
crawled exactly once even under thread races, and a busy-worker counter detects
completion (queue empty **and** nobody working) so the crawl always terminates
cleanly on cyclic graphs.

Full design — including the termination-correctness argument — is in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Project structure

```
spirex/
├── install.sh / uninstall.sh   # one-command install of the `spirex` launcher
├── build.sh / run.sh / test.sh # compile / run / test
├── README.md
├── docs/ARCHITECTURE.md        # design deep-dive
├── src/spirex/                 # Main, Options, Crawler, HttpFetcher,
│                               # LinkExtractor, Scope, IpUtils, OutputWriter,
│                               # RateLimiter, Result
└── test/spirex/Tests.java      # dependency-free unit + integration tests
```

---

## Testing

```bash
./test.sh        # compiles src + test, runs the suite (non-zero exit on failure)
```

A dependency-free suite (no JUnit) with **80 checks**: unit tests for URL
normalisation, IP/CIDR logic, the full scope model, link/form/JS extraction,
entity decoding, and output fields; plus integration tests that run the real
multithreaded crawler against an embedded `HttpServer` to verify scope, depth
limiting, cyclic-graph termination, and that depth-first and breadth-first reach
the same URL set. Fully offline.

---

## Requirements & notes

- **JDK 21+** — that's the only requirement.
- No JavaScript execution (no headless browser); JS endpoints are mined from
  script text with `--scripts`.
- HTML parsing is regex-based (the zero-dependency constraint).
- Proxies are HTTP-only (the JDK HTTP client has no SOCKS support).

> ⚠️ Only crawl hosts you own or are explicitly authorised to test.
