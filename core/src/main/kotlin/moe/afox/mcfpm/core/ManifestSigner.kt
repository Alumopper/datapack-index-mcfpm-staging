package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.PackageManifest
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

public object ManifestSigner {
    public const val ALGORITHM: String = "Ed25519"

    public fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()

    public fun sign(manifest: PackageManifest, keyPair: KeyPair): PackageManifest {
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val fingerprint = Hashing.sha256(keyPair.public.encoded)
        val unsigned = manifest.copy(
            signatureFingerprint = fingerprint,
            signatureAlgorithm = ALGORITHM,
            signingPublicKey = publicKey,
            signature = null,
        )
        val signer = Signature.getInstance(ALGORITHM)
        signer.initSign(keyPair.private)
        signer.update(signingBytes(unsigned))
        return unsigned.copy(signature = Base64.getEncoder().encodeToString(signer.sign()))
    }

    public fun verify(manifest: PackageManifest): Boolean {
        val fields = listOf(
            manifest.signatureFingerprint,
            manifest.signatureAlgorithm,
            manifest.signingPublicKey,
            manifest.signature,
        )
        if (fields.all { it == null }) return true
        if (fields.any { it == null } || manifest.signatureAlgorithm != ALGORITHM) return false
        return runCatching {
            val publicKeyBytes = Base64.getDecoder().decode(manifest.signingPublicKey)
            if (Hashing.sha256(publicKeyBytes) != manifest.signatureFingerprint) return false
            val publicKey = KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(publicKeyBytes))
            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(signingBytes(manifest.copy(signature = null)))
            verifier.verify(Base64.getDecoder().decode(manifest.signature))
        }.getOrDefault(false)
    }

    public fun isSigned(manifest: PackageManifest): Boolean = manifest.signature != null

    private fun signingBytes(manifest: PackageManifest): ByteArray =
        CanonicalJson.encodeManifest(manifest.copy(signature = null))
}
