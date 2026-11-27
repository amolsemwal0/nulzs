package org.zeronura.engine.asset;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.zeronura.engine.render.Texture;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An SVG icon sheet — a file of {@code <symbol id="...">} elements, the kind a web page pulls icons from with
 * {@code <use href="#id"/>} — drawn into images and textures for the UI.
 *
 * <p>It reads the part of SVG that flat icons are made of, not the whole standard: {@code path} (every command,
 * arcs included), {@code circle}, {@code ellipse}, {@code rect} with rounded corners, {@code g} groups, the
 * {@code rotate}, {@code translate}, {@code scale} and {@code matrix} transforms, fill and stroke with stroke width
 * and round caps, the even-odd fill rule, and linear and radial gradients ({@code fill="url(#id)"}, in either
 * {@code gradientUnits}, with stop opacity). {@code currentColor} is the colour asked for when drawing, and
 * {@code var(--name, #hex)} takes its fallback. Anything else — text, filters, CSS — is left out.
 *
 * <p>A file that is a single icon rather than a sheet of them is drawn whole, as the symbol {@link #WHOLE}. Drawn
 * {@code flat}, everything a symbol paints takes the one colour asked for, gradients and all: its silhouette.
 */
public final class SvgSymbols {

    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern TRANSFORM = Pattern.compile("(\\w+)\\s*\\(([^)]*)\\)");

    /** The whole drawing, for a file that is one icon: its root {@code <svg>}, fitted by its viewBox. */
    public static final String WHOLE = "";

    private final Map<String, Element> symbols = new HashMap<>();
    /** The gradients a fill or stroke can name, by id. */
    private final Map<String, Element> gradients = new HashMap<>();

    private SvgSymbols(Document document) {
        NodeList list = document.getElementsByTagName("symbol");
        for (int i = 0; i < list.getLength(); i++) {
            Element symbol = (Element) list.item(i);
            symbols.put(symbol.getAttribute("id"), symbol);
        }
        symbols.put(WHOLE, document.getDocumentElement());
        for (String kind : new String[]{"linearGradient", "radialGradient"}) {
            NodeList found = document.getElementsByTagName(kind);
            for (int i = 0; i < found.getLength(); i++) {
                Element gradient = (Element) found.item(i);
                gradients.put(gradient.getAttribute("id"), gradient);
            }
        }
    }

    /** Reads an icon sheet. */
    public static SvgSymbols load(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return new SvgSymbols(factory.newDocumentBuilder().parse(file.toFile()));
        } catch (Exception e) {
            throw new IllegalStateException("could not read the SVG icons in " + file + ": " + e.getMessage(), e);
        }
    }

    public boolean has(String id) {
        return symbols.containsKey(id);
    }

    /**
     * Draws a symbol into a square image, its viewBox fitted to the whole of it.
     *
     * @param color what {@code currentColor} stands for
     */
    public BufferedImage image(String id, int size, Color color) {
        return image(id, size, color, false);
    }

    /**
     * As {@link #image(String, int, Color)}; {@code flat}, everything it paints is {@code color} instead — its fills,
     * strokes and gradients alike — for its silhouette.
     */
    public BufferedImage image(String id, int size, Color color, boolean flat) {
        Element symbol = symbols.get(id);
        if (symbol == null) {
            throw new IllegalArgumentException("no symbol " + id);
        }
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float[] box = numbers(symbol.getAttribute("viewBox"));
            if (box.length == 4) {
                g.scale(size / box[2], size / box[3]);
                g.translate(-box[0], -box[1]);
            }
            draw(g, symbol, new Style(), new Ink(color, flat));
        } finally {
            g.dispose();
        }
        return image;
    }

    /** As {@link #image}, uploaded as a texture with straight (not premultiplied) alpha. */
    public Texture texture(String id, int size, Color color) {
        return texture(id, size, color, false);
    }

    /**
     * As {@link #texture(String, int, Color, boolean)}, the colour given as 0xRRGGBB: what the game calls, as it is the
     * same on Android, which has no AWT (and draws the icons from pictures baked from these; see android/README.md).
     */
    public Texture texture(String id, int size, int rgb, boolean flat) {
        return texture(id, size, new Color(rgb & 0xFFFFFF), flat);
    }

    /** As {@link #image(String, int, Color, boolean)}, uploaded as a texture with straight alpha. */
    public Texture texture(String id, int size, Color color, boolean flat) {
        BufferedImage image = image(id, size, color, flat);
        ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int argb = image.getRGB(x, y);
                pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        pixels.flip();
        return Texture.fromPixels(pixels, size, size);
    }

    // --- Drawing ----------------------------------------------------------------------------------------

    /**
     * What a child inherits from the elements around it. Fill and stroke are kept as written ({@code #hex},
     * {@code url(#id)}, {@code none}...) and turned into paint for each shape, as a gradient may span its bounds.
     */
    private record Style(String fill, String stroke, float strokeWidth, int cap, int join, boolean evenOdd) {
        Style() {
            this("currentColor", "none", 1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, false);
        }
    }

    /** What the drawing is asked for: the colour {@code currentColor} stands for, and whether all of it is that colour. */
    private record Ink(Color current, boolean flat) {
    }

    /** What is not drawn where it stands: the definitions, and the words about the drawing. */
    private static final java.util.Set<String> NOT_DRAWN = java.util.Set.of("title", "desc", "metadata", "defs",
            "linearGradient", "radialGradient", "symbol", "style");

    private void draw(Graphics2D g, Element element, Style inherited, Ink ink) {
        Style style = style(element, inherited);
        AffineTransform saved = g.getTransform();
        g.transform(transform(element.getAttribute("transform")));
        Shape shape = shape(element, style.evenOdd());
        if (shape != null) {
            Paint fill = paint(style.fill(), ink, shape);
            if (fill != null) {
                g.setPaint(fill);
                g.fill(shape);
            }
            Paint stroke = paint(style.stroke(), ink, shape);
            if (stroke != null && style.strokeWidth() > 0f) {
                g.setPaint(stroke);
                g.setStroke(new BasicStroke(style.strokeWidth(), style.cap(), style.join()));
                g.draw(shape);
            }
        } else {
            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element childElement && !NOT_DRAWN.contains(childElement.getTagName())) {
                    draw(g, childElement, style, ink);
                }
            }
        }
        g.setTransform(saved);
    }

    private static Style style(Element element, Style inherited) {
        String fill = element.hasAttribute("fill") ? element.getAttribute("fill") : inherited.fill();
        String stroke = element.hasAttribute("stroke") ? element.getAttribute("stroke") : inherited.stroke();
        float width = element.hasAttribute("stroke-width") ? Float.parseFloat(element.getAttribute("stroke-width")) : inherited.strokeWidth();
        int cap = inherited.cap();
        if (element.hasAttribute("stroke-linecap")) {
            cap = switch (element.getAttribute("stroke-linecap")) {
                case "round" -> BasicStroke.CAP_ROUND;
                case "square" -> BasicStroke.CAP_SQUARE;
                default -> BasicStroke.CAP_BUTT;
            };
        }
        int join = inherited.join();
        if (element.hasAttribute("stroke-linejoin")) {
            join = switch (element.getAttribute("stroke-linejoin")) {
                case "round" -> BasicStroke.JOIN_ROUND;
                case "bevel" -> BasicStroke.JOIN_BEVEL;
                default -> BasicStroke.JOIN_MITER;
            };
        } else if (element.hasAttribute("stroke-linecap") && cap == BasicStroke.CAP_ROUND) {
            // Round caps came with round joins before joins were read, and still do unless a join is named.
            join = BasicStroke.JOIN_ROUND;
        }
        boolean evenOdd = element.hasAttribute("fill-rule") ? "evenodd".equals(element.getAttribute("fill-rule")) : inherited.evenOdd();
        return new Style(fill, stroke, width, cap, join, evenOdd);
    }

    /** The paint for a fill or stroke as written, on {@code shape}; null for none. */
    private Paint paint(String value, Ink ink, Shape shape) {
        String v = value.trim();
        if (v.startsWith("url(")) {
            if (ink.flat()) {
                return ink.current();
            }
            int hash = v.indexOf('#');
            String id = v.substring(hash + 1, v.indexOf(')')).trim();
            Element gradient = gradients.get(id);
            return gradient == null ? null : gradient(gradient, shape.getBounds2D());
        }
        Color color = paint(v, ink.current());
        return color == null ? null : ink.flat() ? ink.current() : color;
    }

    /**
     * A gradient as Java2D paints it: its stops, here or on the gradient it names by {@code href}, along the line or out
     * from the centre it gives — over the shape's bounds unless its units are the user space's.
     */
    private Paint gradient(Element gradient, Rectangle2D bounds) {
        List<Element> stops = stops(gradient, 0);
        if (stops.isEmpty()) {
            return null;
        }
        float[] fractions = new float[stops.size()];
        Color[] colors = new Color[stops.size()];
        float last = -1f;
        for (int i = 0; i < stops.size(); i++) {
            Element stop = stops.get(i);
            float offset = Math.clamp(fraction(stop.getAttribute("offset"), 0f), 0f, 1f);
            // Java2D wants the stops strictly in order; SVG lets two share a place, for a hard edge.
            offset = Math.max(offset, last + 1e-4f);
            fractions[i] = last = offset;
            colors[i] = stopColor(stop);
        }
        if (stops.size() == 1 || fractions[fractions.length - 1] > 1f) {
            if (stops.size() == 1) {
                return colors[0];
            }
            float scale = 1f / fractions[fractions.length - 1];
            for (int i = 0; i < fractions.length; i++) {
                fractions[i] *= scale;
            }
        }
        boolean userSpace = "userSpaceOnUse".equals(gradient.getAttribute("gradientUnits"));
        AffineTransform place = new AffineTransform();
        if (!userSpace) {
            if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
                return colors[colors.length - 1];
            }
            place.translate(bounds.getX(), bounds.getY());
            place.scale(bounds.getWidth(), bounds.getHeight());
        }
        place.concatenate(transform(gradient.getAttribute("gradientTransform")));
        if ("radialGradient".equals(gradient.getTagName())) {
            float cx = fraction(gradient.getAttribute("cx"), 0.5f);
            float cy = fraction(gradient.getAttribute("cy"), 0.5f);
            float r = fraction(gradient.getAttribute("r"), 0.5f);
            float fx = fraction(gradient.getAttribute("fx"), cx);
            float fy = fraction(gradient.getAttribute("fy"), cy);
            if (r <= 0f) {
                return colors[colors.length - 1];
            }
            return new RadialGradientPaint(new Point2D.Float(cx, cy), r, new Point2D.Float(fx, fy), fractions, colors,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, place);
        }
        float x1 = fraction(gradient.getAttribute("x1"), 0f);
        float y1 = fraction(gradient.getAttribute("y1"), 0f);
        float x2 = fraction(gradient.getAttribute("x2"), 1f);
        float y2 = fraction(gradient.getAttribute("y2"), 0f);
        if (x1 == x2 && y1 == y2) {
            return colors[colors.length - 1];
        }
        return new LinearGradientPaint(new Point2D.Float(x1, y1), new Point2D.Float(x2, y2), fractions, colors,
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, place);
    }

    /** A gradient's stops: its own, or, with none, those of the gradient its {@code href} names. */
    private List<Element> stops(Element gradient, int depth) {
        List<Element> stops = new ArrayList<>();
        NodeList children = gradient.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && "stop".equals(child.getTagName())) {
                stops.add(child);
            }
        }
        if (stops.isEmpty() && depth < 8) {
            String href = gradient.hasAttribute("href") ? gradient.getAttribute("href") : gradient.getAttribute("xlink:href");
            Element named = href.startsWith("#") ? gradients.get(href.substring(1)) : null;
            if (named != null) {
                return stops(named, depth + 1);
            }
        }
        return stops;
    }

    /** A stop's colour, its opacity in its alpha: from its attributes, or from a {@code style} that sets them. */
    private static Color stopColor(Element stop) {
        String color = stop.getAttribute("stop-color");
        String opacity = stop.getAttribute("stop-opacity");
        for (String rule : stop.getAttribute("style").split(";")) {
            int colon = rule.indexOf(':');
            if (colon > 0) {
                String name = rule.substring(0, colon).trim();
                String value = rule.substring(colon + 1).trim();
                if (name.equals("stop-color")) {
                    color = value;
                } else if (name.equals("stop-opacity")) {
                    opacity = value;
                }
            }
        }
        Color base = color.isBlank() ? Color.BLACK : paint(color, Color.BLACK);
        if (base == null) {
            return new Color(0, 0, 0, 0);
        }
        float alpha = opacity.isBlank() ? 1f : Math.clamp(Float.parseFloat(opacity.trim()), 0f, 1f);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.round(alpha * 255f));
    }

    /** A number as a gradient writes it: plain, or a percentage of its span; {@code otherwise} when absent. */
    private static float fraction(String text, float otherwise) {
        if (text == null || text.isBlank()) {
            return otherwise;
        }
        String t = text.trim();
        return t.endsWith("%") ? Float.parseFloat(t.substring(0, t.length() - 1)) / 100f : Float.parseFloat(t);
    }

    /** A fill or stroke: none, currentColor, #rgb, #rrggbb, or var(--name, fallback). */
    private static Color paint(String value, Color current) {
        String v = value.trim();
        if (v.startsWith("var(")) {
            int comma = v.indexOf(',');
            v = comma < 0 ? "currentColor" : v.substring(comma + 1, v.lastIndexOf(')')).trim();
        }
        if (v.equals("none")) {
            return null;
        }
        if (v.equals("currentColor")) {
            return current;
        }
        if (v.startsWith("#")) {
            String hex = v.substring(1);
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            }
            return new Color(Integer.parseInt(hex, 16));
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "white" -> Color.WHITE;
            case "black" -> Color.BLACK;
            default -> current;
        };
    }

    /** The element's own shape, or null for a group (or anything unknown) to draw its children instead. */
    private static Shape shape(Element element, boolean evenOdd) {
        return switch (element.getTagName()) {
            case "path" -> path(element.getAttribute("d"), evenOdd);
            case "circle" -> {
                float r = f(element, "r");
                yield new Ellipse2D.Float(f(element, "cx") - r, f(element, "cy") - r, 2f * r, 2f * r);
            }
            case "ellipse" -> {
                float rx = f(element, "rx");
                float ry = f(element, "ry");
                yield new Ellipse2D.Float(f(element, "cx") - rx, f(element, "cy") - ry, 2f * rx, 2f * ry);
            }
            case "rect" -> {
                float rx = element.hasAttribute("rx") ? f(element, "rx") : element.hasAttribute("ry") ? f(element, "ry") : 0f;
                float ry = element.hasAttribute("ry") ? f(element, "ry") : rx;
                yield rx == 0f && ry == 0f
                        ? new Rectangle2D.Float(f(element, "x"), f(element, "y"), f(element, "width"), f(element, "height"))
                        : new RoundRectangle2D.Float(f(element, "x"), f(element, "y"), f(element, "width"), f(element, "height"),
                        2f * rx, 2f * ry);
            }
            default -> null;
        };
    }

    private static float f(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        return value.isEmpty() ? 0f : Float.parseFloat(value);
    }

    private static AffineTransform transform(String text) {
        AffineTransform result = new AffineTransform();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher m = TRANSFORM.matcher(text);
        while (m.find()) {
            float[] a = numbers(m.group(2));
            switch (m.group(1)) {
                case "rotate" -> {
                    if (a.length >= 3) {
                        result.rotate(Math.toRadians(a[0]), a[1], a[2]);
                    } else {
                        result.rotate(Math.toRadians(a[0]));
                    }
                }
                case "translate" -> result.translate(a[0], a.length > 1 ? a[1] : 0f);
                case "scale" -> result.scale(a[0], a.length > 1 ? a[1] : a[0]);
                case "matrix" -> result.concatenate(new AffineTransform(a[0], a[1], a[2], a[3], a[4], a[5]));
                default -> {
                }
            }
        }
        return result;
    }

    private static float[] numbers(String text) {
        if (text == null) {
            return new float[0];
        }
        Matcher m = NUMBER.matcher(text);
        float[] out = new float[16];
        int n = 0;
        while (m.find()) {
            if (n == out.length) {
                out = java.util.Arrays.copyOf(out, n * 2);
            }
            out[n++] = Float.parseFloat(m.group());
        }
        return java.util.Arrays.copyOf(out, n);
    }

    // --- Path data --------------------------------------------------------------------------------------

    /** Reads path data one token at a time: command letters, numbers, and arc flags written run together. */
    private static final class Tokens {
        private final String d;
        private int at;

        Tokens(String d) {
            this.d = d;
        }

        private void skip() {
            while (at < d.length() && (Character.isWhitespace(d.charAt(at)) || d.charAt(at) == ',')) {
                at++;
            }
        }

        boolean done() {
            skip();
            return at >= d.length();
        }

        boolean command() {
            skip();
            return at < d.length() && Character.isLetter(d.charAt(at)) && d.charAt(at) != 'e' && d.charAt(at) != 'E';
        }

        char letter() {
            skip();
            return d.charAt(at++);
        }

        float number() {
            skip();
            Matcher m = NUMBER.matcher(d);
            if (!m.find(at) || m.start() != at) {
                throw new IllegalArgumentException("bad path data near " + d.substring(at));
            }
            at = m.end();
            return Float.parseFloat(m.group());
        }

        /** An arc's flag: a single 0 or 1, which may be written with no space before the next number. */
        boolean flag() {
            skip();
            return d.charAt(at++) == '1';
        }
    }

    static Path2D path(String d, boolean evenOdd) {
        Path2D.Float path = new Path2D.Float(evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
        Tokens t = new Tokens(d);
        float x = 0f, y = 0f, startX = 0f, startY = 0f;
        // The last control point, for the smooth curves that mirror it.
        float cx = 0f, cy = 0f;
        char command = 'M';
        char previous = ' ';
        while (!t.done()) {
            if (t.command()) {
                command = t.letter();
            }
            boolean rel = Character.isLowerCase(command);
            float ox = rel ? x : 0f;
            float oy = rel ? y : 0f;
            switch (Character.toUpperCase(command)) {
                case 'M' -> {
                    x = ox + t.number();
                    y = oy + t.number();
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                    // Pairs after a move are lines.
                    command = rel ? 'l' : 'L';
                }
                case 'L' -> {
                    x = ox + t.number();
                    y = oy + t.number();
                    path.lineTo(x, y);
                }
                case 'H' -> {
                    x = ox + t.number();
                    path.lineTo(x, y);
                }
                case 'V' -> {
                    y = oy + t.number();
                    path.lineTo(x, y);
                }
                case 'C' -> {
                    float x1 = ox + t.number(), y1 = oy + t.number();
                    cx = ox + t.number();
                    cy = oy + t.number();
                    x = ox + t.number();
                    y = oy + t.number();
                    path.curveTo(x1, y1, cx, cy, x, y);
                }
                case 'S' -> {
                    boolean mirror = "CcSs".indexOf(previous) >= 0;
                    float x1 = mirror ? 2f * x - cx : x, y1 = mirror ? 2f * y - cy : y;
                    cx = ox + t.number();
                    cy = oy + t.number();
                    x = ox + t.number();
                    y = oy + t.number();
                    path.curveTo(x1, y1, cx, cy, x, y);
                }
                case 'Q' -> {
                    cx = ox + t.number();
                    cy = oy + t.number();
                    x = ox + t.number();
                    y = oy + t.number();
                    path.quadTo(cx, cy, x, y);
                }
                case 'T' -> {
                    boolean mirror = "QqTt".indexOf(previous) >= 0;
                    cx = mirror ? 2f * x - cx : x;
                    cy = mirror ? 2f * y - cy : y;
                    x = ox + t.number();
                    y = oy + t.number();
                    path.quadTo(cx, cy, x, y);
                }
                case 'A' -> {
                    float rx = t.number(), ry = t.number(), rotation = t.number();
                    boolean large = t.flag();
                    boolean sweep = t.flag();
                    float ex = ox + t.number(), ey = oy + t.number();
                    arc(path, x, y, rx, ry, rotation, large, sweep, ex, ey);
                    x = ex;
                    y = ey;
                }
                case 'Z' -> {
                    path.closePath();
                    x = startX;
                    y = startY;
                }
                default -> throw new IllegalArgumentException("unknown path command " + command);
            }
            previous = command;
        }
        return path;
    }

    /** An elliptical arc from the current point, converted from SVG's endpoint form to a centred one. */
    private static void arc(Path2D path, float x0, float y0, float rx, float ry, float degrees, boolean large, boolean sweep,
                            float x, float y) {
        if (x0 == x && y0 == y) {
            return;
        }
        if (rx == 0f || ry == 0f) {
            path.lineTo(x, y);
            return;
        }
        double phi = Math.toRadians(degrees);
        double cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x0 - x) / 2.0, dy = (y0 - y) / 2.0;
        double x1 = cos * dx + sin * dy;
        double y1 = -sin * dx + cos * dy;
        double a = Math.abs(rx), b = Math.abs(ry);
        // Radii too small to reach are scaled up until they just do.
        double lambda = (x1 * x1) / (a * a) + (y1 * y1) / (b * b);
        if (lambda > 1) {
            a *= Math.sqrt(lambda);
            b *= Math.sqrt(lambda);
        }
        double num = a * a * b * b - a * a * y1 * y1 - b * b * x1 * x1;
        double den = a * a * y1 * y1 + b * b * x1 * x1;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * a * y1 / b;
        double cyp = -coef * b * x1 / a;
        double centerX = cos * cxp - sin * cyp + (x0 + x) / 2.0;
        double centerY = sin * cxp + cos * cyp + (y0 + y) / 2.0;
        double start = Math.atan2((y1 - cyp) / b, (x1 - cxp) / a);
        double end = Math.atan2((-y1 - cyp) / b, (-x1 - cxp) / a);
        double extent = end - start;
        if (sweep && extent < 0) {
            extent += 2 * Math.PI;
        } else if (!sweep && extent > 0) {
            extent -= 2 * Math.PI;
        }
        // Arc2D measures angles anticlockwise with y up; SVG's y points down, so both are negated.
        Arc2D.Double arc = new Arc2D.Double(-a, -b, 2 * a, 2 * b, -Math.toDegrees(start), -Math.toDegrees(extent), Arc2D.OPEN);
        AffineTransform place = new AffineTransform();
        place.translate(centerX, centerY);
        place.rotate(phi);
        path.append(place.createTransformedShape(arc).getPathIterator(null), true);
        // Land exactly on the endpoint, whatever rounding the arc picked up.
        Point2D reached = path.getCurrentPoint();
        if (reached.distance(x, y) > 1e-3) {
            path.lineTo(x, y);
        }
    }
}
