# limit-order-book

A limit order book and matching engine written from scratch in Java, with a
Spring Boot service around it and benchmarks that produce real, reproducible
throughput and tail-latency numbers.

The whole point of this project is that the performance numbers mean something.
Every structural decision below follows from that.

---

## Features

### Matching semantics

- **Price-time priority.** Better prices match first; within a price level,
  orders match in arrival order (FIFO).
- **Add / cancel / modify.** Cancel is O(1) — orders are intrusively
  doubly-linked inside their price level, so no list scan is needed to unlink.
- **Partial fills.** An aggressive order sweeps as many resting orders as its
  quantity allows; whatever remains rests (or is killed, per its time-in-force).
- **Order types:** limit and market.
- **Time-in-force:** DAY (rest on the book), IOC (fill what you can, cancel the
  remainder), FOK (fill entirely or cancel entirely, no partial).
- **Multi-symbol** — one `OrderBook` per instrument, owned by a registry.
- **L2 depth snapshots** — aggregated quantity per price level, O(1) per level
  via a running total maintained on every mutation.
- **Execution report stream** — every accept / fill / partial-fill / cancel /
  reject emits an event.

### Modify semantics (exact — this is where implementations get it wrong)

| Change | Time priority |
|---|---|
| Price changed (any direction) | **Lost** — re-queued at the back of the new level |
| Quantity **increased** | **Lost** — re-queued at the back of the current level |
| Quantity **decreased** only | **Kept** — stays in place in the queue |

A modify that would cross the book is treated as a new aggressive order at the
new price. These rules are pinned by tests named after the rule they enforce;
do not change them without changing those tests deliberately.

The quantity in a modify is the new **total** order quantity, as originally
submitted — not the new remainder. So "increased" and "decreased" are measured
against the total, and a modify to a total at or below what the order has
already traded is rejected rather than quietly read as a cancel.

### Market orders

A market order is a limit order priced at `Side.marketPrice()` — `Long.MAX_VALUE`
to buy, `Long.MIN_VALUE` to sell. That is not a trick to save a field: it means
the matching loop has no market special case at all, since the sentinel crosses
every resting price and the sweep already stops when the book runs out. A market
order can never rest, so its time-in-force only decides whether a partial fill is
acceptable. The two sentinels are reserved: a limit priced at its own side's
sentinel is rejected.

---

## Tech stack

- **Java 21 language level, running on JDK 24.** `maven.compiler.release=21`.
  Do not raise the release level casually — 21 keeps JMH and the wider
  toolchain on well-trodden ground.
- **Maven multi-module** via the Maven Wrapper.
- **Spring Boot 3.5.x** — `web`, `websocket`, `actuator`. Only in `engine-api`.
- **JMH** for throughput, **HdrHistogram** for latency percentiles.
- **JUnit 5 + AssertJ** for unit tests, **jqwik** for property-based tests.
- **No Lombok.** Annotation processors are the usual breakage point on new JDKs,
  and records cover the DTO cases.

### This machine

`mvn` and `gradle` are **not installed**. Always build with `./mvnw`
(`.\mvnw.cmd` from PowerShell) — the wrapper bootstraps Maven itself. If the
wrapper cannot fetch its distribution, fall back to the Docker image:

```bash
docker run --rm -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-21 mvn -B verify
```

---

## Module layout

```
limit-order-book/
├─ engine-core/    plain Java. ZERO Spring, zero framework on the hot path
├─ engine-bench/   JMH + HdrHistogram; depends on engine-core only
└─ engine-api/     Spring Boot: REST, WebSocket, actuator
```

---

## Invariants

These are load-bearing, not style preferences. An enforcement test guards each.

1. **`engine-core` never depends on Spring** — or on any framework. If a
   benchmark number is to mean anything, no framework may sit in the measured
   path. There is an architecture test asserting this; if it fails, fix the
   dependency, not the test.
2. **The hot path does not allocate.** `submit` / `cancel` / `modify` must be
   ~0 B/op after warmup: pooled order objects, preallocated event buffers,
   primitive-keyed maps. No streams, no `Optional`, no boxing, no
   capturing lambdas in the hot path. Proven by `-prof gc`, not by assertion.
3. **Prices are `long` ticks. Quantities are `long`.** Never `double`, never
   `BigDecimal`, anywhere in `engine-core`. Conversion to a display price
   happens at the API boundary and nowhere else.
4. **The engine is single-threaded by design.** Concurrency is handled by
   feeding it from a single-consumer command queue in `engine-api`. Do not add
   locks inside `engine-core` to "make it thread-safe" — that would trade away
   the thing being measured.
5. **Correct first, fast second.** A clear implementation that passes the
   property tests, then optimize what the benchmarks actually show is hot.

---

## Working expectations

- **Commit and push frequently** — after each small logical unit lands (one
  file, one class, one fix), not batched at the end of a phase. Small, pushed
  commits are the default working style here.
- **Every performance claim in the README must be reproducible** by a named
  `./mvnw` command printed next to it, along with the machine it was measured
  on. No number goes in the README that a reader cannot re-run.
- **Latency measurement must be coordinated-omission safe.** Use HdrHistogram's
  `recordValueWithExpectedInterval` with a fixed-rate submitter. A naive
  measure-loop flatters the tail and makes a p99.9 figure a lie.
- **Update the README in the same commit** as any change to commands, setup, or
  documented behavior.
- **Tests are named after the semantics they pin**, e.g.
  `modifyDecreasingQuantityKeepsTimePriority`.

---

## Commands

```bash
./mvnw -q test                       # unit + property tests
./mvnw -B verify                     # full build, what CI runs
./mvnw -pl engine-api spring-boot:run        # run the API on :8080
./mvnw -pl engine-bench -am -Pbench verify   # JMH throughput + latency + gc profile
```

Phase roadmap and current progress live in [`docs/WORKPLAN.md`](docs/WORKPLAN.md).
