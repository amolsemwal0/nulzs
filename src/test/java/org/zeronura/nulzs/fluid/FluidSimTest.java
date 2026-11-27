package org.zeronura.nulzs.fluid;

import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.physics.SoftBodyMaterial;
import org.zeronura.nulzs.physics.SolidField;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The volumetric fluid rules, against analytic terrain.
 *
 * <p>Everything here is lambdas — a floor, a ledge, a sealed cavern — with no world, no terrain generator
 * and no GL, which is the point of the sim taking {@link SolidField} and {@link HeightField} rather than
 * anything from the game. The three properties worth the most are conservation (a rule that loses mass
 * looks fine for a few seconds and empties a lake over a minute), that nothing ever enters rock, and the
 * two things a height field could not do at all: fluid falling off a ledge, and fluid getting into a cave.
 */
class FluidSimTest {

    /** A springy, lightly damped skin — water's shape. See {@link FluidSurfaceTest} for what it does. */
    private static final SoftBodyMaterial SUPPLE = new SoftBodyMaterial("Supple", 0.7f, 1.1f, 6f);

    private static final FluidType WATER = new FluidType("Water", 0, 0.5f, 0f, 0f, SUPPLE);
    /** A cooling, slow, supplied fluid — lava's shape, without importing anything that knows about lava. */
    private static final FluidType MOLTEN = new FluidType("Molten", 1, 0.22f, 0.5f, 1.5f, SUPPLE);

    private static final float FRAME = 1f / 60f;

    /** Ground at y < 0 and nothing else: an open plain to drop fluid onto. */
    private static SolidField floorAt(float height) {
        return (x, y, z) -> y < height;
    }

    private static FluidSim sim(SolidField terrain) {
        return new FluidSim(terrain, (x, z) -> 0f, HeightField.NONE, PooledFluid.of(WATER), WATER, MOLTEN);
    }

    private static void run(FluidSim sim, int frames) {
        for (int i = 0; i < frames; i++) {
            sim.advance(FRAME, 0f, 0f);
        }
    }

