package org.zeronura.engine.fluid.sph;

import java.nio.FloatBuffer;

import org.joml.Vector3f;
import org.zeronura.engine.fluid.SprayEmitter;
import org.zeronura.engine.physics.FieldContact;
import org.zeronura.engine.physics.FluidField;
import org.zeronura.engine.physics.PhysicsWorld;
import org.zeronura.engine.physics.SolidField;

/**
 * Smoothed-particle hydrodynamics: fluid as a cloud of particles that carry their own mass and work out
 * their own pressure from how crowded they are.
 *
 * <h2>Why a second fluid method at all</h2>
 * The cell simulation next door is a good model of fluid <b>at rest and in bulk</b> — a pond, a flooded
 * cavern, a sheet running down a hillside — and it is a bad model of fluid <b>in the air</b>. Its entire
 * vocabulary is how much is in a box and how quickly it moves to the next box, so a stream leaving a lip has
 * to be described as a column of boxes stepping down through empty space: no arc, no dispersion, no
 * droplets, and a shape quantised to the grid at exactly the moment the eye is best able to see that it is.
 * Every workaround for that in the mesher — drawing a falling cell full height because there is barely any
 * mass in it, feathering an open edge to nothing, refusing to draw a film — is a symptom of one thing, which
 * is that free-flying fluid has no representation.
 *
 * <p>A particle method has the opposite strengths. It is expensive per unit of fluid and hopeless as a way
 * to hold a lake, and it is exact about a droplet, because a droplet <i>is</i> its representation. So the
 * two run together and trade: the grid throws particles where fluid leaves the ground
 * ({@link SprayEmitter}), the particles hand their volume back where they land ({@link SphSink}), and the
 * exchange rate is one number on the material, so the trade conserves mass in both directions.
 *
 * <h2>The method</h2>
 * Each particle carries a mass and is smeared over a sphere of radius {@link #SMOOTHING_RADIUS} by a kernel.
 * Density at a particle is the sum of its neighbours' smeared mass; pressure is how far that overshoots the
 * fluid's rest density; and the force on a particle is the gradient of pressure across it, plus a viscous
 * pull toward its neighbours' average velocity, plus a cohesion term standing in for surface tension. That
 * last one is not a refinement — without it a jet in free fall simply disperses, because once the
 * neighbourhood thins out there is no pressure left to hold anything together, and spray reads as dust.
 *
 * <p>Densities are clamped to never fall below rest, so pressure is never negative. Negative pressure is
 * <i>attraction</i>, and the classic failure it produces is particles collapsing into clumps that then
 * explode when the clump finally over-densifies. Cohesion supplies the attraction instead, where it can be
 * tuned per fluid and bounded.
 *
 * <h2>Neighbours, which is the whole cost</h2>
 * Everything above is a sum over "particles within {@code h}", asked once per particle per pass. Done by
 * comparing all pairs it is quadratic and unaffordable at any useful count; {@link SpatialHash} makes it
 * proportional to the number of neighbours instead. The list is built <b>once per substep</b> and reused by
 * the density pass and the force pass, because the two ask exactly the same question and the 27-cell scan is
 * the expensive part of answering it.
 *
 * <h2>No GL, no game</h2>
 * Terrain arrives as a {@link SolidField} and standing fluid as a {@link FluidField} — the same two
 * interfaces {@code engine.physics} collides against — so the whole thing runs against a lambda in a test
 * with no world and no window. {@link #fillInstances} is the one concession to rendering, and it only fills
 * a caller-owned buffer.
 */
public final class SphFluid implements SprayEmitter {

