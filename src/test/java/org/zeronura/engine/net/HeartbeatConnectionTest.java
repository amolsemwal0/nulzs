package org.zeronura.engine.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A link's heartbeat: beats travel unseen, and a partner that beat and then went quiet is given up on. */
class HeartbeatConnectionTest {

    private static final long SECOND = 1_000_000_000L;

    private final long[] now = {0L};
    private final Connection[] pair = LocalConnection.pair();
    private final HeartbeatConnection mine = HeartbeatConnection.wrap(pair[0], () -> now[0]);
    private final HeartbeatConnection theirs = HeartbeatConnection.wrap(pair[1], () -> now[0]);

    /** Both ends poll once, so each has heard the other beat. */
    private void meet() {
        mine.poll();
        theirs.poll();
        mine.poll();
    }

    @Test
    void beatsTravelButTheGameNeverSeesThem() {
        assertNull(mine.poll(), "the first poll sends a beat");
        mine.send("HELLO");
        assertEquals("HELLO", theirs.poll());
        assertNull(theirs.poll());
        assertSame(mine, HeartbeatConnection.wrap(mine), "a link is never given two heartbeats");
    }

    @Test
    void aPartnerThatBeatAndThenWentQuietIsGivenUpOn() {
        meet();
        // The other game hangs, or its computer loses its network: it polls, and so beats, no more.
        now[0] += 14 * SECOND;
        assertNull(mine.poll());
        assertTrue(mine.isOpen(), "fourteen seconds of quiet is not yet enough");
        now[0] += 2 * SECOND;
        assertNull(mine.poll());
        assertFalse(mine.isOpen());
        assertTrue(mine.timedOut());
        assertFalse(pair[0].isOpen(), "the link underneath is closed too, so the other end hears of it if it comes back");
    }

    @Test
    void theFirstBeatGoesOutEvenAfterAMessage() {
        Connection[] link = LocalConnection.pair();
        HeartbeatConnection beating = HeartbeatConnection.wrap(link[0], () -> now[0]);
        beating.send("TEAM|x"); // as a friend battle sends its team the moment it starts
        beating.poll();
        assertEquals("TEAM|x", link[1].poll());
        assertEquals(HeartbeatConnection.BEAT, link[1].poll(), "or the other end would never know to expect beats");
    }

    @Test
    void ordinaryMessagesCountAsSignsOfLifeToo() {
        meet();
        for (int i = 0; i < 10; i++) {
            now[0] += 5 * SECOND;
            theirs.send("ACT|" + i); // sent straight, without a poll that would beat
            assertEquals("ACT|" + i, mine.poll());
        }
        assertTrue(mine.isOpen());
    }

    @Test
    void aGameFromBeforeHeartbeatsIsNeverGivenUpOn() {
        Connection[] link = LocalConnection.pair();
        HeartbeatConnection beating = HeartbeatConnection.wrap(link[0], () -> now[0]);
        Connection older = link[1];
        beating.poll();
        assertEquals(HeartbeatConnection.BEAT, older.poll(), "an older game gets beats, and ignores them as lines it doesn't know");
        now[0] += 600 * SECOND; // its player thinks for ten minutes
        older.send("OFFER|x");
        assertEquals("OFFER|x", beating.poll());
        now[0] += 600 * SECOND;
        assertNull(beating.poll());
        assertTrue(beating.isOpen());
        assertFalse(beating.timedOut());
    }

    @Test
    void aStallOnThisSideIsNotTakenForTheOtherGoingQuiet() {
        meet();
        // This game stops running for twenty seconds — its window held by the title bar — while the other beats on.
        for (int i = 0; i < 10; i++) {
            now[0] += 2 * SECOND;
            theirs.poll();
        }
        assertNull(mine.poll(), "the beats that waited on the link are read first");
        assertTrue(mine.isOpen());
    }
}
