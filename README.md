# Nulzs Game Engine

A Java game engine, with its own editor, for building 2D and 3D games — platformers, top-down games, card and puzzle
games, first- and third-person games, and open worlds — from a single blank starting point.

Requires JDK 26 and Maven. Rendering is OpenGL 3.3 through LWJGL.

## The folder

```
pom.xml, src/          the engine: built as a library, org.zeronura:nulzs-game-engine
assets/                assets every project shares: the UI fonts, the Tree Tool's default pictures
tools/                 the engine's tools (node catalogs from Blender, material baking, tree pictures, face shadows)
engine                 the command-line tool
exports/android/       builds of the games for players, one folder per system
exports/linux/
exports/windows/
projects/<Name>/       the projects, each a folder of its own (not part of the repository)
saves/                 settings and save games (not part of the repository)
```

The repository is the engine alone. Git leaves out `projects/`, everything built into `exports/`, saves, bug reports,
and the Steamworks SDK and Steam's libraries wherever they are (Valve's licence does not allow publishing them).

## Quick start

```sh
./engine                 # build and launch: the default project, else the editor (last project, or a new blank one)
./engine editor MyGame   # open a project in the editor
./engine new MyGame      # create a blank project
./engine run MyGame      # build it and play it
```

| Command | What it does |
|---|---|
| `./engine run [Project]` | Build and launch a project (default project, else the editor on the last project edited, or on a new blank `MyGame`) |
| `./engine editor [Project]` | Open a project in the editor (or the one last edited) |
| `./engine hub` | Build every project and open the Project Hub |
| `./engine new <Project> [description]` | Create a blank project in `projects/<Project>/` |
| `./engine list` | List projects |
| `./engine default <Project>` / `--clear` | Choose what launches when no project is named |
| `./engine screenshot <Project> <file.png> [frame]` | Launch, save one frame, exit |
| `./engine export <Project> <linux\|windows\|android>` | Build the game for players into `exports/<system>/`, with the project's `tools/export.sh` |
| `./engine rig <folder\|file.psd>...` | Make 2.5D rigs (`.rig.json`) from layered `.psd` files |
| `./engine test [Project\|--all]` | Run the engine's unit tests, a project's, or all of them |
| `./engine install` | Install the engine in the local Maven repository now |

Every project is built on the engine as a library, so `./engine` first installs the engine (`mvn install`) whenever
its code changed since it last was. `mvn compile exec:exec` opens the hub or the editor too.

The Project Hub lists every project, plays them, opens them in the editor, creates blank projects, and sets which
one launches by default. Games started from the hub return to it with Esc.

## Using the engine as a library

`mvn install` (or `./engine install`) puts `org.zeronura:nulzs-game-engine:1.0-SNAPSHOT` in the local Maven repository,
with its sources for an IDE to show. A game's `pom.xml` names it:

```xml
<dependency>
    <groupId>org.zeronura</groupId>
    <artifactId>nulzs-game-engine</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

It brings LWJGL (with the natives for the system it is built on), JOML and JNoise with it. A game packaged for another
system swaps in that system's LWJGL natives (classifier `natives-windows`, `natives-linux`, `natives-macos`,
`natives-macos-arm64`) itself, as Crelmons' export does. The engine's UI faces are inside its jar, so a game run from
any folder draws in them; `.ttf` files in the folder's `assets/Fonts/` replace them.

The launcher is `org.zeronura.engine.GameEngine`, started in the folder that holds `projects/`:

| Arguments | What starts |
|---|---|
| *(none)* | The default project, else the editor on the last project edited, else the editor on a new blank `MyGame` |
| `MyGame` | A project |
| `MyGame --scene scenes/Level.scene` | A project, in one of its scene files instead of its game's first scene |
| `--editor [MyGame]` | The editor, on a project or the one last edited |
| `--hub` | The Project Hub |

`-Dengine.screenshot=<file.png> -Dengine.screenshotFrame=<n> -Dengine.exitAfterFrames=<n+1>` saves one frame and exits.

## Working on the engine

```sh
./engine test              # the engine's unit tests (STEAMWORKS_SDK=<the Steamworks SDK's sdk/ folder> also checks
                           # Steam's library)
