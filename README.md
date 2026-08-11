# limit-order-book

A limit order book and matching engine written from scratch in Java, with a
Spring Boot service around it and benchmarks that produce real, reproducible
throughput and tail-latency numbers.

- **Price-time priority** — best price first, FIFO within a price level
- **Add / cancel / modify** — O(1) cancel via intrusive linking
- **Partial fills**, limit and market orders, **DAY / IOC / FOK**
- **Multi-symbol**, L2 depth snapshots, execution-report stream
- **Measured**: JMH throughput, HdrHistogram p99.9 tail latency, `-prof gc`
  allocation profile

## Design

The matching engine is plain Java. `engine-core` has no Spring dependency and
does not allocate on its `submit` / `cancel` / `modify` paths — that is what
makes the latency numbers below worth reading. Spring Boot sits around it as
an API and ops layer, fed by a single-consumer command queue so the engine
stays single-threaded.

```
engine-core/    plain Java: Order, PriceLevel, OrderBook, MatchingEngine
engine-bench/   JMH + HdrHistogram harness
engine-api/     Spring Boot: REST, WebSocket, actuator, book viewer
```

Prices are `long` tick counts and quantities are `long` throughout — never
floating point.

### Modify semantics

| Change | Time priority |
|---|---|
| Price changed | **Lost** — re-queued at the back of the new level |
| Quantity increased | **Lost** — re-queued at the back of the level |
| Quantity decreased only | **Kept** — stays in place in the queue |

## Quickstart

Requires a JDK (21+). No Maven install needed — the wrapper bootstraps it.

```bash
git clone https://github.com/thompgt/limit-order-book.git
cd limit-order-book

./mvnw -B verify                              # build + tests
./mvnw -B install -DskipTests                 # put engine-core in the local repo
./mvnw -pl engine-api spring-boot:run         # API + UI on http://localhost:8080
./mvnw -pl engine-bench -am -Pbench verify    # benchmarks
```

The `install` is needed once before `-pl engine-api` will resolve `engine-core`.
On Windows PowerShell use `.\mvnw.cmd` in place of `./mvnw`.

Open <http://localhost:8080> for the book viewer: a live depth ladder, an order
ticket, and the execution tape. It is one static HTML file with no build step —
adding an npm toolchain to a Maven-only repository would cost a second lockfile
and a second thing to break in CI, and this page renders two lists.

## API

| | |
|---|---|
| `POST /api/v1/orders` | submit. `{symbol, side, type, timeInForce, price, quantity, orderId?}` — `price` is required for a `LIMIT` order and must be a positive tick |
| `DELETE /api/v1/orders/{id}` | cancel |
| `PATCH /api/v1/orders/{id}` | modify. `{price, quantity}` — quantity is the new **total** |
| `GET /api/v1/book/{symbol}` | L2 depth, `?levels=N` |
| `GET /api/v1/symbols` | what is trading |
| `ws://…/stream/{symbol}` | execution reports as they happen, plus a depth snapshot every 250ms |
| `GET /actuator/prometheus` | `lob_engine_queue_depth`, `lob_pool_allocations`, `lob_stream_dropped`, … |

A reject carries the status that describes it — duplicate id `409`, unknown
symbol or order `404`, anything else `400` — so a client never has to read a
body to find out whether its order worked. A full command queue is `503` with
`Retry-After`: that is a load condition, not a defect in the request.

```bash
curl -X POST localhost:8080/api/v1/orders -H 'content-type: application/json' \
  -d '{"symbol":"AAPL","side":"SELL","price":100050,"quantity":25,"orderId":11}'
# {"orderId":11,"status":"RESTING","filledQuantity":0,"restingQuantity":25,...}
```

## Results

> Not yet measured. The engine is complete; this table is filled in from real
> benchmark runs in [`docs/WORKPLAN.md`](docs/WORKPLAN.md) phase 4. Every
> number here will carry the exact command that produced it and the machine it
> ran on — nothing goes in this table that a reader cannot re-run.

| Metric | Value | Command |
|---|---|---|
| Throughput (orders/sec) | — | `./mvnw -pl engine-bench -am -Pbench verify` |
| Latency p50 | — | ″ |
| Latency p99 | — | ″ |
| **Latency p99.9** | — | ″ |
| Latency p99.99 | — | ″ |
| Allocation on hot path | — | ″ (`-prof gc`) |

Latency is measured with a fixed-rate submitter and HdrHistogram's
`recordValueWithExpectedInterval`, so the tail is corrected for coordinated
omission rather than flattered by it.

## Skills this project exercises

Each row points at the code that backs it, so the claim can be checked rather
than taken on trust.

| Skill | Where it shows up |
|---|---|
| **Trading systems** | Price-time priority matching, aggressive-order sweeps across levels, partial fills, DAY / IOC / FOK, market orders, and the modify priority rules above — `MatchingEngine`, `OrderBook`, `PriceLevel` |
| **Trade booking** | The execution-report lifecycle: accept → trade → fill / rest / cancel / replace, each event carrying trade id, sequence, price and quantity, emitted in the order it happened — `ExecutionSink`, `SubmitResult`, `CancelResult` |
| **Market data** | L2 depth snapshots aggregated per price level, maintained incrementally so a snapshot is O(1) per level rather than a queue walk — `OrderBook.snapshot`, `DepthVisitor`. Streamed over WebSocket per symbol, with depth sampled on a clock so an unbounded book-change rate becomes a bounded message rate — `MarketDataBroadcaster`, `DepthTicker` |
| **Java** | Java 21, no framework and no Lombok in the core: intrusive doubly-linked lists, an ownership contract on recycled objects, sealed-off package-private mutation, and a test suite that names the semantics it pins — 246 tests, 22 of them property-based with jqwik |
| **Low-latency JVM engineering** | The reason for most of the above: object pooling (`OrderPool`), primitive-keyed maps to avoid boxing (`OrderIndex`), reused result objects, callbacks instead of returned collections, and JMH + HdrHistogram with coordinated-omission correction. `-prof gc` found the ladder allocating 24–36 B/op and drove the swap to primitive-keyed trees — see the `OrderBook` javadoc for what it fixed and what it did not |
| **Spring** | Spring Boot 3.5 as an API and ops layer — REST, WebSocket, actuator and Micrometer gauges, all fed through a single-consumer command queue that keeps the engine single-threaded under concurrent HTTP. Gauges, never timers on the command path: a timer there would measure only the commands that got to run |

The split is deliberate and is the main design idea here: Spring never touches
`engine-core`, because a framework sitting in the measured path would make the
latency numbers meaningless. An ArchUnit test enforces it, and lives in
`engine-api` because that is the only module with Spring on its classpath —
inside `engine-core` it would pass trivially and prove nothing.

## Status

Phases 0–5 complete: scaffold, core data structures, matching,
cancel / modify / time-in-force, benchmarks, and the Spring API with its
WebSocket feed and book viewer. 246 tests green — 204 in `engine-core`, 42 in
`engine-api`. Phase 6 (Docker image, tuning notes) is next.

Progress tracked in [`docs/WORKPLAN.md`](docs/WORKPLAN.md); working conventions
in [`CLAUDE.md`](CLAUDE.md).

## License

MIT — see [LICENSE](LICENSE).
