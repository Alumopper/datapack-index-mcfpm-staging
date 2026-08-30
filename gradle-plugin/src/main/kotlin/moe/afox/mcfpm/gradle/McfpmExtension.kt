package moe.afox.mcfpm.gradle

import javax.inject.Inject
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

public abstract class McfpmPayloadSpec @Inject constructor(
    private val payloadName: String,
) : Named {
    override fun getName(): String = payloadName

    public abstract val source: DirectoryProperty
    public abstract val type: Property<String>
    public abstract val classifier: Property<String>
}

public abstract class McfpmExtension @Inject constructor(
    objects: ObjectFactory,
    layout: ProjectLayout,
) {
    public val manifest: RegularFileProperty = objects.fileProperty()
        .convention(layout.projectDirectory.file("mcfpm.toml"))
    public val lockfile: RegularFileProperty = objects.fileProperty()
        .convention(layout.projectDirectory.file("mcfpm.lock"))
    public val consumerProfile: Property<String> = objects.property(String::class.java)
        .convention("all")
    public val payloads: NamedDomainObjectContainer<McfpmPayloadSpec> =
        objects.domainObjectContainer(McfpmPayloadSpec::class.java)
}
