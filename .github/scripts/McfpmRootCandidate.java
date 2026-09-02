import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import kotlin.Pair;
import kotlin.jvm.internal.DefaultConstructorMarker;
import moe.afox.mcfpm.core.FrozenImportCandidate;
import moe.afox.mcfpm.core.ImportCandidateCodec;
import moe.afox.mcfpm.core.ImportCandidateDocument;
import moe.afox.mcfpm.core.ImportCandidatePayload;
import moe.afox.mcfpm.core.ImportCandidateSource;
import moe.afox.mcfpm.core.ReproducibleZip;
import moe.afox.mcfpm.core.ZipContents;
import moe.afox.mcfpm.core.ZipSafetyLimits;
import moe.afox.mcfpm.model.SemVer;

/**
 * Re-selects the root pack after Mcfpm has authenticated and audited the exact
 * upstream archive through a harmless nested overlay. This is intentionally
 * narrow: only a root datapack is accepted, and every upstream identity field
 * remains the one frozen by Mcfpm.
 */
public final class McfpmRootCandidate {
    private McfpmRootCandidate() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: BOOTSTRAP_CANDIDATE RAW_ZIP EXPECTED_BOOTSTRAP_ROOT OUTPUT_CANDIDATE");
        }
        Path bootstrapPath = Path.of(args[0]);
        Path rawPath = Path.of(args[1]);
        String expectedBootstrapRoot = args[2];
        Path outputPath = Path.of(args[3]);

        FrozenImportCandidate bootstrap = ImportCandidateCodec.INSTANCE.decode(Files.readAllBytes(bootstrapPath));
        ImportCandidateDocument document = bootstrap.getDocument();
        ImportCandidateSource oldSource = document.getSource();
        if (!expectedBootstrapRoot.equals(oldSource.getSelectedRoot()) || oldSource.getNestedZip() != null) {
            throw new IllegalArgumentException("Bootstrap candidate did not select the expected non-nested audit pack");
        }

        byte[] raw = Files.readAllBytes(rawPath);
        String rawSha256 = sha256(raw);
        if (!rawSha256.equals(oldSource.getRawSha256()) || raw.length != oldSource.getRawSize()) {
            throw new IllegalArgumentException("Downloaded source does not match the Mcfpm-audited upstream bytes");
        }

        ZipSafetyLimits limits = new ZipSafetyLimits();
        ZipContents contents = ReproducibleZip.INSTANCE.readEntries(raw, limits);
        List<Pair<String, byte[]>> entries = contents.getEntries();
        boolean metadata = entries.stream().anyMatch(entry -> entry.getFirst().equals("pack.mcmeta"));
        boolean datapack = entries.stream().anyMatch(entry -> entry.getFirst().startsWith("data/"));
        boolean resourcePack = entries.stream().anyMatch(entry -> entry.getFirst().startsWith("assets/"));
        if (!metadata || !datapack || resourcePack) {
            throw new IllegalArgumentException("Selected root is not an unambiguous Minecraft datapack");
        }

        byte[] payloadBytes = ReproducibleZip.INSTANCE.fromEntries(entries);
        ReproducibleZip.INSTANCE.verify(payloadBytes, true, limits);
        String normalizedSha256 = sha256(payloadBytes);

        ImportCandidateSource source = new ImportCandidateSource(
            oldSource.getKind(),
            oldSource.getRequestUrl(),
            oldSource.getFinalUrl(),
            oldSource.getRawSha256(),
            oldSource.getRawSize(),
            oldSource.getImmutableVersion(),
            "/",
            null,
            null,
            oldSource.getUpstreamId(),
            oldSource.getRevision(),
            oldSource.getReleaseId(),
            oldSource.getAssetId(),
            oldSource.getAssetName()
        );
        ImportCandidatePayload payload = payload("minecraft.datapack", "datapack", normalizedSha256, payloadBytes.length);
        String packageId = packageId(document);
        ImportCandidateDocument rootDocument = candidateDocument(
            document.getSchema(), source, packageId, document.getVersion(), document.getLicense(),
            document.getMinecraft(), document.getDependencies(), payload
        );
        byte[] encoded = ImportCandidateCodec.INSTANCE.encode(new FrozenImportCandidate(rootDocument, payloadBytes));
        Path parent = outputPath.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".root-candidate-", ".tmp");
        Files.write(temporary, encoded);
        Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("{\"normalizedSha256\":\"%s\",\"normalizedSize\":%d}%n", normalizedSha256, payloadBytes.length);
    }

    private static String packageId(ImportCandidateDocument document) throws Exception {
        Method getter = ImportCandidateDocument.class.getDeclaredMethod("getPackageId-7BlZRqk");
        getter.setAccessible(true);
        return (String) getter.invoke(document);
    }

    private static ImportCandidatePayload payload(String type, String classifier, String sha256, long size) throws Exception {
        Constructor<ImportCandidatePayload> constructor = ImportCandidatePayload.class.getDeclaredConstructor(
            String.class, String.class, String.class, long.class, DefaultConstructorMarker.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(type, classifier, sha256, size, null);
    }

    private static ImportCandidateDocument candidateDocument(
        int schema,
        ImportCandidateSource source,
        String packageId,
        SemVer version,
        String license,
        String minecraft,
        List<?> dependencies,
        ImportCandidatePayload payload
    ) throws Exception {
        Constructor<ImportCandidateDocument> constructor = ImportCandidateDocument.class.getDeclaredConstructor(
            int.class, ImportCandidateSource.class, String.class, SemVer.class, String.class, String.class,
            List.class, ImportCandidatePayload.class, DefaultConstructorMarker.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(schema, source, packageId, version, license, minecraft, dependencies, payload, null);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