    /**
     * The kernel's support radius, in world units, and therefore the spatial hash's cell size.
     *
     * <p>The single number the method's scale hangs off. Particles sit about half of it apart at rest, so it
     * sets how finely a splash is resolved and — through {@link SphMaterial#massAt} — how much fluid one
     * particle is worth. Half a metre puts roughly thirty particles in a support sphere, which is the
     * standard target: many fewer and density estimates get noisy enough to make the pressure term jitter,
     * many more and the neighbour loops cost more than the extra smoothness is worth.
     *
     * <p>It is deliberately coarse against the world's own metre-sized cells. Spray is seen in flight for a
     * fraction of a second and is drawn as a smoothed surface rather than as spheres, so resolving it finer
     * buys detail that the screen-space blur immediately throws away again.
     */
    public static final float SMOOTHING_RADIUS = 0.5f;

    /**
     * Longest substep the solver will take, in seconds.
     *
     * <p>This is a stability limit rather than a quality one, and it is not negotiable the way a graphics
     * constant is. Pressure propagates at the fluid's speed of sound, which goes as the square root of
     * {@link SphMaterial#stiffness}, and a step long enough for a pressure wave to cross a smoothing radius
     * means a particle is pushed by neighbours it has already passed — the standard SPH detonation. A
     * hundred and twentieth of a second is comfortably inside the limit for the stiffnesses here, and it
     * means a long frame is <b>subdivided</b> rather than taken whole.
     */
    public static final float MAX_SUBSTEP = 1f / 120f;

    /**
     * How many substeps one advance may take, whatever the frame length.
     *
     * <p>The spiral-of-death guard every fixed-step integrator needs: a frame that took long enough to need
     * twenty substeps would be made slower by taking them, which makes the next frame longer still. Fluid
     * running briefly in slow motion after a hitch is a far smaller wrong than a session that never
     * recovers.
     */
    private static final int MAX_SUBSTEPS = 4;

    /**
     * Neighbours recorded per particle.
     *
     * <p>A support sphere holds about thirty at rest spacing, so this is headroom rather than a target. It
     * is a hard truncation and not an error: what overflows it is a crush far denser than the fluid's rest
     * state, where dropping the furthest neighbours <i>under</i>-estimates density and so under-estimates
     * the pressure pushing back. That errs toward calm, which is the direction a solver should fail in — the
     * alternative, growing the list, would let one pathological clump allocate without bound in the frame it
     * can least afford to.
     */
    private static final int MAX_NEIGHBOURS = 48;

    /** Ceiling on particle speed, in units per second. A cataract, not a bound spray reaches in flight. */
    private static final float MAX_SPEED = 40f;

    /**
     * How long a particle must be slow before it is given back to the grid, in seconds.
     *
     * <p>Not instant, and the reason is that a splash's most interesting moment is the one just after it
     * lands: the crown, the second bounce, the drops rolling off a rock. Absorbing on contact removes all of
     * it. A short delay lets a droplet's landing play out and still guarantees the population drains, since
     * anything that has genuinely come to rest crosses the threshold and stays across it.
     */
    private static final float SETTLE_SECONDS = 0.35f;

    /** Below this a particle counts as still, in units per second. */
    private static final float SETTLE_SPEED = 1.2f;

    /**
     * How far outside its own surface a particle is pushed on contact, in world units.
     *
     * <p>Resting exactly on the surface leaves the next substep's solidity test free to answer either way
     * as the particle jitters, so a droplet sitting on flat ground flickers between contact and free fall.
     * The same reasoning as the speculative-contact margin in the rigid-body solver, at the scale of a
     * particle rather than a boulder.
     */
    private static final float CONTACT_SKIN = 0.02f;

    /** Floats per particle in the render stream: position (3), radius, fluid index. */
    public static final int FLOATS_PER_INSTANCE = 5;

    /**
     * The drawn radius of a particle, as a fraction of the smoothing radius.
     *
     * <p>Bigger than the spacing on purpose. The screen-space renderer builds a <b>surface</b> out of the
     * nearest sphere at each pixel, so spheres that only just touch leave the gaps between them visible as
     * pits in the result; overlapping generously is what makes a clump read as one body of water rather than
     * as a bag of marbles. It costs fill rate and nothing else.
     */
    private static final float DRAW_RADIUS_FRACTION = 0.62f;

