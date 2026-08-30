package moe.afox.mcfpm.source.github

import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.VersionRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubImportRecipeCodecTest {
    @Test
    fun `recipe round trips canonically and rejects unknown keys`() {
        val recipe = GitHubImportRecipe(
            source = RecipeSource(
                repository = GitHubRepository.parse("Elemend/Builders-Wand"),
                asset = "builders_wand_2.1.2.zip",
            ),
            packageConfiguration = RecipePackage(
                packageId = PackageId.parse("io.github.elemend:builders-wand"),
                license = "MIT",
                type = PayloadType.MINECRAFT_DATAPACK,
                classifier = "datapack",
            ),
            repository = RecipeRepository("private-releases"),
            dependencies = mapOf(PackageId.parse("example:library") to VersionRequirement.parse("^1.0.0")),
        )

        val encoded = GitHubImportRecipeCodec.encode(recipe)
        assertEquals(recipe, GitHubImportRecipeCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> {
            GitHubImportRecipeCodec.decode(encoded.decodeToString() + "\nunknown = true\n")
        }
    }
}
