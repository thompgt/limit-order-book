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
engine-api/     Spring Boot: REST, WebSocket, actuator
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
./mvnw -pl engine-api spring-boot:run         # API on http://localhost:8080
./mvnw -pl engine-bench -am -Pbench verify    # benchmarks
```

On Windows PowerShell use `.\mvnw.cmd` in place of `./mvnw`.

## Results

> Not yet measured. This table is filled in from real benchmark runs once the
> engine lands (see [`docs/WORKPLAN.md`](docs/WORKPLAN.md) phase 4). Every
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

## Status

Phase 0 (scaffold) complete. Progress tracked in
[`docs/WORKPLAN.md`](docs/WORKPLAN.md); working conventions in
[`CLAUDE.md`](CLAUDE.md).

## License

MIT — see [LICENSE](LICENSE).