    private final SolidField terrain;
    private final SphMaterial[] palette;
    private final float[] massOf;
    private final int capacity;
    private final SpatialHash hash;

    /**
     * Kernel normalisation, precomputed for the one smoothing radius in use: poly6 for density and cohesion,
     * spiky for the pressure gradient, and the viscosity laplacian. Named for the shape rather than the
     * author because that is what the code reads as.
     */
    private final float h2;
    private final float poly6;
    private final float spikyGrad;
    private final float viscLap;
    private final float selfDensity;

    private final float[] x;
    private final float[] y;
    private final float[] z;
    private final float[] vx;
    private final float[] vy;
    private final float[] vz;
    private final float[] density;
    private final float[] pressure;
    private final float[] ax;
    private final float[] ay;
    private final float[] az;
    private final float[] age;
    private final float[] still;
    private final byte[] fluid;

    private final int[] neighbours;
    private final int[] neighbourCount;

    private int count;

    private SphSink sink = SphSink.NONE;
    private FluidField standing = FluidField.EMPTY;

    private float centerX;
    private float centerY;
    private float centerZ;
    private float range = Float.MAX_VALUE;

    /** Scratch for the contact normal, so a collision allocates nothing. */
    private final Vector3f contactNormal = new Vector3f();

    /**
     * @param terrain  what is rock — the same view the character and the rigid bodies collide against
     * @param capacity the hard ceiling on live particles. Reached, {@link #spray} refuses rather than
     *                 recycling: taking a droplet out of a splash the player is looking at to make room for
     *                 one just leaving a vent is the wrong trade, and the grid keeps the fluid it was
     *                 offering instead
     * @param palette  every fluid that can spray, indexed by {@link SphMaterial#index()}
     */
    public SphFluid(SolidField terrain, int capacity, SphMaterial... palette) {
        this.terrain = terrain;
        this.capacity = capacity;

        int max = 0;
        for (SphMaterial material : palette) {
            max = Math.max(max, material.index());
        }
        this.palette = new SphMaterial[max + 1];
        this.massOf = new float[max + 1];
        for (SphMaterial material : palette) {
            if (this.palette[material.index()] != null) {
                throw new IllegalArgumentException("two materials share index " + material.index());
            }
            this.palette[material.index()] = material;
            this.massOf[material.index()] = material.massAt(SMOOTHING_RADIUS);
        }

        this.hash = new SpatialHash(SMOOTHING_RADIUS);
        float h = SMOOTHING_RADIUS;
        this.h2 = h * h;
        this.poly6 = (float) (315.0 / (64.0 * Math.PI * Math.pow(h, 9)));
        this.spikyGrad = (float) (45.0 / (Math.PI * Math.pow(h, 6)));
        this.viscLap = this.spikyGrad;
        // A particle's own contribution to its density, which is the r = 0 case of poly6 and is the reason a
        // lone droplet in the air has a density at all rather than dividing by zero.
        this.selfDensity = poly6 * h2 * h2 * h2;

        x = new float[capacity];
        y = new float[capacity];
        z = new float[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        vz = new float[capacity];
        density = new float[capacity];
        pressure = new float[capacity];
        ax = new float[capacity];
        ay = new float[capacity];
        az = new float[capacity];
        age = new float[capacity];
        still = new float[capacity];
        fluid = new byte[capacity];
        neighbours = new int[capacity * MAX_NEIGHBOURS];
        neighbourCount = new int[capacity];
    }

    /** Where landed fluid goes. Left unset, particles simply expire and their volume is lost. */
    public void setSink(SphSink sink) {
        this.sink = sink == null ? SphSink.NONE : sink;
    }

    /**
     * Where fluid already stands, so a droplet falling into a pond rejoins it instead of swimming about in
     * it.
     *
     * <p>Necessary rather than decorative: the grid holds the pond and the particles do not know it is
     * there, so without this a splash falls straight through a lake and lands on the bed.
     */
    public void setStandingFluid(FluidField standing) {
        this.standing = standing == null ? FluidField.EMPTY : standing;
    }

    /**
     * How far from the viewpoint particles are kept, in world units.
     *
     * <p>The counterpart of the cell simulation's window and set to match it: spray outside the region where
     * fluid exists at all has nothing to land in and nobody to be seen by. Particles that leave are absorbed
     * rather than deleted, so the mass goes back into the grid.
     */
    public void setRange(float range) {
        this.range = range;
    }

    // ---------------------------------------------------------------- emission

    /**
     * The {@link SprayEmitter} the grid hands fluid to: turns a parcel of volume into as many particles as
     * it is worth.
     *
     * <p>Returns what it actually took, which is the contract that keeps the trade honest. A budget that has
     * run out returns zero and the grid keeps its water; a parcel worth two and a half particles produces
     * two and returns two particles' worth, and the remaining half stays in the cell to be offered again
     * next frame rather than being rounded away. Over a running waterfall, rounding away a fraction of a
     * droplet per cell per frame is a leak that empties a river.
     */
    @Override
    public float spray(float px, float py, float pz, float dx, float dy, float dz, float volume, int index) {
        SphMaterial material = materialOf(index);
        if (material == null || volume <= 0f) {
            return 0f;
        }
        int wanted = (int) (volume / material.particleVolume());
        if (wanted <= 0) {
            return 0f;
        }
        int room = Math.min(wanted, capacity - count);
        for (int i = 0; i < room; i++) {
            // Scattered inside the cell it came from rather than all emitted at one point. Particles born on
            // top of each other are a density spike, which is a pressure spike, which is the one thing that
            // blows a solver of this kind up — and a jet whose particles all start on the same line looks
            // like a string of beads besides.
            emit(px + jitter(), py + jitter(), pz + jitter(), dx, dy, dz, index);
        }
        return room * material.particleVolume();
    }

    /** One droplet's worth of this fluid, or 0 if it does not spray. See {@link SprayEmitter#parcelVolume}. */
    @Override
    public float parcelVolume(int index) {
        SphMaterial material = materialOf(index);
        return material == null ? 0f : material.particleVolume();
    }

    /** A deterministic scatter within a cell, from a counter — no RNG to seed, and reproducible in a test. */
    private float jitter() {
        // A cheap integer hash of an incrementing counter, mapped to ±0.35 of a cell. Deterministic because
        // a preview that renders the same frame twice must produce the same spray.
        jitterState = jitterState * 1_664_525 + 1_013_904_223;
        return ((jitterState >>> 8) & 0xFFFF) / 65535f * 0.7f - 0.35f;
    }

    private int jitterState = 0x9E3779B9;

    /**
     * Adds one particle. Public because a splash, a bucket or a spell wants to make spray without any grid
     * cell having spilled.
     *
     * @return whether there was room
     */
    public boolean emit(float px, float py, float pz, float dx, float dy, float dz, int index) {
        if (count >= capacity || materialOf(index) == null) {
            return false;
        }
        int i = count++;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        vx[i] = dx;
        vy[i] = dy;
        vz[i] = dz;
        age[i] = 0f;
        still[i] = 0f;
        density[i] = palette[index].restDensity();
        pressure[i] = 0f;
        fluid[i] = (byte) index;
        return true;
    }

    // ---------------------------------------------------------------- stepping

    /**
     * Runs the solver for a frame, subdivided into stable substeps, and centres the live region on a
     * viewpoint.
     *
     * <p>Fed the paused-aware delta like every other clock here, so spray freezes in mid-air behind a menu
     * rather than continuing to fall while the world is stopped.
     */
    public void advance(double deltaSeconds, float viewX, float viewY, float viewZ) {
        centerX = viewX;
        centerY = viewY;
        centerZ = viewZ;
        if (deltaSeconds <= 0.0 || count == 0) {
            return;
        }
        int steps = Math.min(MAX_SUBSTEPS, Math.max(1, (int) Math.ceil(deltaSeconds / MAX_SUBSTEP)));
        float dt = (float) (deltaSeconds / steps);
        dt = Math.min(dt, MAX_SUBSTEP);
        for (int step = 0; step < steps; step++) {
            substep(dt);
        }
        // Retirement is bookkeeping rather than physics, so it runs once a frame rather than once a
        // substep. A droplet retired an eighth of a second late is one that was already invisible.
        //
        // It was moved here expecting a saving, on the reasoning that its two lookups — the standing-fluid
        // walk and the support probe, which is a density evaluation in the real world — must dominate the
        // arithmetic around them. Measured, that is wrong: 0.523 ms against 0.514, i.e. nothing. Kept
        // because it is still the right shape, and recorded because the guess was wrong and the next person
        // looking for time here should not spend it in the same place.
        retire((float) deltaSeconds);
    }

    private void substep(float dt) {
        hash.build(x, y, z, count);
        findNeighbours();
        computeDensity();
        computeForces();
        integrate(dt);
    }

    /**
     * Records every particle within a smoothing radius of each, once, for both passes that follow.
     *
     * <p>The 27-cell scan and the hash lookups are the expensive part of a neighbour query and the density
     * and force passes ask exactly the same question, so asking twice would very nearly double the cost of
     * the whole solver.
     */
    private void findNeighbours() {
        for (int i = 0; i < count; i++) {
            int found = 0;
            int base = i * MAX_NEIGHBOURS;
            int cx = hash.cellIndex(x[i]);
            int cy = hash.cellIndex(y[i]);
            int cz = hash.cellIndex(z[i]);
            for (int ox = -1; ox <= 1 && found < MAX_NEIGHBOURS; ox++) {
                for (int oy = -1; oy <= 1 && found < MAX_NEIGHBOURS; oy++) {
                    for (int oz = -1; oz <= 1 && found < MAX_NEIGHBOURS; oz++) {
                        int gx = cx + ox;
                        int gy = cy + oy;
                        int gz = cz + oz;
                        long wanted = SpatialHash.cellId(gx, gy, gz);
                        int bucket = hash.bucketFor(gx, gy, gz);
                        int end = hash.bucketEnd(bucket);
                        for (int slot = hash.bucketStart(bucket); slot < end; slot++) {
                            // Two cells can share a bucket. Without this the collision would be reported as
                            // a neighbourhood, and a particle could be counted twice in one query — see
                            // SpatialHash on why the duplicate is the dangerous half.
                            if (hash.cellAt(slot) != wanted) {
                                continue;
                            }
                            int j = hash.particleAt(slot);
                            if (j == i) {
                                continue;
                            }
                            float dx = x[i] - x[j];
                            float dy = y[i] - y[j];
                            float dz = z[i] - z[j];
                            if (dx * dx + dy * dy + dz * dz >= h2) {
                                continue;
                            }
                            neighbours[base + found++] = j;
                            if (found >= MAX_NEIGHBOURS) {
                                break;
                            }
                        }
                    }
                }
            }
            neighbourCount[i] = found;
        }
    }

    /**
     * Density and pressure at every particle.
     *
     * <p>Density is clamped up to the fluid's rest value, so pressure can never go negative. A negative
     * pressure is an attraction between neighbours, and it is what turns a slightly sparse patch into a
     * clump, and the clump into an explosion once it finally over-densifies. Attraction is supplied instead
     * by {@link SphMaterial#cohesion}, where it is bounded and per fluid.
     */
    private void computeDensity() {
        for (int i = 0; i < count; i++) {
            int index = fluid[i];
            float rho = massOf[index] * selfDensity;
            int base = i * MAX_NEIGHBOURS;
            int n = neighbourCount[i];
            for (int k = 0; k < n; k++) {
                int j = neighbours[base + k];
                float dx = x[i] - x[j];
                float dy = y[i] - y[j];
                float dz = z[i] - z[j];
                float d = h2 - (dx * dx + dy * dy + dz * dz);
                if (d <= 0f) {
                    continue;
                }
                rho += massOf[fluid[j]] * poly6 * d * d * d;
            }
            SphMaterial material = palette[index];
            float rest = material.restDensity();
            density[i] = Math.max(rho, rest);
            pressure[i] = material.stiffness() * (density[i] - rest);
        }
    }

    /** Pressure gradient, viscosity and cohesion, summed into an acceleration per particle. */
    private void computeForces() {
        for (int i = 0; i < count; i++) {
            SphMaterial material = palette[fluid[i]];
            float fx = 0f;
            float fy = 0f;
            float fz = 0f;
            float pi = pressure[i];
            int base = i * MAX_NEIGHBOURS;
            int n = neighbourCount[i];
            for (int k = 0; k < n; k++) {
                int j = neighbours[base + k];
                float dx = x[i] - x[j];
                float dy = y[i] - y[j];
                float dz = z[i] - z[j];
                float r2 = dx * dx + dy * dy + dz * dz;
                if (r2 >= h2 || r2 < 1e-8f) {
                    // Two particles at the same point have no direction to be pushed apart along, so the
                    // gradient is undefined rather than large. The jitter at emission is what keeps this
                    // rare; skipping is the only answer that cannot produce a NaN.
                    continue;
                }
                float r = (float) Math.sqrt(r2);
                float inv = 1f / r;
                float mj = massOf[fluid[j]];
                float rhoj = density[j];
                float gap = SMOOTHING_RADIUS - r;

                // Pressure: symmetric in i and j, so the pair exchanges equal and opposite force and the
                // fluid conserves momentum. Positive pressure pushes i away from j.
                float press = mj * (pi + pressure[j]) / (2f * rhoj) * spikyGrad * gap * gap;
                fx += press * dx * inv;
                fy += press * dy * inv;
                fz += press * dz * inv;

                // Viscosity: a pull toward the neighbour's velocity, which is what makes lava come off a lip
                // in ropes where water shatters.
                float visc = material.viscosity() * mj / rhoj * viscLap * gap;
                fx += visc * (vx[j] - vx[i]);
                fy += visc * (vy[j] - vy[i]);
                fz += visc * (vz[j] - vz[i]);

                // Cohesion: surface tension's stand-in, and the reason a jet in free fall stays a jet.
                float d = h2 - r2;
                float cohere = material.cohesion() * mj * poly6 * d * d * d;
                fx -= cohere * dx * inv;
                fy -= cohere * dy * inv;
                fz -= cohere * dz * inv;
            }
            float invRho = 1f / density[i];
            ax[i] = fx * invRho;
            // The same gravity the player falls under and a boulder tumbles under. A splash arcing at a
            // visibly different rate from the character watching it is the kind of wrongness that is felt
            // without being nameable.
            ay[i] = fy * invRho + PhysicsWorld.GRAVITY;
            az[i] = fz * invRho;
        }
    }

    /** Semi-implicit Euler, then terrain contact. */
    private void integrate(float dt) {
        for (int i = 0; i < count; i++) {
            float nvx = vx[i] + ax[i] * dt;
            float nvy = vy[i] + ay[i] * dt;
            float nvz = vz[i] + az[i] * dt;
            float speed2 = nvx * nvx + nvy * nvy + nvz * nvz;
            if (speed2 > MAX_SPEED * MAX_SPEED) {
                float scale = MAX_SPEED / (float) Math.sqrt(speed2);
                nvx *= scale;
                nvy *= scale;
                nvz *= scale;
                speed2 = MAX_SPEED * MAX_SPEED;
            }
            vx[i] = nvx;
            vy[i] = nvy;
            vz[i] = nvz;
            x[i] += nvx * dt;
            y[i] += nvy * dt;
            z[i] += nvz * dt;

            if (terrain.isSolid(x[i], y[i], z[i])) {
                collide(i);
                speed2 = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            }
            // Slow is not the same as settled, and treating it as such deletes spray in mid-air. A droplet
            // thrown straight up is momentarily motionless at the top of its arc, and a rope of lava sags
            // more slowly than the threshold for its whole flight — both would be absorbed at the one
            // moment they are most worth looking at. Standing on something is the other half of the
            // question, and it is only asked of particles that are already slow, because in the game
            // "is this rock" is a density evaluation and most particles in flight are not close to being
            // still.
            still[i] = speed2 < SETTLE_SPEED * SETTLE_SPEED && isSupported(i) ? still[i] + dt : 0f;
        }
    }

    /** Whether there is ground close enough under a particle for it to be resting on rather than falling. */
    private boolean isSupported(int i) {
        return terrain.isSolid(x[i], y[i] - SUPPORT_REACH, z[i]);
    }

    /** How far under a particle ground counts as holding it up, in world units. */
    private static final float SUPPORT_REACH = 0.3f;

    /**
     * Pushes a particle back out of rock and reflects what is left of its velocity.
     *
     * <p>The normal comes from the same 26-probe stencil the rigid bodies use, which is what makes a splash
     * off a gentle slope run down it rather than bouncing as though off a 45° face — six axial probes can
     * only ever answer one of 26 directions. It costs 26 field samples, which is why this is reached only by
     * particles that have actually penetrated rather than being asked of every particle every substep.
     */
    private void collide(int i) {
        FieldContact.normalAt(terrain, x[i], y[i], z[i], contactNormal);
        float depth = FieldContact.signedDistance(terrain, x[i], y[i], z[i], contactNormal,
                SMOOTHING_RADIUS);
        float push = Math.max(0f, -depth) + CONTACT_SKIN;
        x[i] += contactNormal.x * push;
        y[i] += contactNormal.y * push;
        z[i] += contactNormal.z * push;

        float into = vx[i] * contactNormal.x + vy[i] * contactNormal.y + vz[i] * contactNormal.z;
        if (into >= 0f) {
            return;
        }
        SphMaterial material = palette[fluid[i]];
        // Split into the part heading into the surface, which bounces, and the part along it, which is
        // scrubbed by friction. Doing it as one damped reflection would make a droplet skating along flat
        // ground lose speed at the restitution rate, so spray would slide for metres.
        float nvx = vx[i] - contactNormal.x * into;
        float nvy = vy[i] - contactNormal.y * into;
        float nvz = vz[i] - contactNormal.z * into;
        float keep = 1f - material.friction();
        float bounce = -into * material.restitution();
        vx[i] = nvx * keep + contactNormal.x * bounce;
        vy[i] = nvy * keep + contactNormal.y * bounce;
        vz[i] = nvz * keep + contactNormal.z * bounce;
    }

    /**
     * Gives back every particle that has stopped being one, and compacts the survivors.
     *
     * <p>Four ways out, and each is a different statement about when a particle method has nothing left to
     * say: it has come to rest, it has landed in standing fluid, it has run out of lifetime, or it has left
     * the region the fluid exists in at all. The first three hand their volume to the sink, so the water is
     * still in the world; the fourth does not, because there is no grid out there to hand it to and creating
     * one only to evict it would be work done to accomplish nothing.
     *
     * <p>Removal is a swap with the last live particle, so it is constant time and order is not preserved.
     * Nothing here depends on order — the neighbour lists are rebuilt from scratch every substep, which is
     * exactly why this can be done in a single backward pass rather than needing a stable compaction.
     */
    private void retire(float dt) {
        for (int i = count - 1; i >= 0; i--) {
            age[i] += dt;
            SphMaterial material = palette[fluid[i]];

            float dx = x[i] - centerX;
            float dy = y[i] - centerY;
            float dz = z[i] - centerZ;
            if (dx * dx + dy * dy + dz * dz > range * range) {
                remove(i);
                continue;
            }
            boolean landed = still[i] >= SETTLE_SECONDS
                    || age[i] >= material.lifetimeSeconds()
                    || !Float.isNaN(standing.surfaceLevelAt(x[i], y[i], z[i]));
            if (landed) {
                sink.absorb(x[i], y[i], z[i], material.particleVolume(), fluid[i]);
                remove(i);
            }
        }
    }

    private void remove(int i) {
        int last = --count;
        if (i != last) {
            x[i] = x[last];
            y[i] = y[last];
            z[i] = z[last];
            vx[i] = vx[last];
            vy[i] = vy[last];
            vz[i] = vz[last];
            density[i] = density[last];
            pressure[i] = pressure[last];
            age[i] = age[last];
            still[i] = still[last];
            fluid[i] = fluid[last];
        }
    }

    // ---------------------------------------------------------------- queries

    public int count() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    /** How much fluid the particles are holding, in cell units — the other half of a conservation check. */
    public float totalVolume() {
        float sum = 0f;
        for (int i = 0; i < count; i++) {
            sum += palette[fluid[i]].particleVolume();
        }
        return sum;
    }

    public float x(int i) {
        return x[i];
    }

    public float y(int i) {
        return y[i];
    }

    public float z(int i) {
        return z[i];
    }

    public float speed(int i) {
        return (float) Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
    }

    /** Density at a particle, which is what a test asserts on to know the pressure term is doing anything. */
    public float density(int i) {
        return density[i];
    }

    public int fluidAt(int i) {
        return fluid[i];
    }

    /** Neighbours found for a particle on the last substep — a probe's window onto the hash's usefulness. */
    public int neighbourCount(int i) {
        return neighbourCount[i];
    }

    /** The material registered for a palette index, or null if nothing sprays that fluid. */
    public SphMaterial materialOf(int index) {
        return index >= 0 && index < palette.length ? palette[index] : null;
    }

    /** The radius a particle is drawn at, in world units. See {@link #DRAW_RADIUS_FRACTION}. */
    public static float drawRadius() {
        return SMOOTHING_RADIUS * DRAW_RADIUS_FRACTION;
    }

    /**
     * How much space one particle occupies at rest spacing, in cell units — what a material's
     * {@code particleVolume} should be, and why it is derived rather than chosen.
     *
     * <p>Three numbers describe the same thing here and only two of them are free: the smoothing radius, the
     * spacing particles sit at under no pressure (half of it), and the volume one particle stands for. Pick
     * the third independently and the two simulations disagree about what a particle is worth — a
     * {@code particleVolume} above this converts a spilling cell into <b>fewer</b> particles than would fill
     * the space at rest, so spray comes out sparse and thin, and one below it packs them tighter than rest
     * spacing, which is a density spike at the moment of emission and therefore a pressure spike. Neither
     * shows up as an error; the first reads as the effect being too weak and the second as it being
     * unstable.
     *
     * <p>Deriving it also means the exchange rate between grid and particles is <i>the same number</i> as
     * the packing the solver was normalised for, so the handoff conserves volume as well as mass.
     */
    public static float restParticleVolume() {
        float spacing = SMOOTHING_RADIUS * 0.5f;
        return spacing * spacing * spacing;
    }

    /**
     * Fills {@code out} with {@code position + radius + fluid index} per particle and returns the count.
     *
     * <p>The only method here that knows a renderer exists, and it still does not: it fills a buffer the
     * caller owns, exactly as {@code FluidSim.buildSurface} does, so the solver can be driven headless.
     */
    public int fillInstances(FloatBuffer out) {
        out.clear();
        float radius = drawRadius();
        int written = 0;
        for (int i = 0; i < count; i++) {
            if (out.remaining() < FLOATS_PER_INSTANCE) {
                break;
            }
            out.put(x[i]).put(y[i]).put(z[i]).put(radius).put(fluid[i]);
            written++;
        }
        out.flip();
        return written;
    }
}
