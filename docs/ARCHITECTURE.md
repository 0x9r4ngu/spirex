# spirex — Architecture & Design

This document explains how spirex is built: the design goals, the concurrency
model, every class and its responsibilities, the key algorithms, and the choices
behind them. It complements the user-facing [`README.md`](../README.md).

---

## 1. Design goals

1. **Pure Java, zero dependencies.** Everything is built on the JDK standard
   library so the project compiles with nothing but `javac` and runs with nothing
   but `java`. This is the central constraint and it shapes several decisions
   (regex parsing instead of a DOM library, a hand-written test runner instead of
   JUnit, an HTTP-only proxy because the JDK client lacks SOCKS).
2. **Genuinely multithreaded.** The crawl must scale across many worker threads
   with correct, race-free coordination — not a fake "parallel" loop. This is the
   heart of the program.
3. **Correct termination.** A crawler walks a cyclic graph; it must always finish,
   exactly once per URL, with no deadlock and no premature exit.
4. **Configurable and predictable.** Depth, scope, strategy, rate, and output are
   all controllable, and the same inputs always produce the same result set.
5. **Single responsibility per class.** Each file does one thing, which keeps the
   engine readable and testable.

---

## 2. High-level data flow

```
              seeds
                │
                ▼
        ┌───────────────┐      enqueue (normalise → dedup → scope → exclude)
        │    Crawler    │◄──────────────────────────────────────────┐
        │  (frontier)   │                                            │
        └───────┬───────┘                                            │
                │ next()                                             │
       ┌────────┼────────┬────────┐                                  │
       ▼        ▼        ▼        ▼                                  │
   ┌───────┐┌───────┐┌───────┐┌───────┐   N worker threads          │
   │worker ││worker ││worker ││worker │                              │
   └───┬───┘└───┬───┘└───┬───┘└───┬───┘                              │
       │        │        │        │                                  │
       ▼        ▼        ▼        ▼                                  │
   RateLimiter.acquire()  →  HttpFetcher.fetch()                     │
       │                                                             │
       ▼                                                             │
   OutputWriter.write()   ◄── tech-detect, forms                    │
       │                                                             │
       ▼                                                             │
   LinkExtractor.extract()  ── discovered links ───────────────────-┘
```

A worker pulls a URL, fetches it, writes the result, extracts links, and feeds
the in-scope ones back into the frontier. The loop ends when the frontier is
empty and every worker is idle.

---

## 3. Concurrency model (the core)

### 3.1 The frontier

The work queue is a single inner class, `Crawler.Frontier`, wrapping an
`ArrayDeque<Node>` guarded by the object monitor. It serves three roles at once:
the pending-work queue, the strategy selector, and the termination detector.

```java
private static final class Frontier {
    private final ArrayDeque<Node> dq = new ArrayDeque<>();
    private final boolean bfs;
    private int busy = 0;
    private boolean done = false;

    synchronized void add(Node n) {
        if (done) return;
        if (bfs) dq.addLast(n); else dq.addFirst(n);
        notifyAll();
    }

    synchronized Node next() {
        while (true) {
            if (done) return null;
            Node n = dq.pollFirst();
            if (n != null) { busy++; return n; }   // claimed → worker is busy
            if (busy == 0) { done = true; notifyAll(); return null; }
            wait();                                 // queue empty, others busy
        }
    }

    synchronized void complete() {
        busy--;
        if (busy == 0 && dq.isEmpty()) { done = true; notifyAll(); }
    }

    synchronized void stop() { done = true; notifyAll(); }
}
```

### 3.2 Strategy = which end we use

`next()` always pops the **front** of the deque. The only difference between
strategies is where `add()` puts new work:

- **Depth-first** (`addFirst`): newly found links go to the front and are picked
  up immediately → the crawler dives deep along one branch first (LIFO stack).
- **Breadth-first** (`addLast`): new links go to the back → the crawler finishes
  the current level before descending (FIFO queue).

One data structure, two behaviours, selected by a boolean.

### 3.3 Termination — why it is correct

The invariant: **`busy` counts URLs that have been handed to a worker but not yet
finished processing.**

- A worker increments `busy` inside `next()` at the moment it takes a node.
- A worker decrements `busy` inside `complete()` after it finishes (including
  after it has enqueued any children it found).

From this, two facts follow:

1. **No premature exit.** While a worker is still processing a page, `busy > 0`.
   Any children it discovers are added *before* it calls `complete()`. So the
   condition `busy == 0 && queue empty` can only be true when there is genuinely
   no work left and none in flight. The crawl cannot stop with work pending.

2. **No deadlock / no missed wake-up.** A worker that finds the queue empty but
   `busy > 0` calls `wait()`. Every `add()` and the terminal branch of `next()`
   call `notifyAll()`. When the last busy worker finishes, `complete()` sets
   `done = true` and notifies; every waiting worker then re-checks, sees `done`,
   and returns `null`, exiting its loop. The main thread `join()`s all workers
   and the program ends.

Because all mutation of `dq`, `busy`, and `done` happens inside `synchronized`
methods on the same monitor, there are no visibility or interleaving hazards.

