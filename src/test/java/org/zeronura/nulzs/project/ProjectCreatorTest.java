package org.zeronura.nulzs.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProjectCreatorTest {

    @TempDir
    Path temp;

    private ProjectCreator creator() {
        return new ProjectCreator(temp.resolve("projects"), temp.resolve("src"));
    }

    @Test
    void aBlankProjectIsCreatedAndLoadsBack() throws IOException {
        ProjectInfo created = creator().createBlank("SpaceRacer", "Race through space");

        ProjectInfo loaded = ProjectInfo.load(created.directory());
        assertEquals("SpaceRacer", loaded.name());
        assertEquals("Race through space", loaded.description());
        assertEquals("org.zeronura.games.spaceracer.SpaceRacerGame", loaded.gameClass());
        assertEquals(1920, loaded.width());
        assertEquals(1080, loaded.height());
        assertTrue(Files.isDirectory(loaded.assetsDirectory()));
        assertTrue(Files.exists(temp.resolve("src/org/zeronura/games/spaceracer/SpaceRacerGame.java")));
        assertTrue(Files.exists(temp.resolve("src/org/zeronura/games/spaceracer/MainScene.java")));

        assertEquals(1, Projects.list(temp.resolve("projects")).size());
        assertTrue(Projects.find(temp.resolve("projects"), "spaceracer").isPresent());
    }

    @Test
    void aProjectOfItsOwnKeepsItsBuildAndCodeInItsFolder() throws IOException {
        ProjectInfo created = new ProjectCreator(temp.resolve("projects"), null).createBlank("SpaceRacer", "");
        Path folder = temp.resolve("projects/SpaceRacer");

        assertTrue(created.hasOwnBuild());
        assertEquals(folder.resolve("src/main/java"), created.sourceRoot());
        assertEquals(folder.resolve("target/classes"), created.classesDirectory());
        assertTrue(Files.exists(folder.resolve("src/main/java/org/zeronura/games/spaceracer/SpaceRacerGame.java")));
        assertTrue(Files.exists(folder.resolve("src/main/java/org/zeronura/games/spaceracer/MainScene.java")));
        assertFalse(Files.exists(temp.resolve("src")), "nothing written outside the project's folder");
        String pom = Files.readString(folder.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>nulzs-game-engine</artifactId>"), "the engine is its library");
        assertTrue(pom.contains("<artifactId>spaceracer</artifactId>"));
        assertTrue(pom.contains("<version>${nulzs.engine.version}</version>"));
    }

    @Test
    void aProjectWithoutABuildOfItsOwnUsesTheWorkingFoldersCode() throws IOException {
        ProjectInfo created = creator().createBlank("SpaceRacer", "");
        assertFalse(created.hasOwnBuild());
        assertEquals(Path.of("src", "main", "java"), created.sourceRoot());
        assertEquals(Path.of("target", "classes"), created.classesDirectory());
    }

    @Test
    void badAndDuplicateNamesAreRejected() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> creator().createBlank("lowercase", ""));
        assertThrows(IllegalArgumentException.class, () -> creator().createBlank("Has Space", ""));
        assertThrows(IllegalArgumentException.class, () -> creator().createBlank("", ""));

        creator().createBlank("Twice", "");
        assertThrows(FileAlreadyExistsException.class, () -> creator().createBlank("Twice", ""));
    }

    @Test
    void namesThatAreJavaKeywordsStillGetAValidPackage() {
        assertEquals("org.zeronura.games.intgame", ProjectCreator.packageFor("Int"));
        assertEquals("org.zeronura.games.tetris", ProjectCreator.packageFor("Tetris"));
    }

    @Test
    void theGeneratedCodeCompilesAgainstTheEngine() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no system Java compiler");
        creator().createBlank("Demo", "");

        List<String> arguments = new ArrayList<>(List.of("-d", temp.resolve("out").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))));
        try (Stream<Path> sources = Files.walk(temp.resolve("src"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> arguments.add(p.toString()));
        }

        assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)));
    }

    @Test
    void theDefaultProjectRoundTrips() throws IOException {
        Path settings = temp.resolve("engine.properties");
        assertTrue(Projects.activeProject(settings).isEmpty());

        Projects.setActiveProject(settings, "Demo");
        assertEquals("Demo", Projects.activeProject(settings).orElseThrow());

        Projects.setActiveProject(settings, null);
        assertTrue(Projects.activeProject(settings).isEmpty());
    }
}
