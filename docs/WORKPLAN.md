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

## Phase 2 — Matching

- [ ] Aggressive-order crossing, sweeping multiple levels
- [ ] Partial fills; resting remainder
- [ ] Execution report events into a preallocated sink
- [ ] Property test: filled buy qty == filled sell qty for any order sequence
- [ ] Property test: the book is never left crossed (`bestBid < bestAsk`)

## Phase 3 — Cancel / modify / time-in-force

- [ ] O(1) cancel by order id
- [ ] Modify with the exact priority rules in `CLAUDE.md`, each pinned by a
      test named after the rule
- [ ] Market orders
- [ ] IOC — take available, cancel remainder
- [ ] FOK — check fillability before touching the book, then all-or-nothing

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