./engine test --all        # and every project's
```

The projects run on the engine as installed: `./engine` reinstalls it before building them whenever the engine
changed.

## The editor

`./engine editor MyGame` (or **Edit** on the hub) opens a project in the engine's editor:

```
 File  Edit  Create  Assets  View  Tools  Build  Options  Help                           [MyGame]
 [move rotate scale] [world/local] [snap] [path shader tree] [Save]   [> || >|]  [Compile] [Run Game]
+-----------+------------------------------------------------+----------------+
| Hierarchy | Scene | Connections | Door.java x | Shader: Rock | Inspector      |
|           |   3D / 2D viewport, gizmos, grid               |   name, tags   |
|           |   or the Connections graph                     |   transform    |
|           |   or a script, or the Shader Tool's nodes      |   components   |
+-----------+------------------------------------------------+   Add Comp...  |
| Project | Console                                          |                |
+--------------------------------------------------------------+----------------+
```

| Part | What it does |
|---|---|
| Menu bar | File (scenes, projects), Edit (undo, copy/paste, duplicate, tools), Create (empty, 3D shapes, sprites, cameras, models), Assets, View (panels, 2D/3D, grid, layout), Tools (Path Tool, Shader Tool, Geometry Nodes Tool, Tree Tool), Build (compile, play, run the game, Maven, package for players), Options (interface size, snap steps, compile on save), Help (shortcuts, F1) |
| Hierarchy | The scene's entities as a tree: select, rename (F2), drag to parent or reorder, search, right-click menu |
| Scene view | The scene as the game draws it, in 3D through an orbit/fly camera (right drag + WASD, middle drag, Alt+drag, wheel, F to frame) or flat in 2D; click or box-select; W/E/R move, rotate and scale gizmos with world or local axes and snapping; drop models and pictures from the Project panel |
| Inspector | The selection's name, tags, transform and every component's public fields — numbers dragged or typed, switches, enums, colours with a picker, vectors, asset paths with a file picker, links to other entities, and signals wired to other entities' methods; Add Component lists the project's scripts and the engine's components |
| Connections | The scene as a node graph: each entity with its scripts, wires from fields to the entities they point at, signal wires labelled with the method they call, parent links; drag from a socket onto a node to link it |
| Code editor | Scripts and other text files in tabs: Java colouring, line numbers, auto-indent, Ctrl+/ comments, Ctrl+F find, compile errors underlined |
| Project | The project's assets and scripts: folder tree, tiles with picture thumbnails or a list, search, new folders/scripts/scenes, rename, delete to the trash, drag files into the scene or onto fields |
| Console | Everything the editor, the compiler and a running game print, filtered by level; click a `File.java:12` line to open it there |

**Scenes** are files in `projects/MyGame/assets/scenes/*.scene` (JSON). **Scripts** are the project's Java components;
Assets > New Script writes one into the project's `src/main/java/<package>/scripts/`, and saving a script compiles it inside the
editor (Ctrl+B any time) and loads the new version into the open scene straight away. **Play** (Ctrl+P) runs the scene in
the viewport and puts it back as it was on Stop; **Run Game** (F5) compiles and starts the real game in its own window,
and Ctrl+F5 starts it in the open scene. A new project made from the editor's File menu starts its first scene file:

```java
public void start(Engine engine) {
    engine.scenes().set(new FileScene("scenes/Main.scene"));
}
```

### Tools

The **Tools** menu (and the toolbar buttons after the snap switch) holds the editor's tools. Esc or Tools > Stop
Drawing puts a drawing tool down. The Terrain Tool is for 3D scenes and the Tile Map Tool for 2D ones; the menu offers
each in its kind of scene, and the toolbar shows the one that fits.

| Tool | What it does |
|---|---|
| **Path Tool** | Click the ground point by point to draw a spline: a road, a path, a footpath, a wall or a railing (a `PathSpline` component). Shift+click makes a sharp corner; click the last point or press Enter to finish; Backspace takes a point back. With a path selected its points show: drag them, Shift+click to turn a corner sharp or round, Alt+click to take one out, Ctrl+click on the path to add one. The Inspector sets the kind, width, height, curved or straight, closed loop, texture or colour, footpaths on either side, railings or walls along the edges, and whether it blocks the player. |
| **Shader Tool** | Opens a "Shader:" tab with a Blender-style node editor for materials. Every node is made as Blender 5 makes it (71 of them, in Output, Shader, Input, Texture, Color, Converter and Vector), with Blender's sockets, settings and defaults; a node's dropdowns show and hide sockets as Blender does. Right-click or Add Node to add, drag between sockets to join (drop a wire on empty canvas to add a node already joined), middle- or right-drag to pan, wheel to zoom, Home to frame all. The side panel previews the material, lit, on a sphere, cube, cylinder or plane and lists anything the compiler could not do. Save Shader... keeps it as `assets/materials/<Name>.nodes.json`; **Apply to Selected** puts it on the selected models and shapes, and a shader picked in the Project panel opens here. |
| **Geometry Nodes Tool** | Opens a "Geometry:" tab with a Blender-style geometry node editor, on the same canvas as the Shader Tool. Its Add menu is Blender 5's own (Input, Attribute, Geometry, Curve, Instances, Mesh, Point, Simulation, Color, Texture, Utilities, with their sub-menus), cut down to the 204 nodes the engine's geometry evaluator runs; Repeat and Simulation zones add both their nodes, paired, and Join Geometry's socket takes many links. The side panel shows the result in 3D (the tree runs on its own thread a moment after each change), how many vertices, faces, points, curves and instances it made and how long it took, and the tree's **Inputs**: add, name, type, order and give defaults to the settings its Group Input hands out. Set Material picks the project's saved Shader Tool materials. Save keeps it as `assets/geometry/<Name>.nodes.json`; **Add to Selected** puts it on the selected entities as a `GeometryNodes3D`, whose inspector sets the tree's inputs for that entity. The node list is made from Blender by `tools/blender_nodes/make_geometry_catalog.sh`. |
| **Terrain Tool** (3D) | Sculpts and paints a `Terrain3D` (or the map a `WorldMap3D` shows, in its game's grounds: see *Maps in the editor* below): the selected one, the scene's, or a new 100 m one (which switches off a new scene's flat Ground plane and its flat ground collision, so the ground can be dug). A panel over the view holds the brushes — Raise, Lower, Smooth, Flatten, Roughen, Paint — with their size and strength; drag over the ground to brush it, Shift turns raising into lowering, `[` and `]` size the brush, Ctrl+click picks the height Flatten levels to. Paint has up to 8 ground layers, each a colour and, if you give it one, a picture tiled over the ground. Each stroke is one undo step. The Inspector sets the terrain's size, detail (32 to 256 squares across), shadows, and whether characters stand on it. |
| **Tile Map Tool** (2D) | Paints a `TileMap2D` in layers from a tileset picture: the selected one, the scene's, or a new 40 x 24 one. Its panel picks the tileset and its tile size, shows the tileset to pick a tile from (drag over several to paint them as a stamp), and lists the layers: choose the one painted on, show or hide, lock, make it solid (its tiles become walls and floors for 2D physics), set its opacity, add, delete, move up or down, rename (click the chosen one's name). Paint, Erase, Rectangle, Fill and Pick; Alt+click picks a tile up with any of them. Each stroke is one undo step. |
| **Sprite Editor** | Opens a "Sprite:" tab to paint pictures pixel by pixel: Pencil, Eraser, Fill, Line, Rectangle (Shift fills it) and Picker, a brush size, a palette and the colours last used, a pixel grid, and a grid of frames or tiles of any size for a sheet. Middle-drag pans, the wheel zooms, a right click picks a colour up, Home fits it in view. Save writes a PNG (new ones go in `assets/sprites`), and sprites and tile maps showing it update straight away. "Edit in Sprite Editor" on a PNG in the Project panel opens it here. |
| **Tree Tool** | Click the ground to plant a tree, then click upwards to draw its trunk as a spline (a `SplineTree` component); click the top point or Enter finishes. The Inspector grows the rest with sliders: branches, where they start, their length, angle, bend and thickness, splits, sub-branches, wildness, leaf balls and leaves, tint, bark and leaf pictures, and a seed. Drag the trunk's points to bend it, drag the root to move the tree, Alt+click takes a point out, Ctrl+click adds one on top. |

### Materials

Every model (`Model3D`) and shape (`Shape3D`) has a **Material** setting in the Inspector:

| Choice | What it draws |
|---|---|
| **Baked** (a model) / **Plain colour** (a shape) | Its own look: the materials and textures baked into the model file, or the shape's colour |
| **Own shader** | A shader made just for it in the Shader Tool, kept with it in the scene file |
| A saved shader, by name | One of the project's shaders in `assets/materials`, shared by everything that wears it |
| **New Shader...** | Gives it a shader of its own and opens it in the Shader Tool, started from how it looks now: a textured model's picture (written out to `assets/materials/pictures/`) in an Image Texture node feeding the Principled BSDF, a coloured part's colour, a shape's colour |
| **Save as Shader...** | Keeps its own shader under a name in `assets/materials`, so any model or shape can pick it; it then wears the saved one |

In the Shader Tool, **Save** puts an own shader on what owns it, and **Save Shader...** keeps whatever is open for reuse.
The button beside Material edits what it wears, and a `.nodes.json` file dropped on the field (or on a model in the
Scene view) puts that shader on. A model of several materials lists each one as a **part** under Material: a part
wears what the whole does (**As Material**), or is set apart: kept Baked, given its own shader, or a saved one, so a
bench can keep its baked iron legs and wear a shader on its wooden slats. The preview's **Model** button shows the
shader on the model itself, or on just the part.

A model's parts in a shader are drawn as they stand in the file: a rigged model's shaded parts don't play its clip.
And the node shader is lit in Blender's way, not the cel shading baked models get, so a baked model and its own
shader made from it look alike in colour but not in shading.

The tools' results are ordinary components a game can make from code: `PathSpline` (points in the entity's space,
laid flat on its level), `SplineTree` (trunk points from the root up) and `Terrain3D` (centred on its entity; its
`map()` is a `WorldMap` the `Brushes` work on, and it is a `Ground3D`, which characters stand on) in `graphics3d`;
`TileMap2D` (bottom-left corner at its entity, tile (0, 0) bottom-left, a tile is its place in the tileset counted left
to right, top to bottom) in `graphics2d`. Heights, paint and tiles are kept with them in the scene file. And what a model or shape
wears is its `material` (`""`, `"own"` or a `.nodes.json` asset path, see `WearsMaterial`), saved shaders read through
`MaterialLibrary` (which picks up a re-saved file while the game runs). The node list comes from Blender itself; after
a Blender update, rebuild it with

```
blender --background --factory-startup --python tools/blender_nodes/make_shader_catalog.py -- \
    --out src/main/resources/nodes/shader_nodes.json
```

and the default bark and leaf pictures in `assets/tree/` with `python3 tools/tree_textures.py --out assets/tree`.

### Maps in the editor

A `WorldMap3D` component shows a map file (`.map`, as the map editor saves it) in the scene, drawn the way its game
draws it: the ground, water, roads, trees, buildings and props. Its **Map** field names the file. The map lies where
the game puts it, its corner at the world's origin, so the scene's places are the game's; one map per scene. A game
gives its maps its own grounds and props by extending `WorldMap3D` and returning its `Look` from `createLook`; a
project whose first scene shows its map opens on that map in the editor.

The Terrain Tool paints the map in its game's grounds, and sculpts it unless its heights are laid from its ground
areas each time it loads (the New Map's are; there only paint is kept). Each stroke is one undo step. **Save** writes the
map file too — not what the game works out when it loads it, like the New Map's beaches — and the first save in a
session keeps the file it replaces as `<name>-before-editor.map`. The map stays in its file: the scene keeps only which
file, and which version of it, so an undo or Play/Stop doesn't load it again. The map's objects, roads, trees, zones and
markers are drawn but not yet edited here; the game's own map editor still does those.

### What the Inspector shows

A component's **public fields** are its settings: `boolean`, numbers, `String`, enums, `Color`, `Vector2f/3f/4f`, an
`Entity` or any component type (a link to another entity), and `Signal` (an event). They are saved with the scene.

```java
public class PressurePlate extends Component {
    @Tooltip("What presses the plate")
    public Entity watched;                       // set in the Inspector, or draw the wire in Connections
    @Range(min = 0, max = 5)
    public float radius = 1.2f;                  // a slider
    @Asset({"ogg"})
    public String clickSound = "";               // a file picker; drop a file on it
    public final Signal pressed = new Signal();  // wire it to Door.open() in the Inspector

    protected void update(float dt) {
        if (watched != null && watched.transform().position.distance(transform().position) < radius) {
            pressed.emit();
        }
    }
}
```

`@HideInEditor` (or `transient`) keeps a public field out. A component the editor can add needs a public constructor
taking nothing. The editor's own building blocks are ordinary components a game can use from code too: `Shape3D`
(cube, sphere, cylinder, capsule, cone, plane), `Model3D` (a model file by asset path), `Sprite2D` (an image by asset
path), `CameraView3D` and `CameraView2D` (the camera a scene is seen through). `SceneFile` reads and writes scene files,
`FileScene` plays one, and `SceneHost` runs a scene off the scene stack, as the editor does.

In any game: **F3** stats overlay, **F11** fullscreen, **F12** screenshot to `screenshots/`.

## Projects

Each project is one folder holding everything of its own: its settings, its build, its code and tests, its assets,
and whatever tools, notes and platform builds (an `android/` Gradle project, say) it has.

```
projects/MyGame/
  project.properties        name, gameClass, window size (1920x1080 by default)
  pom.xml                   its build: the engine as a library; its tests run from the engine's folder
  src/main/java/org/zeronura/games/mygame/
    MyGameGame.java         implements Game — puts the first scene on the stack
    MainScene.java          your first scene, blank
  src/test/java/            its tests
  assets/                   images, sounds, models, levels (searched before the engine's shared assets/)
  tools/export.sh           optional: builds it for players, `export.sh linux|windows|android`, into exports/
```

The game runs from the engine's folder, so paths in a game's code start there (`projects/MyGame/...`); assets are
found by the paths under `assets/` (`assets().texture("player.png")`). Settings and saves go in `saves/`.

A blank project is not a 2D or 3D template. Its scene starts empty and every engine feature switches on the
first time you use it, so the same project can become any kind of game.

## Writing a game

```java
public final class MyGameGame implements Game {
    public void start(Engine engine) {
        engine.scenes().set(new MainScene());
    }
}

public class MainScene extends Scene {
    protected void onStart()             { /* build the level */ }
    protected void onUpdate(float dt)    { /* every frame: input, game logic */ }
    protected void onFixedUpdate(float dt) { /* fixed 60 Hz: simulation */ }
    protected void onRender3D()          { /* extra 3D passes over the world: rain, snow, particles */ }
    protected void onDraw(SpriteBatch b) { /* immediate 2D drawing in world space */ }
    protected void onGui(Gui gui)        { /* HUD and menus in screen pixels */ }
}
```

Scenes hold **entities**; entities hold **components**. Behaviour is composed, not inherited:

```java
Entity crate = createEntity("crate", 4f, 2f);
crate.add(new SpriteRenderer(Color.BROWN, 1f, 1f));
crate.add(Body2D.box(BodyType.DYNAMIC, 1f, 1f));
crate.add(new Flammable());            // your own component
```

Write your own components by extending `Component` and overriding `start`, `update`, `fixedUpdate` or
`preRender`. `engine.scenes().push(new PauseMenu())` covers a scene with an overlay; `pop()` removes it.

### Input

```java
input().isKeyPressed(Key.SPACE);      // this frame only
input().isKeyDown(Key.D);             // held
input().actions().bindKeys("jump", Key.SPACE, Key.W);
input().actions().isPressed("jump");
```

### 2D

| Tool | Use |
|---|---|
| `SpriteRenderer`, `TextureRegion` | Images and sprite-sheet frames, with layers, pivots, flipping |
| `SpriteAnimator`, `Animation` | Sprite-sheet animation (loop, once, ping-pong) |
| `Tilemap`, `TilemapRenderer` | Grid levels, including `Tilemap.fromAscii` for levels written as text |
| `Camera2D` | Follow, zoom, rotation, level bounds, `setVisibleHeight` for resolution independence |
| `Body2D`, `Physics2DWorld` | Boxes and circles: static, kinematic, dynamic; triggers; one-way platforms; raycasts; tile collision |
| `SpriteBatch` | Immediate drawing: sprites, rectangles, lines, circles |
| `PointerHandler` | Click and drag sprites (cards, puzzle pieces, in-world buttons) |

### UI, animation and timing

| Tool | Use |
|---|---|
| `Gui` | Immediate-mode panels, text, buttons, toggles, sliders, progress bars, text fields |
| `Tweens`, `Ease` | Animate any value: `tweens().move(transform, x, y, 0.3f, Ease.BACK_OUT)` |
| `Scheduler` | `scheduler().after(1f, ...)`, `scheduler().every(0.5f, ...)` |

### 3D

```java
World3D world = world3D();
world.setLighting(Lighting.sunset());
world.setGroundPlane(0f);

