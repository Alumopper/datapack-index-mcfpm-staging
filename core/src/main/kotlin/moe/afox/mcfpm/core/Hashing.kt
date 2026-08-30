package moe.afox.mcfpm.core

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

public object Hashing {
    public fun sha256(bytes: ByteArray): String =
        digest(bytes.inputStream())

    public fun sha256(path: Path): String =
        Files.newInputStream(path).use(::digest)

    public fun digest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    public fun verifySha256(path: Path, expected: String): Boolean =
        sha256(path) == expected
}
