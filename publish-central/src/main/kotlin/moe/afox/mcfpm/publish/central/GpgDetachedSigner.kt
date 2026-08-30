package moe.afox.mcfpm.publish.central

import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

public class GpgDetachedSigner(
    private val executable: String = "gpg",
    private val timeout: Duration = Duration.ofMinutes(2),
) : DetachedSignatureVerifier {
    public fun sign(files: Map<String, ByteArray>, signingKey: String): McfpmResult<Map<String, ByteArray>> {
        if (signingKey.isBlank()) return failure("OpenPGP signing key is required")
        val signatures = linkedMapOf<String, ByteArray>()
        files.toSortedMap().forEach { (fileName, content) ->
            val signature = withTemporaryPair(fileName, content, null) { contentPath, signaturePath ->
                val outcome = run(
                    listOf(
                        executable,
                        "--batch",
                        "--yes",
                        "--armor",
                        "--detach-sign",
                        "--local-user",
                        signingKey,
                        "--output",
                        signaturePath.toString(),
                        contentPath.toString(),
                    ),
                )
                require(outcome.exitCode == 0) { "gpg signing failed for $fileName: ${outcome.output}" }
                Files.readAllBytes(signaturePath)
            }
            when (signature) {
                is McfpmResult.Success -> signatures[fileName] = signature.value
                is McfpmResult.Failure -> return signature
            }
        }
        return McfpmResult.Success(signatures)
    }

    override fun verify(content: ByteArray, armoredSignature: ByteArray): Boolean =
        when (val result = withTemporaryPair("verify", content, armoredSignature) { contentPath, signaturePath ->
            run(listOf(executable, "--batch", "--verify", signaturePath.toString(), contentPath.toString())).exitCode == 0
        }) {
            is McfpmResult.Success -> result.value
            is McfpmResult.Failure -> false
        }

    private fun <T> withTemporaryPair(
        label: String,
        content: ByteArray,
        signature: ByteArray?,
        operation: (Path, Path) -> T,
    ): McfpmResult<T> {
        val directory = Files.createTempDirectory("mcfpm-gpg-${UUID.randomUUID()}").toAbsolutePath().normalize()
        val contentPath = directory.resolve("content.bin")
        val signaturePath = directory.resolve("content.asc")
        return try {
            Files.write(contentPath, content)
            if (signature != null) Files.write(signaturePath, signature)
            McfpmResult.Success(operation(contentPath, signaturePath))
        } catch (exception: Exception) {
            failure("OpenPGP operation failed for $label: ${exception.message}")
        } finally {
            Files.deleteIfExists(signaturePath)
            Files.deleteIfExists(contentPath)
            Files.deleteIfExists(directory)
        }
    }

    private fun run(command: List<String>): ProcessOutcome {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            outputFuture.cancel(true)
            throw IllegalStateException("gpg timed out after ${timeout.seconds} seconds")
        }
        val output = outputFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS).trim().take(2_000)
        return ProcessOutcome(process.exitValue(), output)
    }

    private fun <T> failure(message: String): McfpmResult<T> = McfpmResult.Failure(
        listOf(Diagnostic(DiagnosticCode.PUBLISH_VALIDATION_FAILED, DiagnosticSeverity.ERROR, message)),
    )

    private data class ProcessOutcome(val exitCode: Int, val output: String)
}
