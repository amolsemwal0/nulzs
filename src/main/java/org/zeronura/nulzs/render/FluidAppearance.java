package org.zeronura.nulzs.render;

import org.joml.Vector3f;

/**
 * How one fluid looks: the parameters {@code fluid.frag} is driven by.
 *
 * <p>The counterpart to {@code FluidType}, which is the same fluid's physics. Keeping them apart is the
 * layering rule this codebase already applies to {@code SceneLighting} and {@code WorldLighting} — the
 * engine takes the numbers, the game decides what they are, because which shade of blue a pond is is art
 * direction and does not belong in a renderer.
 *
 * <p>The whole palette is one parametric model rather than a shader per fluid, and {@link #emissive} is
 * what spans it. At 0 the surface is <b>lit</b>: diffuse from the scene's sun, a fresnel sheen that stands
 * in for a sky reflection, a tight specular glint — a pond. At 1 it is <b>self-lit</b>: the same rippling
 * surface, coloured by a gradient running from {@link #shallowColor} through {@link #emberColor} to
 * {@link #hotColor}, with the upper two above 1 so the bloom pass makes it glow instead of reading as a
 * flat orange slab — lava. Anything between crossfades, which is what a fluid like magma-warm mud or a
 * glowing acid would want.
 *
 * @param shallowColor tint where the fluid is thin — a pond's clear teal rim, or the coolest stop of a
 *                     melt's gradient, which is the one stop under 1 and so the one that is <i>lit</i>
 * @param deepColor    tint where it is deep, crossfaded to over a few units of depth. Unread by the molten
 *                     model, which replaces the depth tint with its own heat ramp
 * @param emberColor   emissive only: the gradient's middle stop, the orange a flow mostly is. Above 1.
 * @param hotColor     emissive only: its top stop, the yellow of the hottest melt. Well above 1.
 * @param emissive     0 for a lit surface, 1 for a self-lit one; between crossfades the two models
 * @param absorption   how fast the fluid absorbs light passing through it, per world unit of path, <b>per
 *                     channel</b>. It is the whole of the water body: {@code fluid.frag} measures how far
 *                     the light reaching the eye travelled inside the fluid and runs Beer's law over it,
 *                     so these three numbers decide the transparency, the shallow-to-deep hue shift and
 *                     what the bed looks like through the shallows, and none of those can drift apart from
 *                     the others. Three rather than one because that is what water <i>is</i> — red is
 *                     absorbed several times faster than blue, which is why a sandy shallow is turquoise
 *                     rather than sand under a blue wash, and no single rate can produce it. See
 *                     {@code Fluids}, which sets them against the depth of water the world actually holds;
 *                     a melt is opaque within centimetres on every channel.
 * @param sprayOpacity how opaque this fluid gets in the air, once a splash is thick enough for its own
 *                     colour to saturate — the ceiling {@code sph_composite.frag} clamps to. It was called
 *                     {@code opacity} and read as though it were the pooled surface's alpha too;
 *                     {@code fluid.frag} never once looked at it, and now that the pooled body's
 *                     transparency comes from {@link #absorption} the name would be actively misleading.
 *                     Airborne fluid is genuinely clearer than a pool of the same stuff — a splash is a
 *                     few particles deep — so this is its own number rather than derived.
 * @param specular     strength of the sun glint; a viscous fluid wants much less than water
 * @param rippleScale  how strongly the animated surface waves perturb the normal; 0 is glassy
 * @param foamColor    what fast-moving fluid turns toward — white for water, the hottest tone for a melt
 * @param foamSpeed    the speed, in world units per second, at which foam reaches full strength. It is a
 *                     property of the <i>fluid</i>, not of the renderer: water breaks white the moment it
 *                     is disturbed, while molten rock has to be moving hard before its crust tears open,
 *                     so one shared threshold would either foam lava at a crawl or never foam a brook
 * @param flowLineColor the colour of the streamlines drawn along the current — see {@code fluid.frag}'s
 *                      {@code flowLines}. White for water, a hot yellow for a melt. Push it past 1 for a
 *                      fluid that should glow along its flow: everything over 1 is split off and added
 *                      unlit, so it survives shadow and the bloom finds it
 * <p>NOTE: there used to be a pair of texture slots here as well, holding water's shallow and deep
 * albedos, and they went for a blunter reason than the crust below did: <b>both files were measured at a
 * standard deviation of exactly zero on every channel</b>, so they were two 2048-square images carrying
 * one colour apiece — and the two colours they carried, a flat cyan and a flat blue, were considerably
 * worse than the {@link #shallowColor}/{@link #deepColor} pair they were overriding. While they stood,
 * nothing done to this record's colours could reach the screen. If water is given real art, it should
 * modulate the absorption rather than replace the body colour.
 *
 * <p>NOTE: there used to be a {@code crust} slot here carrying the molten model's own art. It is gone, and
 * the reason is worth keeping: the two stops that make a melt glow sit <i>above 1</i>, and an 8-bit image
 * cannot hold a value of 3.4 however bright it looks — so the art could only say where to glow while these
 * three colours said how much, which is the smaller half of the job for two texture units and a set of
 * measurements that failed silently when the art was re-exported.
 */
public record FluidAppearance(Vector3f shallowColor, Vector3f deepColor, Vector3f emberColor,
                              Vector3f hotColor, float emissive, Vector3f absorption, float sprayOpacity,
                              float specular,
                              float rippleScale, Vector3f foamColor, float foamSpeed,
                              Vector3f flowLineColor) {

    /** Most fluids are not self-lit; this is the short form for one that is only a colour and a sheen. */
    public static FluidAppearance lit(Vector3f shallow, Vector3f deep, Vector3f absorption, float sprayOpacity,
                                      float specular, float rippleScale, Vector3f foam, float foamSpeed,
                                      Vector3f flowLines) {
        return new FluidAppearance(shallow, deep, new Vector3f(), new Vector3f(), 0f, absorption,
                sprayOpacity, specular, rippleScale, foam, foamSpeed, flowLines);
    }
}
