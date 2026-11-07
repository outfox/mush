package dev.twodog.mush.scene

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SceneTextTest {
    private val scene = """
        [gd_scene format=3]

        [ext_resource type="PackedScene" path="res://scenes/world.tscn" id="1_world"]

        [node name="Root" type="Node"]

        [node name="World" parent="." instance=ExtResource("1_world")]

        [node name="UI" type="CanvasLayer" parent="."]

        [node name="Label" type="Label" parent="UI"]
        offset_left = 20.0
        text = "a string can run over lines
        [not a section] even looking like this
        [node name=\"Fake\" parent=\".\"] or exactly like this
        [ext_resource path=\"fake\"] or this
        and an \"escaped quote\" does not end it
        keeps its section"

        [connection signal="pressed" from="UI" to="."]
        extra = 1
    """.trimIndent()

    private fun lineOf(prefix: String) = scene.lines().indexOfFirst { it.startsWith(prefix) }

    @Test fun `the caret finds the node section it is in`() {
        assertEquals(".", SceneText.nodePathAt(scene, lineOf("[node name=\"Root\"")))
        assertEquals("World", SceneText.nodePathAt(scene, lineOf("[node name=\"World\"")))
        assertEquals("UI", SceneText.nodePathAt(scene, lineOf("[node name=\"UI\"")))
        assertEquals("UI/Label", SceneText.nodePathAt(scene, lineOf("offset_left")))
    }

    @Test fun `a multi-line string cannot start a new section`() {
        assertEquals("UI/Label", SceneText.nodePathAt(scene, lineOf("keeps its section")))
        assertEquals("UI/Label", SceneText.nodePathAt(scene, lineOf("[node name=\\\"Fake\\\"")))
        assertEquals("UI/Label", SceneText.nodePathAt(scene, lineOf("[ext_resource path=\\\"fake\\\"")))
        assertEquals("a click cannot land in string content",
            lineOf("[node name=\"Root\""), SceneText.lineOfNode(scene, "Fake"))
    }

    @Test fun `escaped names and parents decode to the real node path`() {
        val escaped = """
            [gd_scene format=3]

            [node name="Root" type="Node"]

            [node name="back\\slash" type="Node" parent="."]

            [node name="Child" type="Node" parent="back\\slash"]
        """.trimIndent()
        assertEquals("""back\slash""", SceneText.nodePathAt(escaped, 4))
        assertEquals("""back\slash/Child""", SceneText.nodePathAt(escaped, 6))
        assertEquals(6, SceneText.lineOfNode(escaped, """back\slash/Child"""))
    }

    @Test fun `other sections select nothing`() {
        assertNull(SceneText.nodePathAt(scene, lineOf("[gd_scene")))
        assertNull(SceneText.nodePathAt(scene, lineOf("[ext_resource")))
        assertNull(SceneText.nodePathAt(scene, lineOf("extra = 1")))
    }

    @Test fun `a clicked node finds its section`() {
        assertEquals(lineOf("[node name=\"Root\""), SceneText.lineOfNode(scene, "."))
        assertEquals(lineOf("[node name=\"Label\""), SceneText.lineOfNode(scene, "UI/Label"))
    }

    @Test fun `a node without a section falls back to its nearest ancestor`() {
        assertEquals(lineOf("[node name=\"World\""), SceneText.lineOfNode(scene, "World/Box/Mesh"))
        assertEquals(lineOf("[node name=\"Root\""), SceneText.lineOfNode(scene, "Spawned"))
    }

    @Test fun `problems in the temp copy point at the document`() {
        val parseError = "Parse Error: Parse error. [Resource file res://.godot/mush/77B1D27D-20364.tscn:5]"
        val missing = "res://.godot/mush/77B1D27D-20364.tscn:8 - Parse Error: [ext_resource] referenced non-existent resource"
        assertEquals(4, SceneText.problemLine(parseError))
        assertEquals(7, SceneText.problemLine(missing))
        assertEquals("Parse Error: Parse error. [Resource file res://a b/\$x.tscn:5]", SceneText.problemText(parseError, "res://a b/\$x.tscn"))
    }

    @Test fun `problems elsewhere never mark this document`() {
        assertNull(SceneText.problemLine("res://scenes/sub.tscn:3 - Parse Error: nope"))
        assertNull(SceneText.problemLine("Resource file not found: res://art/typo.svg (expected type: Texture2D)"))
    }

    @Test fun `hosts starting together all get the blank scene`() {
        val root = Files.createTempDirectory("mush-blank")
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(50) { round ->
                val project = root.resolve("p$round")
                val start = CountDownLatch(1)
                val publishers = List(8) {
                    pool.submit {
                        start.await()
                        GodotProject.ensureBlankScene(project.toString())
                    }
                }
                start.countDown()
                publishers.forEach { it.get(5, TimeUnit.SECONDS) }
                val dir = project.resolve(".godot/mush")
                assertEquals(listOf("blank.tscn"), Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() })
                assertTrue(Files.readString(dir.resolve("blank.tscn")).contains("MushBlank"))
            }
        } finally {
            pool.shutdownNow()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `only other scene files in the project trigger a reload`() {
        val root = "P:/game"
        val self = "P:/game/scenes/a.tscn"
        assertTrue(GodotProject.isSceneDependency("P:/game/scenes/b.tscn", root, self))
        assertTrue(GodotProject.isSceneDependency("P:/game/theme.TRES", root, self))
        assertTrue(GodotProject.isSceneDependency("P:/game/fx.gdshader", root, self))
        assertTrue(GodotProject.isSceneDependency("P:/game/common.gdshaderinc", root, self))
        assertFalse("its own saves already reached the host", GodotProject.isSceneDependency(self, root, self))
        assertFalse("the host's own temp files would loop", GodotProject.isSceneDependency("P:/game/.godot/mush/x.tscn", root, self))
        assertFalse(GodotProject.isSceneDependency("P:/other/b.tscn", root, self))
        assertFalse(GodotProject.isSceneDependency("P:/game/art/mush.png", root, self))
    }
}