### 3.4 Exactly-once crawling

A `ConcurrentHashMap.newKeySet()` named `visited` holds the canonical form of
every URL ever queued. The claim is atomic:

```java
if (!visited.add(dedupKey(norm))) return;   // someone already took it
```

`Set.add` returns `false` if the element was present. Two threads racing on the
same link will both call `add`; exactly one gets `true` and proceeds to enqueue,
the other bails. No locks, no double-fetch.

### 3.5 The worker loop

```java
private void workerLoop() {
    Node n;
    while ((n = frontier.next()) != null) {
        try { process(n); }
        catch (Throwable t) { output.error(...); logError(...); }
        finally { frontier.complete(); }   // always balances the busy++ in next()
    }
}
```

`complete()` is in a `finally` block, so a thrown request (timeout, connection
reset, malformed URL) can never leak a `busy` count and stall termination.

---

## 4. Class reference

| Class | Responsibility |
|-------|----------------|
| **`Main`** | Parse the command line into `Options`; print help / version / health-check; launch `Crawler`. |
| **`Options`** | Plain data object holding every configuration field, grouped by section. Populated by `Main`, read-only thereafter. |
| **`Crawler`** | Owns the worker pool and the frontier. Implements seeding, the worker loop, `process()`, `enqueue()`, dedup-key construction, known-files seeding, path-climb, response storage, the crawl-duration watchdog, URL normalisation, and termination. |
| **`HttpFetcher`** | Wraps one shared `HttpClient`. Applies timeout, retries, redirect policy, proxy, custom headers, and the max-response-size cap. Returns a `Response` record. |
| **`LinkExtractor`** | Stateless extractor. Pulls links from HTML attributes, `<base>`, sitemap `<loc>`, robots directives, and (optionally) JavaScript; extracts forms; fingerprints technologies. |
| **`Scope`** | Decides `inScope(uri)` (host modes + include/exclude regex) and `isExcluded(uri)` (the `-e` filters). Computes registrable domains. |
| **`IpUtils`** | IPv4 parsing, CIDR membership, private-range detection for the `-e` filters. |
| **`OutputWriter`** | Thread-safe rendering to stdout and/or a file. Field projections, output templates, match/filter/extension rules, JSON Lines, form output, no-clobber. |
| **`RateLimiter`** | Global pacing: folds `--rate` and `--delay` into one minimum inter-request interval. |
| **`Result`** | Immutable record: url, source, depth, status, content-type, tech, out-of-scope flag. |

---

## 5. Key algorithms

### 5.1 URL normalisation (`Crawler.normalize`)

Produces a canonical string used for both de-duplication and output:

- reject anything that isn't absolute `http`/`https`;
- lower-case the scheme and host;
- drop the default port (`:80` for http, `:443` for https);
- drop the fragment (`#...`);
- ensure the path is at least `/`;
- preserve the query string verbatim.

So `HTTP://Example.COM:80/a#x` and `http://example.com/a` collapse to the same
key and are crawled once.

### 5.2 De-duplication key (`Crawler.dedupKey`)

The canonical URL is transformed before it goes into `visited`, so two options
can widen what counts as "the same":

- **`--no-query`:** strip everything from `?` onward, so
  `/search?q=a` and `/search?q=b` are one entry.
- **`--fold-similar`:** split the path on `/` and replace any segment that
  looks like an identifier — all digits, a long hex string, or a long
  token — with `{}`. Thus `/users/123/posts` and `/users/456/posts` both become
  `/users/{}/posts` and only one is crawled.

### 5.3 Scope decision (`Scope.inScope`)

Order of evaluation:

1. Must be `http`/`https` with a host.
2. If any `--out-scope` exclude regex matches the URL → out.
3. If `--in-scope` include regexes exist, at least one must match.
4. Apply the host mode derived from `--scope`:
   - `ALL` (`--scope any` or a custom regex) → in;
   - `STRICT` (`dn`/`fqdn`) → host must equal the seed host;
   - `SUBS` (`rdn`, default) → host equals or is a subdomain of the seed's
     registrable domain.

**Registrable domain** (`rootDomain`) takes the last two labels, but keeps three
when the last two are a known second-level suffix (`co.uk`, `com.au`, …).

### 5.4 Exclude filters (`Scope.isExcluded`, `IpUtils`)

Each `-e` value is parsed into one of five kinds:

| Kind | Match logic |
|------|-------------|
| `cdn` | host ends with a known CDN suffix |
| `private-ips` | host resolves to a loopback / link-local / site-local address |
| CIDR (`10.0.0.0/8`) | host's address falls inside the range (bitmask compare) |
| IP (`1.2.3.4`) | host equals the literal IP |
| regex | the URL or host matches the pattern |

DNS resolution is **lazy** — only the `private-ips` and CIDR kinds trigger it, so
crawls that don't use those filters never pay for lookups, and the tests run
fully offline.

### 5.5 Rate limiting (`RateLimiter`)

The three pacing flags are reduced to one minimum interval:

```
interval = max( 1e9 / rl ,  60e9 / rlm ,  rd · 1e9 )   nanoseconds
```

