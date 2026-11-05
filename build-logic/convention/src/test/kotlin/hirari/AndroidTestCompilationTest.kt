package hirari

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidTestCompilationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `check compiles device tests even when compilation is registered later`() {
        val project = ProjectBuilder.builder().withProjectDir(temp.newFolder()).build()
        project.file("src/androidTest").mkdirs()
        val check = project.tasks.register("check")
        project.checkAndroidTestCompilation()
        val compilation = project.tasks.register("compileDebugAndroidTestKotlin")
        project.tasks.register("connectedDebugAndroidTest")
        assertEquals(setOf(compilation.get()), check.get().taskDependencies.getDependencies(check.get()))
    }

    @Test
    fun `check has no missing dependency when compilation does not exist`() {
        val project = ProjectBuilder.builder().withProjectDir(temp.newFolder()).build()
        project.file("src/androidTest").mkdirs()
        val check = project.tasks.register("check")
        project.checkAndroidTestCompilation()
        assertEquals(emptySet(), check.get().taskDependencies.getDependencies(check.get()))
    }

    @Test
    fun `modules without device test sources do not add compilation to check`() {
        val project = ProjectBuilder.builder().withProjectDir(temp.newFolder()).build()
        val check = project.tasks.register("check")
        project.checkAndroidTestCompilation()
        project.tasks.register("compileDebugAndroidTestKotlin")
        assertEquals(emptySet(), check.get().taskDependencies.getDependencies(check.get()))
    }
}