Entity wall = createEntity("wall");
wall.transform().setPosition(0f, 2f, -10f);
wall.add(new MeshRenderer(world.own(Primitives.box(8f, 4f, 1f, Color.GRAY))));
wall.add(new BoxCollider3D(8f, 4f, 1f));

Entity player = createEntity("player");
player.add(new CharacterController3D());
player.add(new FirstPersonController());   // or ThirdPersonController
```

| Tool | Use |
|---|---|
| `World3D` | Camera, sun and sky, shadows, terrain, 3D collision, raycasts |
| `Primitives`, `MeshBuilder3D` | Boxes, spheres, cylinders, cones, capsules, planes — or combine shapes into one mesh |
| `MeshRenderer` | Lit, shadowed meshes; shared meshes draw instanced |
| `ModelRenderer` | glTF/FBX models from `assets().model(...)`, with skeletal animation |
| `BoxCollider3D`, `CharacterController3D` | Walls and platforms; walking, jumping, stepping up ledges |
| `FirstPersonController`, `ThirdPersonController`, `FlyCameraController` | Ready-made player and camera input |
| `Lighting` | Day, sunset, overcast and night presets |
| `ScreenEffects` | The player's picture settings over any 3D scene: ambient occlusion, bloom, FXAA or 2x supersampling (Settings > Video > Graphics) |

### Open worlds

```java
float water = -4f;
Terrain terrain = new Terrain(new NoiseHeights(42), TerrainColors.grassland(water, 58f),
        Terrain.Settings.defaults().withWaterLevel(water).withViewDistance(10));
