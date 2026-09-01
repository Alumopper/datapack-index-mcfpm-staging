package moe.afox.mcfpm.index;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.apache.maven.index.reader.ChunkReader;
import org.apache.maven.index.reader.IndexReader;
import org.apache.maven.index.reader.Record;
import org.apache.maven.index.reader.RecordExpander;
import org.apache.maven.index.reader.resource.BufferedResourceHandler;
import org.apache.maven.index.reader.resource.BufferedWritableResourceHandler;
import org.apache.maven.index.reader.resource.PathWritableResourceHandler;
import org.apache.maven.index.reader.resource.UriResourceHandler;

public final class CentralIndexScanner {
    private static final String PROPERTIES_FILE = "nexus-maven-repository-index.properties";

    private CentralIndexScanner() {}

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        Files.createDirectories(options.cache());
        createParent(options.state());
        createParent(options.output());
        repairInterruptedCommit(options.cache(), options.marker());

        CandidateState state = readState(options.state());
        RecordExpander expander = new RecordExpander();
        boolean incremental;
        Instant published;
        int chunks = 0;
        long records = 0;

        var local = new BufferedWritableResourceHandler(new PathWritableResourceHandler(options.cache()));
        var remote = new BufferedResourceHandler(new UriResourceHandler(options.remote()));
        try (IndexReader reader = new IndexReader(local, remote)) {
            incremental = reader.isIncremental();
            published = reader.getPublishedTimestamp().toInstant();
            if (!incremental) {
                state.clear();
            }
            for (ChunkReader chunk : reader) {
                try (chunk) {
                    chunks++;
                    for (Map<String, String> raw : chunk) {
                        Record record = expander.apply(raw);
                        state.apply(record);
                        records++;
                    }
                }
            }
            writeState(options.state(), state);
            writeOutput(options.output(), state, incremental, published, chunks, records);
        }
        writeMarker(options.cache(), options.marker());
        System.out.printf(
                "{\"ok\":true,\"incremental\":%s,\"chunks\":%d,\"records\":%d,\"candidates\":%d}%n",
                incremental, chunks, records, state.values().size());
    }

    private static CandidateState readState(Path path) throws IOException {
        CandidateState state = new CandidateState();
        if (!Files.exists(path)) {
            return state;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                if (fields.length != 3) {
                    throw new IOException("invalid Central candidate state line");
                }
                state.put(new CandidateState.Candidate(fields[0], fields[1], fields[2]));
            }
        }
        return state;
    }

    private static void writeState(Path path, CandidateState state) throws IOException {
        Path temporary = siblingTemporary(path);
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            for (CandidateState.Candidate candidate : state.values().values()) {
                writer.write(candidate.group());
                writer.write('\t');
                writer.write(candidate.name());
                writer.write('\t');
                writer.write(candidate.version());
                writer.newLine();
            }
        }
        moveAtomic(temporary, path);
    }

    private static void writeOutput(
            Path path,
            CandidateState state,
            boolean incremental,
            Instant published,
            int chunks,
            long records)
            throws IOException {
        Path temporary = siblingTemporary(path);
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            writer.write("{\n  \"schemaVersion\": 1,\n");
            writer.write("  \"generatedAt\": \"" + Instant.now() + "\",\n");
            writer.write("  \"publishedAt\": \"" + published + "\",\n");
            writer.write("  \"incremental\": " + incremental + ",\n");
            writer.write("  \"chunksRead\": " + chunks + ",\n");
            writer.write("  \"recordsRead\": " + records + ",\n");
            writer.write("  \"candidates\": [\n");
            int index = 0;
            for (CandidateState.Candidate candidate : state.values().values()) {
                writer.write("    {\"group\":\"" + candidate.group() + "\",\"name\":\"" + candidate.name()
                        + "\",\"version\":\"" + candidate.version() + "\"}");
                if (++index < state.values().size()) {
                    writer.write(',');
                }
                writer.newLine();
            }
            writer.write("  ]\n}\n");
        }
        moveAtomic(temporary, path);
    }

    private static void repairInterruptedCommit(Path cache, Path marker) throws IOException {
        Path properties = cache.resolve(PROPERTIES_FILE);
        if (!Files.exists(properties)) {
            return;
        }
        String expected = Files.exists(marker) ? Files.readString(marker, StandardCharsets.US_ASCII).trim() : "";
        String actual = sha256(properties);
        if (!actual.equals(expected)) {
            Files.delete(properties);
        }
    }

    private static void writeMarker(Path cache, Path marker) throws IOException {
        Path properties = cache.resolve(PROPERTIES_FILE);
        if (!Files.exists(properties)) {
            throw new IOException("Maven index reader did not persist its properties file");
        }
        Path temporary = siblingTemporary(marker);
        Files.writeString(temporary, sha256(properties) + "\n", StandardCharsets.US_ASCII);
        moveAtomic(temporary, marker);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Path siblingTemporary(Path path) {
        return path.resolveSibling(path.getFileName() + ".tmp");
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
