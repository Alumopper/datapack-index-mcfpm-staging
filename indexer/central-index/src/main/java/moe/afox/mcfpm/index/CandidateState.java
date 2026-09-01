package moe.afox.mcfpm.index;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.maven.index.reader.Record;

final class CandidateState {
    private static final Pattern GROUP = Pattern.compile("^[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)*$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]*$");
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

    record Candidate(String group, String name, String version) {
        String key() {
            return group + ":" + name + ":" + version;
        }
    }

    private final TreeMap<String, Candidate> candidates = new TreeMap<>();

    Map<String, Candidate> values() {
        return candidates;
    }

    void clear() {
        candidates.clear();
    }

    void put(Candidate candidate) {
        validate(candidate.group(), candidate.name(), candidate.version());
        candidates.put(candidate.key(), candidate);
    }

    void apply(Record record) {
        if (record.getType() != Record.Type.ARTIFACT_ADD && record.getType() != Record.Type.ARTIFACT_REMOVE) {
            return;
        }
        String group = record.getString(Record.GROUP_ID);
        String name = record.getString(Record.ARTIFACT_ID);
        String version = record.getString(Record.VERSION);
        if (!isValid(group, name, version)) {
            return;
        }
        String extension = record.getString(Record.FILE_EXTENSION);
        String packaging = record.getString(Record.PACKAGING);
        String classifier = record.getString(Record.CLASSIFIER);
        boolean isMcfpkg = "mcfpkg".equals(extension) || "mcfpkg".equals(packaging);
        if (!isMcfpkg || (classifier != null && !classifier.isBlank())) {
            return;
        }
        Candidate candidate = new Candidate(group, name, version);
        if (record.getType() == Record.Type.ARTIFACT_ADD) {
            candidates.put(candidate.key(), candidate);
        } else {
            candidates.remove(candidate.key());
        }
    }

    static void validate(String group, String name, String version) {
        if (!isValid(group, name, version)) {
            throw new IllegalArgumentException("invalid mcfpkg coordinate in scanner state");
        }
    }

    private static boolean isValid(String group, String name, String version) {
        return group != null
                && name != null
                && version != null
                && GROUP.matcher(group).matches()
                && NAME.matcher(name).matches()
                && SEMVER.matcher(version).matches()
                && !version.toUpperCase(java.util.Locale.ROOT).contains("SNAPSHOT");
    }
}
