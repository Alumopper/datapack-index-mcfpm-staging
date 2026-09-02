import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
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
 * Repairs one pinned upstream Simple NPC release whose pack.mcmeta omits the
 * comma between its two description components. No other archive, metadata
 * shape, package identity, or transformation is accepted.
 */
public final class McfpmSimpleNpcCandidate {
    private static final String PACKAGE_ID = "io.github.windwavessea:simple-npc";
    private static final String VERSION = "1.1.0";
    private static final String LICENSE = "MIT";
    private static final String MINECRAFT = "1.21.9+";
    private static final String RAW_SHA256 = "437a858138f03637667c761cfe3ce61323bce660c625ff9a18f9bc43318b6fd2";
    private static final long RAW_SIZE = 1_612_944L;
    private static final String SOURCE_URL = "https://github.com/WindWavesSea/Simple-NPC/releases/download/V1.1.0/Simple_NPC_Data_Pack_V1.1.0.zip";
    private static final String BROKEN_METADATA = """
        {
            "pack": {
                "description": [
                    {"text": "Simple NPC","color":"blue"}
                    {"text": "By WindWaves_Sea","color":"gold"}
                ],
                "max_format":107,
                "min_format":88
            }
        }""";
    private static final String REPAIRED_METADATA = """
        {
          "pack": {
            "description": [
              {"text":"Simple NPC","color":"blue"},
              {"text":"By WindWaves_Sea","color":"gold"}
            ],
            "max_format":107,
            "min_format":88
          }
        }
        """;

    private McfpmSimpleNpcCandidate() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: RAW_ZIP OUTPUT_CANDIDATE");
        }
        Path rawPath = Path.of(args[0]);
        Path outputPath = Path.of(args[1]);
        byte[] raw = Files.readAllBytes(rawPath);
        if (raw.length != RAW_SIZE || !RAW_SHA256.equals(sha256(raw))) {
            throw new IllegalArgumentException("Simple NPC source does not match the pinned release asset");
        }

        ZipSafetyLimits limits = new ZipSafetyLimits();
        ZipContents contents = ReproducibleZip.INSTANCE.readEntries(raw, limits);
        List<Pair<String, byte[]>> repairedEntries = new ArrayList<>();
        int metadataCount = 0;
        boolean datapack = false;
        boolean resourcePack = false;
        for (Pair<String, byte[]> entry : contents.getEntries()) {
            String name = entry.getFirst();
            byte[] value = entry.getSecond();
            if (name.equals("pack.mcmeta")) {
                metadataCount += 1;
                String metadata = new String(value, StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (!metadata.equals(BROKEN_METADATA)) {
                    throw new IllegalArgumentException("Simple NPC pack.mcmeta does not match the reviewed malformed document");
                }
                value = REPAIRED_METADATA.getBytes(StandardCharsets.UTF_8);
            }
            if (name.startsWith("data/")) datapack = true;
            if (name.startsWith("assets/")) resourcePack = true;
            repairedEntries.add(new Pair<>(name, value));
        }
        if (metadataCount != 1 || !datapack || resourcePack) {
            throw new IllegalArgumentException("Simple NPC source is not an unambiguous root datapack");
        }

        byte[] payloadBytes = ReproducibleZip.INSTANCE.fromEntries(repairedEntries);
        ReproducibleZip.INSTANCE.verify(payloadBytes, true, limits);
        String normalizedSha256 = sha256(payloadBytes);
        ImportCandidateSource source = new ImportCandidateSource(
            "url", SOURCE_URL, SOURCE_URL, RAW_SHA256, RAW_SIZE, "sha256:" + RAW_SHA256,
            "/", null, null, null, null, null, null, null
        );
        ImportCandidatePayload payload = payload(normalizedSha256, payloadBytes.length);
        ImportCandidateDocument document = candidateDocument(source, payload);
        byte[] encoded = ImportCandidateCodec.INSTANCE.encode(new FrozenImportCandidate(document, payloadBytes));

        Path parent = outputPath.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".simple-npc-candidate-", ".tmp");
        Files.write(temporary, encoded);
        Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf(
            "{\"rawSha256\":\"%s\",\"rawSize\":%d,\"normalizedSha256\":\"%s\",\"normalizedSize\":%d}%n",
            RAW_SHA256, RAW_SIZE, normalizedSha256, payloadBytes.length
        );
    }

    private static ImportCandidatePayload payload(String sha256, long size) throws Exception {
        Constructor<ImportCandidatePayload> constructor = ImportCandidatePayload.class.getDeclaredConstructor(
            String.class, String.class, String.class, long.class, DefaultConstructorMarker.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance("minecraft.datapack", "datapack", sha256, size, null);
    }

    private static ImportCandidateDocument candidateDocument(
        ImportCandidateSource source,
        ImportCandidatePayload payload
    ) throws Exception {
        Constructor<ImportCandidateDocument> constructor = ImportCandidateDocument.class.getDeclaredConstructor(
            int.class, ImportCandidateSource.class, String.class, SemVer.class, String.class, String.class,
            List.class, ImportCandidatePayload.class, DefaultConstructorMarker.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
            1, source, PACKAGE_ID, SemVer.Companion.parse(VERSION), LICENSE, MINECRAFT, List.of(), payload, null
        );
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
