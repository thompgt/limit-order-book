package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Self-trade prevention: what happens when an account reaches its own quote.
 *
 * <p>The default is that nothing happens, and that is deliberate — an order
 * carries no identity unless the boundary supplies one. These tests pin both
 * halves: that the check stays out of the way when it is off, and that each
 * policy cancels the side it says it cancels.
 */
class SelfTradeTest {

    private static final int SYMBOL = 1;
    private static final long ALICE = 7L;
    private static final long BOB = 8L;

    private RecordingSink sink;

    private MatchingEngine engineWith(SelfTradePolicy policy) {
        sink = new RecordingSink();
        MatchingEngine engine = new MatchingEngine(
                sink,
                new OrderPool(),
                MatchingEngine.DEFAULT_MAX_PRICE,
                MatchingEngine.DEFAULT_MAX_QUANTITY,
                policy);
        engine.registerSymbol(SYMBOL);
        return engine;
    }

    @Test
    void anOrderWithNoAccountIsNotTheSameParticipantAsAnotherWithNoAccount() {
        // Two anonymous orders are two participants as far as the engine can
        // tell. Treating NO_ACCOUNT as an identity would stop unrelated
        // clients trading with each other.
        MatchingEngine engine = engineWith(SelfTradePolicy.CANCEL_BOTH);
        engine.submit(1L, SYMBOL, Side.SELL, TimeInForce.DAY, 100L, 10L);

        SubmitResult result = engine.submit(2L, SYMBOL, Side.BUY, TimeInForce.DAY, 100L, 10L);

        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(sink.trades()).hasSize(1);
    }

    @Nested
    class WhenOff {

        @Test
        void anAccountLiftsItsOwnQuote() {
            MatchingEngine engine = engineWith(SelfTradePolicy.OFF);
            engine.submit(1L, SYMBOL, ALICE, Side.SELL, TimeInForce.DAY, 100L, 10L);

            SubmitResult result =
                    engine.submit(2L, SYMBOL, ALICE, Side.BUY, TimeInForce.DAY, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(sink.trades()).hasSize(1);
        }
    }

    @Nested
    class CancelResting {

        @Test
        void theRestingOrderGoesAndTheAggressorCarriesOn() {
            MatchingEngine engine = engineWith(SelfTradePolicy.CANCEL_RESTING);
            engine.submit(1L, SYMBOL, ALICE, Side.SELL, TimeInForce.DAY, 100L, 10L);
            engine.submit(2L, SYMBOL, BOB, Side.SELL, TimeInForce.DAY, 101L, 10L);

            SubmitResult result =
                    engine.submit(3L, SYMBOL, ALICE, Side.BUY, TimeInForce.DAY, 101L, 10L);

            // Alice's own offer is pulled; she trades with Bob one level up.
            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(sink.cancelReason(1L)).isEqualTo(CancelReason.SELF_TRADE);
            assertThat(engine.order(1L)).isNull();
            assertThat(sink.trades()).hasSize(1);
        }

        @Test
        void aWholeLevelOfOwnOrdersIsClearedRatherThanTradedThrough() {
            MatchingEngine engine = engineWith(SelfTradePolicy.CANCEL_RESTING);
            engine.submit(1L, SYMBOL, ALICE, Side.SELL, TimeInForce.DAY, 100L, 5L);
            engine.submit(2L, SYMBOL, ALICE, Side.SELL, TimeInForce.DAY, 100L, 5L);

            SubmitResult result =
                    engine.submit(3L, SYMBOL, ALICE, Side.BUY, TimeInForce.DAY, 100L, 10L);

            assertThat(sink.trades()).isEmpty();
            assertThat(sink.cancelReason(1L)).isEqualTo(CancelReason.SELF_TRADE);
            assertThat(sink.cancelReason(2L)).isEqualTo(CancelReason.SELF_TRADE);
            // Nothing left to trade with, so the remainder rests.
            assertThat(result.status()).isEqualTo(OrderStatus.RESTING);
        }
    }

    @Nested
    class CancelAggressor {

        @Test
        void theSweepStopsAndTheRemainderIsCancelledNotRested() {
            MatchingEngine engine = engineWith(SelfTradePolicy.CANCEL_AGGRESSOR);
            engine.submit(1L, SYMBOL, ALICE, Side.SELL, TimeInForce.DAY, 100L, 10L);

            SubmitResult result =
                    engine.submit(2L, SYMBOL, ALICE, Side.BUY, TimeInForce.DAY, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(sink.cancelReason(2L)).isEqualTo(CancelReason.SELF_TRADE);
            assertThat(sink.trades()).isEmpty();
            // The book is untouched: the resting quote is still there.
            assertThat(engine.book(SYMBOL).bestAsk()).isEqualTo(100L);
        }

        @Test
        void whatTradedAgainstOtherAccountsFirstStillStands() {
            MatchingEngine engine = engineWith(SelfTradePolicy.CANCEL_AGGRESSOR);
            engine.submit(1L, SYMBOL, BOB, Side.SELL, TimeInForce.DAY, 100L, 4L);
            engine.submit(2L, SYMBOL, ALICE, Side.SELL, TimeInForce.DAY, 101L, 6L);

            SubmitResult result =
                    engine.submit(3L, SYMBOL, ALICE, Side.BUY, TimeInForce.DAY, 101L, 10L);

            assertThat(sink.trades()).hasSize(1);
            assertThat(sink.filledQuantity(3L)).isEqualTo(4L);
            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
        }
    }

    @Nested
    class CancelBoth {

        @Test
        void bothOrdersLeaveAndNothingTrades() {
            MatchingEngine engine = engineWith(SelfTradePolicy.CANCEL_BOTH);
            engine.submit(1L, SYMBOL, ALICE, Side.SELL, TimeInForce.DAY, 100L, 10L);

            SubmitResult result =
                    engine.submit(2L, SYMBOL, ALICE, Side.BUY, TimeInForce.DAY, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(sink.cancelReason(1L)).isEqualTo(CancelReason.SELF_TRADE);
            assertThat(sink.cancelReason(2L)).isEqualTo(CancelReason.SELF_TRADE);
            assertThat(sink.trades()).isEmpty();
            assertThat(engine.book(SYMBOL).isEmpty()).isTrue();
        }
    }

    @Test
    void aModifyThatCrossesItsOwnQuoteIsCaughtToo() {
        // A modify into a crossing price is an aggressive order like any
        // other, so the policy has to apply there as well.
        MatchingEngine engine = engineWith(SelfTradePolicy.CANCEL_AGGRESSOR);
        engine.submit(1L, SYMBOL, ALICE, Side.SELL, TimeInForce.DAY, 100L, 10L);
        engine.submit(2L, SYMBOL, ALICE, Side.BUY, TimeInForce.DAY, 90L, 10L);

        SubmitResult result = engine.modify(2L, 100L, 10L);

        assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(sink.cancelReason(2L)).isEqualTo(CancelReason.SELF_TRADE);
        assertThat(engine.order(2L)).isNull();
        assertThat(sink.trades()).isEmpty();
    }
}