terrain.setDecorator((chunk, ground, out) -> { /* place trees and rocks */ });
world3D().setTerrain(terrain);
```

Terrain streams in chunks around the camera on background threads, so worlds can be endless.
`HeightmapHeights` builds terrain from an authored heightmap instead of noise.

### Hand-made maps and the map editor

For a set, designed world rather than an endless one, build it in the in-engine map editor and load it
as a `WorldMap`:

```java
WorldMap map = WorldMapIO.load(path);
world3D().setTerrain(new MapTerrain(map, palette, prefabs));
```

A map holds sculpted heights, painted ground types, placed props, **zones** (painted areas with
game-defined meaning and settings) and **markers** (named points). `MapEditorScene` edits all of it:

| Tab | What it does |
|---|---|
| Sculpt | Raise, lower, smooth, flatten and roughen brushes |
| Land | Raise whole islands with a click, paint land up out of the sea, carve sea and rivers, grow mountain ranges; new ground is painted to suit its shape |
| Paint | Paint ground types; set the water level |
| Assets | An asset drawer of pictured props by category, with search: place, paint (like grass, with density, spacing, slope and keep-out-of-water), select/move/rotate/scale and erase |
| Roads | Draw a road network from click to click; junctions, crossroads and roundabouts get the right pieces |
| Splines | Draw a line through points, curving or with sharp corners, open or a closed loop, and lay it as a road, path, raised footpath, wall or railing in any texture from the asset library, or as a row of models (houses, trees) picked at random a gap apart; roads can carry footpaths and railings, walls or houses along their sides; roads meeting at a point join in a junction; the ground under them is graded |
| Zones | Create zones of the kinds your game defines, edit their settings, paint them on |
| Markers | Move the player spawn; place markers such as healing springs |
| Map | Rename, save, reload, resize the map around what is on it, grow it with sea on any side, generate a new island (up to 4096 cells) or a flat map |

Splines (`WorldMap.splines`, `MapSpline`) are kept as their points and settings and laid by `SplineMeshes` whenever
they change; a game draws them by giving its `MapTerrain` a `SplineLook` (`SplineMaterials` loads textures from its
assets), and walls and railings collide. `MapEditorConfig.SplineSetup` names the folder of textures the editor offers
(`tex/` by default, every `*_albedo.png` in it, its `_normal.png` beside it used too) and what each kind wears at first.
A texture whose picture runs top to bottom, like a path drawn down its image, is turned to run along the line by itself.

Where splines share a point (`SplineJunctions`), two meeting end to end join in a mitre, and three or more roads make a
junction: the two most nearly in line run through with their markings, every other road stops at their edge on a slant
that follows it, and footpaths and railings open and close round the corners. A point put down on a road's line joins it
there, and a road drawn across another makes a crossroads. A row of models (`SplineModels`) is laid as ordinary map
objects marked with their spline (`MapObject.owner`, map format 7), laid again whenever it changes, never on a road, in
the water or on another building.

Right-drag looks, WASD flies, the mouse wheel or `[ ]` resizes the brush, Ctrl+Z/Ctrl+Y undo and redo,
Ctrl+S saves and F5 test-plays from the cursor. An overview of the whole map sits in the corner (M hides it);
click it to fly there.

Maps are built for size: ground is meshed only near the camera and in less detail far away, painted props are
drawn within a distance that suits their size, and undo keeps only the ground an edit touched.

A **tiled** map (`WorldMap.setTiled`, made from a smooth one by `TileConversion`) is a grid of flat tiles in whole
levels, and `TileMapEditorScene` edits it in layers, each standing on the one under it:

| Layer | What it holds |
|---|---|
| Terrain | Tile heights (raise, lower, set a level) and ground types, picked from pictures of their textures |
| Paths and roads | Grounds laid over the ground a block at a time (`WorldMap.tileOverlay`); the ground under a path is kept |
| Objects | Props picked from pictures and placed square to the tiles; they stand on the ground — a solid one on the highest tile under it — so reshaping the terrain carries them with it |
| Zones | Painted areas, as in the 3D editor |
| Markers | Named points and the spawn |

Any layer can be hidden (the one being edited always shows) or locked, and objects hidden kind by kind. The world view
draws the map as the game will — through the game's camera and, given a `TileLook`, in its textures — with the brush
and the asset about to be placed shown on the tiles; Tab switches to a flat map of the whole world. Z and X turn the
view, T looks straight down, 1-5 pick the layer.

A game's own editors can work the same way: `TileWorldView` is that world view's camera, `TileRay` picks on any grid
of tile heights, and `EditorWidgets` draws the layers' eyes and padlocks and the cards of pictures. A room editor can be
built from them — a room in layers of floor and walls, rugs, furniture, what hangs on the walls, and people, seen as
the game shows it.

Model files become props: `ModelPrefabs.registerFolder(catalog, folder)` adds every `.glb`, `.gltf`, `.obj` or
`.fbx` under a folder, in a category named after its subfolder, drawn in its materials' colours. Rigged models are
skipped — a file with bones is a character for `ModelRenderer`, not scenery — so one folder can hold both, and
`ModelPrefabs.register` gives a colour and a scale to a model exported without a material or at the wrong size.
A game might read `projects/MyGame/assets/prefabs/<Category>/` and `projects/MyGame/assets/models/`. A game describes
its materials, props, zone kinds (with validated settings) and marker kinds in a `MapEditorConfig`.

### Platforms: Steam and other stores

`Platform` is what a game gets from the store it ships on: the player, their friends, messages to other players,
invitations, achievements, cloud files and rich presence. Games use only the interface, so changing stores means one
new implementation.

| Implementation | Use |
|---|---|
| `SteamPlatform` | Steam, calling the Steamworks SDK's own library through Java's foreign function API: no wrapper to build |
| `LocalPlatform` | No service, for development and tests; players on one `LocalPlatform.Network` are each other's friends |

```java
switch (SteamPlatform.start(appId, developer, List.of(folderWithSteamLibrary))) {
    case SteamPlatform.Start.Started started -> platform = started.platform();
    case SteamPlatform.Start.Relaunching relaunching -> engine.quit();   // Steam is starting the game itself
    case SteamPlatform.Start.Failed failed -> showError(failed.reason());
}
```

Call `platform.update()` from `Game.update`, which runs once a frame for as long as the game does. `PeerLinks` turns a
platform's messages into `Connection`s, so anything written for a socket also runs between friends. The Steamworks
SDK isn't in this repository (Valve's licence doesn't allow publishing it): a game ships Steam's library from it.

## Layout

```
org.zeronura.engine
  core         Engine, Game, EngineConfig, Time, Scheduler, Assets, Screenshot
  scene        Scene, SceneManager, Entity, Component, Transform, PointerHandler; SceneFile, FileScene, Signal,
               ComponentFields (public fields as settings), SceneHost
  studio       The editor: Studio, StudioScene and its panels
  input        Input, InputMap, Key, MouseButton
  graphics2d   SpriteBatch, SpriteRenderer, Camera2D, Tilemap, Animation, Color
  physics2d    Physics2DWorld, Body2D, triggers and raycasts
  gui          Gui, Tweens, Ease
  graphics3d   World3D, MeshRenderer, ModelRenderer, controllers, primitives
  terrain      Terrain, NoiseHeights, HeightmapHeights, Noise, TerrainSurface
  worldmap     WorldMap, MapTerrain, Brushes, PrefabCatalog, IslandGenerator, WorldMapIO
  editor       MapEditorScene, MapEditorConfig, EditorHistory
  project      ProjectHub, ProjectCreator, Projects
  platform     Platform, LocalPlatform, PeerLinks; platform.steam: SteamPlatform
  render, physics, animation, asset, audio, fluid, ui   low-level systems the above are built on
```

The editor is layered on the same framework games use: scenes are plain entity/component data, components expose
their settings as public fields, and the editor itself is a game the engine runs.