`acquire()` keeps a `nextAllowed` timestamp under a lock; each caller reserves the
next slot and sleeps until it arrives. Because the reservation is atomic, N
threads calling concurrently are still globally paced to the configured rate.

### 5.6 Link extraction (`LinkExtractor`)

Regex-based, with deliberately bounded character classes (no nested unbounded
quantifiers) to avoid catastrophic backtracking on hostile input:

- **Attributes** — a single pattern captures `href`, `src`, `action`,
  `formaction`, `data-url`, `data-href`, `poster`, in quoted or bare form.
- **`<base href>`** — read first, so relative links resolve against the right base.
- **Sitemap** — `<loc>…</loc>` entries when the body looks like XML.
- **robots.txt** — `Disallow` / `Allow` / `Sitemap` directives become URLs.
- **JavaScript** — quoted absolute or root-relative path-like strings, only when
  `--scripts` is set.

Each candidate is sanitised (srcset lists trimmed to the first token, spaces
encoded), resolved against the base URI, normalised, and emitted only if it is an
absolute http(s) URL. `mailto:`, `tel:`, `javascript:`, `data:`, and bare
fragments are dropped.

### 5.7 Path climb (`--climb`)

For a fetched URL, every parent directory is also queued
(`/a/b/c` → `/a/b/`, `/a/`), which surfaces index pages and directory listings
the link graph might not reference directly.

### 5.8 Crawl-duration budget (`--max-time`)

`parseDuration` converts `30s`/`5m`/`1h`/`1d` into a deadline. A daemon watchdog
thread flips the frontier to `stop()` when the deadline passes; workers then
drain and exit.

---

## 6. Output pipeline (`OutputWriter`)

Every result passes through, in order:

1. **Match / filter regex** (`-m` / `-f`).
2. **Extension match / filter** (`--ext-only` / `--ext-skip`, plus the default filter which is
   off by default so assets still surface).
3. **Rendering** — JSON Lines (`-j`), a custom template (`-ot`), or a plain field
   projection (`-f`, default `url`), with an optional colourised status prefix in
   verbose mode.

All public methods are `synchronized`, so concurrent workers never interleave a
half-written line, and the optional file writer stays consistent. JSON values are
escaped manually (no JSON library), and field projections (`path`, `fqdn`,
`rurl`, `dir`, query `key`/`value`/`kv`, …) are derived from the parsed URI.

---

## 7. Robustness

- **Per-request:** connect/read timeout, configurable retries, redirect policy
  (`--no-redirect`), body-size cap (`--max-size`).
- **Per-worker:** `process()` is wrapped so any exception is logged (and optionally
  written to `-elog`) without affecting other workers or termination.
- **Restricted headers:** the JDK rejects setting `Host`, `Connection`, etc.; the
  fetcher skips those silently instead of crashing.
- **Bad input:** malformed URLs are dropped at normalisation; unparseable flags
  produce a clear error and a non-zero exit.

---

## 8. Intentionally out of scope

spirex deliberately keeps a focused, standard-crawl feature set. A few
capabilities are out of scope by design, because they would either break the
pure-JDK / zero-dependency constraint or add weight without serving the core job:

- **JavaScript execution / headless browsing** — would require embedding and
  driving a Chromium instance. spirex reads response text only; `--scripts` still
  mines endpoint-looking strings out of JavaScript source.
- **TLS fingerprint (JA3) control** — needs control over the TLS ClientHello that
  the JDK socket layer doesn't expose.
- **SOCKS proxies** — the JDK HTTP client supports HTTP proxies only (`-x`).
- **A DOM parser** — extraction is regex-based to avoid a third-party dependency.

These boundaries keep the binary a single self-contained jar that builds with
nothing but `javac`.

---

## 9. Testing strategy

`test/spirex/Tests.java` is a self-contained runner (a `check`/`eq` helper plus a
pass/fail tally) so the project needs no test framework.

- **Unit tests** target the pure, deterministic logic — normalisation, IP/CIDR
  math, every scope branch and exclude filter, all extraction patterns, each
  output-field projection, and rate-limiter timing.
- **Integration tests** start an in-process `com.sun.net.httpserver.HttpServer`
  serving a tiny site whose link graph contains a cycle, a duplicate link, and an
  excluded branch. The real `Crawler` runs against `127.0.0.1`, and the tests
  assert the exact crawled set, scope exclusion, depth limiting, **clean
  termination on a cyclic graph**, and that depth-first and breadth-first yield
  the same set. Capturing `System.out` lets the tests inspect real output without
  network access.

---

## 10. Extending spirex

Common changes and where they go:

- **A new output field** → add a case to `OutputWriter.fieldValue` and list it in
  `Main` (`-lof`).
- **A new link source** → add a pattern and a loop to `LinkExtractor.extract`.
- **A new scope rule** → extend `Scope.inScope` or add an `Exclusion` kind.
- **A new flag** → add a field to `Options`, a `case` to `Main.parse`, a help line
  to the usage block, and wire it where it acts.
- **A different queue discipline** (e.g. priority by depth) → swap the `ArrayDeque`
  inside `Frontier`; the termination logic is independent of ordering.
