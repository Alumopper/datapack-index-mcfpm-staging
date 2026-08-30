package moe.afox.mcfpm.model

/** Small, dependency-free SPDX identifier validator used at import boundaries. */
public object SpdxLicense {
    private val identifiers: Set<String> = setOf(
        "0BSD",
        "AFL-3.0",
        "AGPL-3.0-only",
        "AGPL-3.0-or-later",
        "Apache-2.0",
        "Artistic-2.0",
        "BSL-1.1",
        "BSD-2-Clause",
        "BSD-3-Clause",
        "CC-BY-4.0",
        "CC-BY-SA-4.0",
        "CC0-1.0",
        "CDDL-1.0",
        "EPL-1.0",
        "EPL-2.0",
        "EUPL-1.2",
        "GPL-2.0-only",
        "GPL-2.0-or-later",
        "GPL-3.0-only",
        "GPL-3.0-or-later",
        "ISC",
        "LGPL-2.1-only",
        "LGPL-2.1-or-later",
        "LGPL-3.0-only",
        "LGPL-3.0-or-later",
        "MIT",
        "MIT-0",
        "MPL-2.0",
        "MS-PL",
        "OFL-1.1",
        "Unlicense",
        "WTFPL",
        "Zlib",
    )

    public fun isIdentifier(value: String): Boolean = value in identifiers

    public fun requireIdentifier(value: String): String {
        require(isIdentifier(value)) { "License must be a recognized SPDX identifier: $value" }
        return value
    }
}
