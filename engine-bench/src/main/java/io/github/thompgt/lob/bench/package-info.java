/**
 * Benchmark harness for {@code engine-core}.
 *
 * <p>Three things get measured here, and they answer different questions:
 * JMH throughput (orders/sec), HdrHistogram tail latency (p50 through p99.99),
 * and allocation rate via {@code -prof gc} to show the hot path stays at
 * ~0 B/op.
 *
 * <p>Latency runs must be coordinated-omission safe: a fixed-rate submitter
 * plus {@code Histogram#recordValueWithExpectedInterval}. A naive
 * measure-as-fast-as-you-can loop hides exactly the stalls a p99.9 figure
 * exists to expose.
 */
package io.github.thompgt.lob.bench;