    /**
     * The property a leaking rule hides best: it looks convincing for a few seconds and quietly empties a
     * lake over a minute of play.
     */
    @Test
    void flowConservesMass() {
        FluidSim sim = sim(floorAt(0f));
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                sim.addFluid(x + 0.5f, 6.5f, z + 0.5f, 0.8f, WATER);
            }
        }
        sim.advance(0.0, 0f, 0f);
        float before = sim.totalVolume();
        assertTrue(before > 0f, "the test put nothing in");

        run(sim, 240);
        assertEquals(before, sim.totalVolume(), before * 1e-3f, "fluid was created or lost by the flow");
    }

    /** Nothing may end up inside rock, however hard it is pushed against it. */
    @Test
    void fluidNeverEntersSolidGround() {
        FluidSim sim = sim(floorAt(0f));
        for (int i = 0; i < 40; i++) {
            sim.addFluid(0.5f, 0.5f, 0.5f, 1.0f, WATER);   // pile far more than a cell can hold
        }
        run(sim, 120);

        for (int y = -6; y < 0; y++) {
            assertEquals(0f, sim.depthAt(0.5f, y + 0.5f, 0.5f), 1e-6f,
                    "fluid got into rock at y=" + y);
        }
    }

    /**
     * Gravity: fluid left in the air lands, rather than hanging where it was dropped.
     *
     * <p>Measured as volume in a box, not as {@link FluidSim#depthAt} at a point. A bucket of water on an
     * open plain genuinely does spread into a film thinner than {@link FluidSim#WET_EPSILON}, so a
     * point probe reads dry and says nothing about where the mass went — which is how the first version of
     * this test managed to fail against a simulation that was working perfectly.
     */
    @Test
    void fluidFallsToTheGround() {
        FluidSim sim = sim(floorAt(0f));
        sim.addFluid(0.5f, 20.5f, 0.5f, 1.0f, WATER);
        run(sim, 120);

        assertEquals(0f, sim.volumeIn(-30f, 5f, -30f, 30f, 40f, 30f), 1e-4f, "fluid stayed up in the air");
        assertEquals(1.0f, sim.volumeIn(-30f, 0f, -30f, 30f, 5f, 30f), 1e-3f,
                "fluid did not arrive on the ground");
    }

    /**
     * A waterfall — the first of the two things the 2.5D height field could not represent at all.
     *
     * <p>A column has one surface, so fluid at the top of a cliff simply stopped at the edge. Here it has
     * to leave the ledge, cross the drop and arrive on the ground below.
     */
    @Test
    void fluidPouredOnALedgeFallsOffTheEdge() {
        // A shelf at y<10 for x<0, and ground at y<0 everywhere: a cliff face at x=0.
        SolidField cliff = (x, y, z) -> y < 0f || (x < 0f && y < 10f);
        FluidSim sim = sim(cliff);

        for (int i = 0; i < 60; i++) {
            sim.addFluid(-2.5f, 10.5f, 0.5f, 0.6f, WATER);
            sim.advance(FRAME, 0f, 0f);
        }
        run(sim, 300);

        float onTheShelf = sim.volumeIn(-40f, 10f, -40f, 0f, 40f, 40f);
        float belowTheCliff = sim.volumeIn(-40f, -1f, -40f, 60f, 10f, 40f);

        assertTrue(belowTheCliff > onTheShelf,
                "most of the fluid should have gone over the edge; shelf=" + onTheShelf
                        + " below=" + belowTheCliff);
        assertTrue(belowTheCliff > 1f, "nothing reached the bottom of the cliff at all");
    }

    /**
     * A flooded cavern — the second thing a height field could not represent.
     *
     * <p>One surface per column means a pond on the roof and a pool in the cave under it are the same
     * number, so this could not even be expressed before. Here fluid poured down a shaft has to end up in
     * the cavern, <b>under</b> solid rock.
     */
    @Test
    void fluidPouredDownAShaftFillsTheCaveBeneath() {
        // Rock everywhere below y=0 except a cavern (-10..-4) and a shaft at (0,0) connecting it to the top.
        SolidField cave = (x, y, z) -> {
            if (y >= 0f) {
                return false;
            }
            boolean inCavern = y > -10f && y < -4f
                    && x > -6f && x < 6f && z > -6f && z < 6f;
            boolean inShaft = x > -1f && x < 1f && z > -1f && z < 1f && y > -10f;
            return !(inCavern || inShaft);
        };
        FluidSim sim = sim(cave);

        for (int i = 0; i < 200; i++) {
            sim.addFluid(0.5f, 1.5f, 0.5f, 0.5f, WATER);
            sim.advance(FRAME, 0f, 0f);
        }
        run(sim, 400);

        float inCavern = 0f;
        for (int x = -5; x < 5; x++) {
            for (int z = -5; z < 5; z++) {
                for (int y = -10; y < -4; y++) {
                    inCavern += sim.depthAt(x + 0.5f, y + 0.5f, z + 0.5f) > 0f
                            ? 1f : 0f;
                }
            }
        }
        assertTrue(inCavern > 20f, "the cavern did not flood; wet cells=" + inCavern);
        assertNotNull(sim.fluidAt(0.5f, -9.5f, 0.5f), "the bottom of the shaft should be wet");
    }

    /**
     * Communicating vessels: fluid poured down one shaft has to rise in the other.
     *
     * <p>This is what the compression term buys, and it is the reason a purely downhill-and-sideways rule
     * is not enough — without an upward push everything drains to the lowest cell and no closed vessel ever
     * fills. It is also what makes a basin pond flat instead of pooling in its deepest corner.
     */
    @Test
    void fluidRisesInAConnectedShaft() {
        // Two shafts at x=-4 and x=+4 joined by a pipe along the bottom, all inside rock.
        SolidField uBend = (x, y, z) -> {
            if (y >= 0f || y < -12f) {
                return y >= 0f ? false : true;
            }
            boolean leftShaft = x > -5f && x < -3f && z > -1f && z < 1f;
            boolean rightShaft = x > 3f && x < 5f && z > -1f && z < 1f;
            boolean pipe = y > -12f && y < -10f && x > -5f && x < 5f && z > -1f && z < 1f;
            return !(leftShaft || rightShaft || pipe);
        };
        FluidSim sim = sim(uBend);

        for (int i = 0; i < 300; i++) {
            sim.addFluid(-4.5f, -1.5f, -0.5f, 0.5f, WATER);
            sim.advance(FRAME, 0f, 0f);
        }
        run(sim, 600);

        float leftTop = topOfColumn(sim, -4.5f, -0.5f);
        float rightTop = topOfColumn(sim, 4.5f, -0.5f);
        assertTrue(rightTop > -10f, "nothing rose in the far shaft at all (top=" + rightTop + ")");
        assertEquals(leftTop, rightTop, 2.0f,
                "connected shafts must come to nearly the same level; left=" + leftTop + " right=" + rightTop);
    }

    /** The highest wet cell in a column, or -100 if it is dry. */
    private static float topOfColumn(FluidSim sim, float x, float z) {
        for (int y = 0; y > -13; y--) {
            if (sim.depthAt(x, y - 0.5f, z) > 0f) {
                return y - 0.5f;
            }
        }
        return -100f;
    }

    /** A basin has to pond level, not pile up wherever it was poured. */
    @Test
    void aBasinPondsToOneLevel() {
        // A 9x9 bowl with walls, floor at y=0.
        SolidField bowl = (x, y, z) -> y < 0f || x < -4f || x > 5f || z < -4f || z > 5f;
        FluidSim sim = sim(bowl);

        for (int i = 0; i < 200; i++) {
            sim.addFluid(-3.5f, 3.5f, -3.5f, 0.9f, WATER);   // poured into one corner
            sim.advance(FRAME, 0f, 0f);
        }
        run(sim, 900);

        float corner = sim.depthAt(-3.5f, 0.05f, -3.5f);
        float opposite = sim.depthAt(4.5f, 0.05f, 4.5f);
        assertTrue(corner > 0.2f && opposite > 0.2f,
                "the pool did not spread across the basin: " + corner + " vs " + opposite);
        assertEquals(corner, opposite, 0.35f,
                "a settled basin must be level; poured corner=" + corner + " far corner=" + opposite);
    }

    /** A settled pond must stop costing anything — the whole reason a volumetric sim is affordable here. */
    @Test
    void aSettledPoolStopsBeingSimulated() {
        SolidField bowl = (x, y, z) -> y < 0f || x < -3f || x > 4f || z < -3f || z > 4f;
        FluidSim sim = sim(bowl);
        for (int x = -3; x < 4; x++) {
            for (int z = -3; z < 4; z++) {
                sim.addFluid(x + 0.5f, 0.5f, z + 0.5f, 0.9f, WATER);
            }
        }
        run(sim, 600);

        // Volume alone proves nothing here — it is conserved whether or not anything is moving. What
        // matters is that the sim has stopped *visiting* the pool: that is the whole reason a volumetric
        // fluid is affordable, and it is the one thing the height field this replaced could never do,
        // since it swept its entire window every frame regardless.
        assertEquals(0, sim.activeChunkCount(), "a settled pool is still being simulated every frame");
    }

    /** Pausing must freeze fluid exactly as it freezes the sky and the animation clocks. */
    @Test
    void aZeroDeltaChangesNothing() {
        FluidSim sim = sim(floorAt(0f));
        sim.addFluid(0.5f, 8.5f, 0.5f, 1.0f, WATER);
        sim.advance(FRAME, 0f, 0f);
        float paused = sim.depthAt(0.5f, 8.5f, 0.5f);

        for (int i = 0; i < 30; i++) {
            sim.advance(0.0, 0f, 0f);
        }
        assertEquals(paused, sim.depthAt(0.5f, 8.5f, 0.5f), 1e-6f, "fluid moved while the game was paused");
    }

    /**
     * A source keeps producing, and cooling is what stops its flow growing without bound.
     *
     * <p>Without the sink, a fed source piles up forever — the window's edge clamps flux and nothing drains
     * away — so this pins that the two rates reach a balance rather than a ramp.
     */
    @Test
    void afedSourceReachesASteadyStateRatherThanGrowingForever() {
        FluidSim sim = new FluidSim(floorAt(0f), (x, z) -> 0f, HeightField.NONE,
                PooledFluid.of(WATER), WATER, MOLTEN);
        sim.addSource(new FluidSource() {
            @Override
            public float supplyLevelAt(float x, float z) {
                return (x > -2f && x < 2f && z > -2f && z < 2f) ? 2f : Float.NEGATIVE_INFINITY;
            }

            @Override
            public FluidType fluid() {
                return MOLTEN;
            }
        });

        run(sim, 60);
        float early = sim.totalVolume();
        run(sim, 600);
        float mid = sim.totalVolume();
        run(sim, 600);
        float late = sim.totalVolume();

        assertTrue(early > 0f, "the source produced nothing");
        assertTrue(late < mid * 1.25f,
                "a fed source is still growing rather than balancing against cooling: "
                        + early + " -> " + mid + " -> " + late);
    }

    /**
     * Cooling must not run when no source is resident.
     *
     * <p>Gated wrongly, this is not a subtle bug: it deletes every natural pool of a cooling fluid anywhere
     * in the world, wherever it is, because cooling was switched on globally the moment a vent existed.
     * Unfed molten rock solidifying in place is also the physical reading — it does not evaporate.
     */
    @Test
    void unfedCoolingFluidIsLeftAloneRatherThanEvaporating() {
        FluidSim sim = sim(floorAt(0f));   // no sources at all
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                sim.addFluid(x + 0.5f, 0.5f, z + 0.5f, 0.9f, MOLTEN);
            }
        }
        run(sim, 60);
        float settled = sim.totalVolume();
        run(sim, 1200);

        assertEquals(settled, sim.totalVolume(), settled * 1e-3f,
                "a pool with no source resident evaporated instead of staying put");
    }

    /**
     * Meshing must not disturb the chunk map it is walking.
     *
     * <p>The crash that shipped: {@link FluidSim#buildSurface} iterates the chunks, and emitting a side
     * face asks the terrain about the cell beside it — which used to <i>create</i> a chunk to cache the
     * answer, inserting into the map mid-iteration. Every frame of the real game hit it and the process
     * died with a {@code ConcurrentModificationException} the moment a world loaded.
     *
     * <p>Reproduced by putting fluid hard against a 16-cell chunk boundary with nothing beyond it, which is
     * the case that forces the question about an unloaded chunk. Two details are needed to make it fire
     * reliably, and both are why a first attempt at this test passed against the broken code:
     * <ul>
     *   <li><b>Several wet chunks</b>, not one. A map with a single entry finishes iterating before it ever
     *       looks again, so the insertion goes unnoticed; with more to visit, the next step throws.</li>
     *   <li><b>No stepping first.</b> The flow rules create the neighbouring chunks themselves, so any test
     *       that runs the simulation before meshing has already loaded the chunk that would be inserted —
     *       which is exactly why every other test here sailed past this.</li>
     * </ul>
     */
    @Test
    void buildingTheSurfaceDoesNotDisturbTheChunkMapItIsWalking() {
        FluidSim sim = sim(floorAt(0f));
        // Five isolated cells, each at the +X edge of its own chunk with nothing loaded beyond it.
        for (int i = 0; i < 5; i++) {
            sim.addFluid(15.5f, 0.5f, i * 32 + 0.5f, 0.9f, WATER);
        }

        java.nio.FloatBuffer out =
                java.nio.FloatBuffer.allocate(FluidSim.MAX_VERTICES * FluidSim.FLOATS_PER_VERTEX);
        int vertices = sim.buildSurface(out);

        assertTrue(vertices > 0, "a wet cell against a chunk boundary must still produce faces");
    }

    /** A cell's fluid is whatever brought the most mass, so a flow keeps its identity as it travels. */
    @Test
    void aFlowKeepsItsOwnFluidAsItSpreads() {
        FluidSim sim = sim(floorAt(0f));
        sim.addFluid(0.5f, 4.5f, 0.5f, 3.0f, MOLTEN);
        run(sim, 240);

        assertSame(MOLTEN, sim.fluidAt(0.5f, 0.2f, 0.5f), "the flow changed fluid on the way down");
    }

    /** Depth is answered per body, not per column: a pond and a flooded cave below it are different water. */
    @Test
    void depthIsMeasuredInTheBodyYouAreStandingIn() {
        // A walled basin on top of a solid shelf, with a sealed cavity underneath it. Both are enclosed on
        // purpose: an open shelf lets a puddle spread to a film below WET_EPSILON, which reads dry at every
        // point and would make this test fail against a simulation doing exactly the right thing.
        SolidField layered = (x, y, z) -> {
            boolean insideFootprint = x > -1f && x < 2f && z > -1f && z < 2f;
            if (y >= 0f) {
                return !insideFootprint && y < 4f;   // walls around the basin
            }
            if (y > -2f) {
                return true;                         // the shelf the basin sits on
            }
            if (y > -6f) {
                return !insideFootprint;             // a sealed cavity under the shelf
            }
            return true;                             // bedrock
        };
        FluidSim sim = sim(layered);
        for (int i = 0; i < 5; i++) {
            sim.addFluid(0.5f, 0.5f, 0.5f, 0.2f, WATER);        // a puddle in the basin
            sim.addFluid(0.5f, -5.5f, 0.5f, 0.2f, WATER);       // separate water in the cavity
            sim.advance(FRAME, 0f, 0f);
        }

        assertTrue(sim.depthAt(0.5f, 0.05f, 0.5f) > 0f, "the surface puddle is missing");
        assertTrue(sim.depthAt(0.5f, -5.9f, 0.5f) > 0f, "the water in the cavity is missing");
        assertNull(sim.fluidAt(0.5f, -3.5f, 0.5f),
                "the air gap between the two bodies must read as dry");
    }

    // ------------------------------------------------------------------ the soft surface

    /** A settled pond in a walled basin, so nothing is still spreading when the test starts. */
    private static FluidSim settledPond() {
        SolidField basin = (x, y, z) -> y < 0f || x < -4f || x > 4f || z < -4f || z > 4f;
        FluidSim sim = sim(basin);
        for (int x = -4; x < 4; x++) {
            for (int z = -4; z < 4; z++) {
                sim.addFluid(x + 0.5f, 0.5f, z + 0.5f, 0.9f, WATER);
            }
        }
        run(sim, 300);
        return sim;
    }

    private static float[] meshSnapshot(FluidSim sim) {
        FloatBuffer buffer = FloatBuffer.allocate(FluidSim.MAX_VERTICES * FluidSim.FLOATS_PER_VERTEX);
        int vertices = sim.buildSurface(buffer);
        float[] copy = new float[vertices * FluidSim.FLOATS_PER_VERTEX];
        buffer.get(copy);
        return copy;
    }

    /** The largest difference in vertex height between two snapshots of the same surface. */
    private static float peakHeightChange(float[] a, float[] b) {
        assertEquals(a.length, b.length, "the surface changed shape, not just height");
        float peak = 0f;
        for (int i = 1; i < a.length; i += FluidSim.FLOATS_PER_VERTEX) {
            peak = Math.max(peak, Math.abs(a[i] - b[i]));
        }
        return peak;
    }

    /**
     * The end-to-end claim: something landing in the water deforms the geometry the renderer is handed.
     *
     * <p>Paired with the frames before it on purpose. A settled pond has to be <b>geometrically still</b>
     * first, or "the splash changed the mesh" would be true of a simulation that was quietly jittering all
     * along, which is exactly the failure this system could hide.
     */
    @Test
    void aSplashDeformsTheSurfaceTheRendererIsHanded() {
        FluidSim sim = settledPond();

        float[] calm = meshSnapshot(sim);
        run(sim, 30);
        float[] stillCalm = meshSnapshot(sim);
        assertEquals(0f, peakHeightChange(calm, stillCalm), 1e-4f,
                "an undisturbed pond must be perfectly still");

        sim.splash(0.5f, 0.5f, 2.5f, 6f);
        run(sim, 8);
        float[] rippled = meshSnapshot(sim);

        assertTrue(peakHeightChange(calm, rippled) > 0.01f,
                "the splash must reach the mesh, moved " + peakHeightChange(calm, rippled));
    }

    /**
     * The shoreline follows the bank, not the cell grid.
     *
     * <p>A conical bowl filled to a known level has an exactly circular waterline, so this can ask the one
     * question a render answers by eye: is the edge of the water <b>round</b>. Measured by binning the
     * surface's own vertices by bearing and comparing the furthest one in each bin — a staircase gives a
     * radius that swings by about a cell as the outline steps around the circle, and one that stops short
     * of the true waterline into the bargain, since a cell only holds water once its <i>centre</i> is under
     * the surface.
     */
    @Test
    void aPondsEdgeIsRoundRatherThanCutToTheCellGrid() {
        // Ground: a cone from -3 at the centre rising to 0 at radius 14. Water resting at -1, so the
        // waterline is exactly where the cone reaches -1: radius 14 · (1 - 1/3) = 9.333.
        HeightField bowl = (x, z) -> -3f * Math.max(0f,
                1f - (float) Math.sqrt(x * x + z * z) / 14f);
        SolidField ground = (x, y, z) -> y < bowl.heightAt(x, z);
        FluidSim sim = new FluidSim(ground, bowl, (x, z) -> -1f, PooledFluid.of(WATER), WATER, MOLTEN);
        run(sim, 300);

        float[] mesh = meshSnapshot(sim);
        int bins = 24;
        float[] reach = new float[bins];
        for (int i = 0; i < mesh.length; i += FluidSim.FLOATS_PER_VERTEX) {
            float x = mesh[i];
            float z = mesh[i + 2];
            float radius = (float) Math.sqrt(x * x + z * z);
            int bin = (int) ((Math.atan2(z, x) + Math.PI) / (2 * Math.PI) * bins) % bins;
            reach[bin] = Math.max(reach[bin], radius);
        }

        float shortest = Float.MAX_VALUE;
        float longest = 0f;
        for (float r : reach) {
            shortest = Math.min(shortest, r);
            longest = Math.max(longest, r);
        }
        assertEquals(9.33f, (shortest + longest) * 0.5f, 0.75f,
                "the water must reach the bank, not stop half a cell short of it");

        // Area is the assertion with teeth. A radius measured per bearing is the *outer envelope* of the
        // outline, and a staircase's envelope is perfectly round — it passed this test's first version
        // while stopping two units short of the bank. What a stepped edge actually loses is surface: every
        // step is a bite out of the disc.
        float area = 0f;
        for (int i = 0; i + 3 * FluidSim.FLOATS_PER_VERTEX <= mesh.length;
             i += 3 * FluidSim.FLOATS_PER_VERTEX) {
            if (mesh[i + 4] < 0.5f) {
                continue;   // a side face, standing on edge: no horizontal area to speak of
            }
            float x0 = mesh[i];
            float z0 = mesh[i + 2];
            float x1 = mesh[i + FluidSim.FLOATS_PER_VERTEX];
            float z1 = mesh[i + FluidSim.FLOATS_PER_VERTEX + 2];
            float x2 = mesh[i + 2 * FluidSim.FLOATS_PER_VERTEX];
            float z2 = mesh[i + 2 * FluidSim.FLOATS_PER_VERTEX + 2];
            area += Math.abs((x1 - x0) * (z2 - z0) - (x2 - x0) * (z1 - z0)) * 0.5f;
        }
        float expected = (float) (Math.PI * 9.33 * 9.33);
        assertEquals(expected, area, expected * 0.12f,
                "the sheet must cover the pond it is in, got " + area + " of " + expected);
    }

    /**
     * Water under a mountain is not a shoreline, however the ground above it reads.
     *
     * <p>The shore rules lean on a height field that answers for the <i>surface</i>, so a cavern pool has a
     * "ground" thirty units over its head. The guard that stops that being taken seriously is the one thing
     * standing between a cave lake and having every edge of it dragged sideways.
     */
    @Test
    void aCavernPoolIsNotTreatedAsAShoreline() {
        SolidField cave = (x, y, z) -> {
            if (y >= 0f) {
                return false;
            }
            boolean inCavern = y > -30f && y < -26f && x > -6f && x < 6f && z > -6f && z < 6f;
            boolean inShaft = x > -1f && x < 1f && z > -1f && z < 1f && y > -30f;
            return !(inCavern || inShaft);
        };
        // The surface is a hillside far above the cavern — exactly the case the guard exists for.
        FluidSim sim = new FluidSim(cave, (x, z) -> x * 0.3f, HeightField.NONE, PooledFluid.of(WATER),
                WATER, MOLTEN);
        for (int i = 0; i < 300; i++) {
            sim.addFluid(0.5f, -1.5f, 0.5f, 0.5f, WATER);
            sim.advance(FRAME, 0f, 0f);
        }
        run(sim, 300);

        float[] mesh = meshSnapshot(sim);
        assertTrue(mesh.length > 0, "the cavern never flooded");
        for (int i = 0; i < mesh.length; i += FluidSim.FLOATS_PER_VERTEX) {
            float x = mesh[i];
            float y = mesh[i + 1];
            float z = mesh[i + 2];
            if (y < -26f) {
                assertTrue(x >= -6.01f && x <= 6.01f && z >= -6.01f && z <= 6.01f,
                        "a cavern vertex was dragged into the rock at " + x + "," + y + "," + z);
            }
        }
    }

    /**
     * Water that runs into a freshly dug hole has to be drawn, and the bug this pins was drawn at zero
     * alpha rather than not drawn at all.
     *
     * <p>Reported from play as digging beside a pond and watching the water vanish into the hole. Every
     * obvious suspect was innocent: the flow moved the mass in correctly, the mesher built faces over it
     * and the renderer submitted them. What broke was the <b>bed height cache</b>, which is sampled when a
     * column is seeded and which {@code refreshTerrain} used not to drop — so the mesher still believed the
     * ground under the hole was at the level it had before the shovel, i.e. <i>above</i> the water now
     * standing in it. {@code cornerDepth} clamps that to zero, and {@code fluid.frag} fades anything
     * shallower than {@code THIN_FILM} (0.35) out as the film it thinks it is.
     *
     * <p>So the assertion is on the <b>depth attribute</b>, not on the geometry: a version of this test
     * that checked faces were emitted over the hole passes against the bug. The height the vertices sit at
     * is checked too, since a stale bed also drags the surface toward where the ground used to be.
     */
    @Test
    void waterRunningIntoAFreshlyDugHoleIsDrawnWithRealDepth() {
        boolean[] dug = {false};
        SolidField terrain = (x, y, z) -> y < bedHeight(dug[0], x, z);
        HeightField ground = (x, z) -> bedHeight(dug[0], x, z);
        FluidSim sim = new FluidSim(terrain, ground, HeightField.NONE, PooledFluid.of(WATER), WATER, MOLTEN);

        // Seed the columns while the basin floor is still whole — this is what puts the pre-dig bed in the
        // cache, and doing it first is the whole point rather than incidental setup.
        sim.advance(0.0, 0f, 0f);
        for (int x = -4; x <= -2; x++) {
            for (int z = -1; z <= 1; z++) {
                sim.addFluid(x + 0.5f, 0.5f, z + 0.5f, 1.0f, WATER);
            }
        }
        run(sim, 60);

        // Now dig, exactly as the edit listener does: the ground moves and the sim is told which box moved.
        dug[0] = true;
        sim.refreshTerrain(HOLE_MIN_X, -4f, HOLE_MIN_Z, HOLE_MAX_X, 1f, HOLE_MAX_Z);
        run(sim, 600);

        float[] mesh = meshSnapshot(sim);
        float deepest = 0f;
        float lowestTop = Float.POSITIVE_INFINITY;
        for (int i = 0; i < mesh.length; i += FluidSim.FLOATS_PER_VERTEX) {
            float x = mesh[i];
            float y = mesh[i + 1];
            float z = mesh[i + 2];
            boolean top = mesh[i + 4] > 0.5f;               // normal.y — a side face says nothing here
            if (!top || !inHole(x, z)) {
                continue;
            }
            deepest = Math.max(deepest, mesh[i + 6]);        // the depth the shader fades on
            lowestTop = Math.min(lowestTop, y);
        }

        assertTrue(lowestTop < HOLE_FLOOR + 1f,
                "the water never reached the bottom of the hole; it rests at " + lowestTop);
        assertTrue(deepest > THIN_FILM,
                "water pooled in the dug hole is drawn as a film and fades out invisible: deepest corner "
                        + "reads " + deepest + " units, and anything under " + THIN_FILM + " is faded away");
    }

    /** The depth below which {@code fluid.frag} fades a fluid out as a film. Kept in step by hand. */
    private static final float THIN_FILM = 0.35f;

    private static final float HOLE_MIN_X = 2f;
    private static final float HOLE_MAX_X = 5f;
    private static final float HOLE_MIN_Z = -1f;
    private static final float HOLE_MAX_Z = 2f;
    private static final float HOLE_FLOOR = -3f;

    private static boolean inHole(float x, float z) {
        return x >= HOLE_MIN_X && x <= HOLE_MAX_X && z >= HOLE_MIN_Z && z <= HOLE_MAX_Z;
    }

    /**
     * A walled basin with a flat floor, and a hole in one end of it once {@code dug}.
     *
     * <p>One function for both the solid field and the height field, so the two cannot disagree about
     * where the ground is — which is the very mismatch the test exists to catch, and would be no test at
     * all if the fixture reproduced it.
     */
    private static float bedHeight(boolean dug, float x, float z) {
        if (Math.abs(x) > 6f || Math.abs(z) > 6f) {
            return 20f;                                      // basin wall, so the pond has somewhere to be
        }
        return dug && inHole(x, z) ? HOLE_FLOOR : 0f;
    }

    /** And it has to level out again on its own, or every pond ends the session dented. */
    @Test
    void aDisturbedPondSettlesBackToTheShapeItHad() {
        FluidSim sim = settledPond();
        float[] calm = meshSnapshot(sim);

        sim.splash(0.5f, 0.5f, 2.5f, 6f);
        run(sim, 900);

        assertTrue(peakHeightChange(calm, meshSnapshot(sim)) < 0.004f,
                "the pond never got back to level");
        assertFalse(sim.surface().isAwake(), "and the skin must stop being simulated");
    }

    /**
     * A source is fast-forwarded the first time it is met, and <b>never again</b>.
     *
     * <p>This is the one that shipped wrong, and it cost a session at the volcano three quarters of its
     * frame. The backlog is armed whenever a column carrying a source enters the simulation window, it is
     * <i>assigned</i> 240 ticks rather than accumulating them, and it drains only a couple a frame — so a
     * source column re-entering the window, which happens constantly while a player moves near one, pinned
     * it at maximum indefinitely. Each of those ticks is a whole simulation step, so the fluid cost went
     * from 4.75 ms a frame to 56.51.
     *
     * <p>The rule is therefore about the <b>source column</b>, not about the arrival event: walking away
     * from a vent and back must not buy another eight seconds of catch-up. Driven by moving the window off
     * the source and back, which is exactly what a player does.
     */
    @Test
    void aSourceIsSettledOnceWhenFirstMetAndNotEveryTimeItScrollsBackIntoRange() {
        FluidSim sim = new FluidSim(floorAt(0f), (x, z) -> 0f, HeightField.NONE,
                PooledFluid.of(WATER), WATER, MOLTEN);
        sim.addSource(new FluidSource() {
            @Override
            public float supplyLevelAt(float x, float z) {
                return (x > -2f && x < 2f && z > -2f && z < 2f) ? 2f : Float.NEGATIVE_INFINITY;
            }

            @Override
            public FluidType fluid() {
                return MOLTEN;
            }
        });

        // First contact: the source is met, so a catch-up is owed.
        sim.advance(FRAME, 0f, 0f);
        assertTrue(sim.settleBacklogTicks() > 0, "meeting a source for the first time owes a settle");

        // Let it drain.
        for (int i = 0; i < 400 && sim.settleBacklogTicks() > 0; i++) {
            sim.advance(FRAME, 0f, 0f);
        }
        assertEquals(0, sim.settleBacklogTicks(), "the backlog never drained");

        // Now walk well clear of it and come back, several times over. Each return re-seeds the same source
        // column, and each used to buy another full fast-forward.
        for (int trip = 0; trip < 3; trip++) {
            for (int step = 0; step < 5; step++) {
                sim.advance(FRAME, FluidSim.WINDOW * 2f, 0f);
            }
            for (int step = 0; step < 5; step++) {
                sim.advance(FRAME, 0f, 0f);
            }
            assertEquals(0, sim.settleBacklogTicks(),
                    "trip " + trip + ": returning to a source it has already settled re-armed the backlog,"
                            + " which is what pins it at maximum for as long as a player moves near one");
        }
    }

    /**
     * A source holds its own column against cooling, all the way down — not just the two cells at the top.
     *
     * <p>The rule this system already stated was that a cell supplied from the chamber stays molten, but
     * only the cells {@code feedSources} actually touches were exempt, and it touches a source's top two.
     * The body of a deep pool therefore cooled, dropped, was topped up at the surface, carried the top-up
     * down and cooled again — a loop with nothing at the end of it, in which nothing on screen changes and
     * the pool's chunks can never settle. Measured at the volcano's crater that was some fifty chunks
     * stepped every frame, and closing it took them to thirty-six.
     *
     * <p>The other half is what must NOT change: fluid that has run away from its source still cools, or
     * the sink that gives a fed flow a steady state is gone. {@code afedSourceReachesASteadyStateRather...}
     * above is what pins that, and it is the reason this exemption is about the supplied <b>column</b>
     * rather than about the fluid being lava.
     */
    @Test
    void aSourceHoldsItsWholeColumnAgainstCoolingRatherThanOnlyItsSurface() {
        // A deep, walled basin so the pool is many cells tall and cannot spread away.
        SolidField basin = (x, y, z) -> y < 0f || Math.abs(x) > 3.5f || Math.abs(z) > 3.5f;
        FluidSim sim = new FluidSim(basin, (x, z) -> 0f, HeightField.NONE, PooledFluid.of(WATER),
                WATER, MOLTEN);
        sim.addSource(new FluidSource() {
            @Override
            public float supplyLevelAt(float x, float z) {
                // Reaching the whole basin, deliberately. The exemption is per COLUMN — a source holds
                // what it reaches and no more — so a pool wider than its own source still cools at the
                // rim, which is correct and is not what this test is about.
                return Math.abs(x) < 4f && Math.abs(z) < 4f ? 8f : Float.NEGATIVE_INFINITY;
            }

            @Override
            public FluidType fluid() {
                return MOLTEN;
            }
        });

        // Fill and let it come to rest.
        run(sim, 400);
        float settled = sim.totalVolume();
        assertTrue(settled > 20f, "the basin never filled: " + settled);

        // A long run with nothing touching it. A pool its source is holding must not quietly boil away and
        // be topped back up forever.
        run(sim, 1200);
        float later = sim.totalVolume();

        assertEquals(settled, later, settled * 0.05f,
                "a held pool lost " + (settled - later) + " of " + settled + " to cooling, which is the"
                        + " drain that made it need re-feeding every frame");
    }
}
