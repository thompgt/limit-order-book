# Workplan

Phases land in order. Each phase ends green (`./mvnw -B verify`) and pushed.
Commits happen after each small unit inside a phase, not at the phase boundary.

---

## Phase 0 — Scaffold ✅

- [x] Repo, `.gitignore`, `.gitattributes`, MIT license
- [x] `CLAUDE.md` — features, stack, invariants, working expectations
- [x] Maven Wrapper (only-script, 3.9.16) — no local `mvn` on the build machine
- [x] Root aggregator pom; Spring Boot BOM imported, not inherited
- [x] `engine-core` / `engine-bench` / `engine-api` modules, build green
- [x] `README.md` with an unfilled **Results** table
- [x] CI workflow (green on GitHub Actions, JDK 21)
- [x] Public repo pushed — https://github.com/thompgt/limit-order-book

## Phase 1 — Core data structures ✅

- [x] `Order` — mutable, pooled, intrusively doubly-linked (`prev`/`next`)
- [x] `PriceLevel` — FIFO queue at one price + cached `totalQty` for O(1) depth
- [x] `OrderBook` — bid/ask price ladders, best bid/ask, L2 depth snapshot
- [x] `OrderIndex` — primitive `long orderId -> Order` map, no boxing
- [x] Unit tests per class — 48 green in `engine-core`

Ladders are `TreeMap<Long, PriceLevel>` as planned. This boxes a `Long` key
per lookup; the swap to a primitive-keyed sorted structure waits on a phase 4
benchmark saying it is the bottleneck — correct first, fast second.

## Phase 2 — Matching ✅

- [x] Aggressive-order crossing, sweeping multiple levels
- [x] Partial fills; resting remainder
- [x] Execution report events into a preallocated sink
- [x] Property test: filled buy qty == filled sell qty for any order sequence
- [x] Property test: the book is never left crossed (`bestBid < bestAsk`)
- [x] `OrderPool` — orders recycled, so steady-state churn stops allocating
- [x] Tests — 120 green in `engine-core`, 11 of them properties

Both property groups were mutation-checked rather than trusted: filling
newest-first instead of oldest-first, and dropping the limit-price check, each
fail the suite. A property that cannot fail is decoration.

IOC, FOK and market orders were rejected with `UNSUPPORTED_TIME_IN_FORCE` at
this point — refused loudly rather than silently treated as DAY — until phase 3
implemented them.

## Phase 3 — Cancel / modify / time-in-force ✅

- [x] O(1) cancel by order id
- [x] Modify with the exact priority rules in `CLAUDE.md`, each pinned by a
      test named after the rule
- [x] Market orders
- [x] IOC — take available, cancel remainder
- [x] FOK — check fillability before touching the book, then all-or-nothing
- [x] Tests — 195 green in `engine-core`, 22 of them properties

A market order is a limit priced at `Side.marketPrice()`, so the matching loop
needs no market special case: the sentinel crosses everything and the sweep
stops when the book runs out. A *limit* at its own side's sentinel is rejected
with `RESERVED_PRICE` so the encoding stays unambiguous.

FOK asks `OrderBook.fillableQuantity` before touching the book. A partial fill
that then had to be unwound would already have been published to every
consumer, so the only reliable way to emit nothing is never to start.

`LifecycleProperties` runs the same mutation check phase 2 used, over mixed
streams of every command type. Dropping the index removal from cancel fails all
11 properties; skipping the unlink before a modify re-adds fails all 11;
shrinking an order without adjusting its level's cached total fails exactly the
one property written for it.

## Phase 4 — Benchmarks

- [ ] JMH throughput across book-depth and order-mix scenarios
- [ ] HdrHistogram latency harness, coordinated-omission safe
      (fixed-rate submitter + `recordValueWithExpectedInterval`)
- [ ] `-prof gc` demonstrating ~0 B/op on the hot path
- [ ] End-to-end REST/WebSocket load generator for client-observed latency
- [ ] README **Results** table filled from real runs, with commands and
      machine specs

## Phase 5 — Spring API

- [ ] `POST /api/v1/orders`, `DELETE /api/v1/orders/{id}`,
      `PATCH /api/v1/orders/{id}`
- [ ] `GET /api/v1/book/{symbol}` — L2 depth snapshot
- [ ] WebSocket `/stream/{symbol}` — executions and depth updates
- [ ] Single-consumer command queue keeping the engine single-threaded
- [ ] Actuator health + Micrometer metrics
- [ ] Architecture test: no framework type reachable from `engine-core`

## Phase 6 — Polish

- [ ] Docker image
- [ ] Sample client / demo script
- [ ] Book-state visualization
- [ ] Tuning notes: what actually moved the tail latency, with before/after

---

## Verification checklist

- `./mvnw -q test` green
- `./mvnw -pl engine-bench -am -Pbench verify` produces a percentile dump; the
  p99.9 in the README is read off that output
- API smoke: submit crossing orders → observe a partial fill on the stream →
  cancel the resting remainder → confirm depth updates
- Deterministic replay: a fixed-seed order sequence produces a byte-identical
  execution-report log across runs. Fastest way to catch a priority regression.
- CI green on GitHub
