package moe.afox.mcfpm.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import org.apache.maven.index.reader.Record;
import org.junit.jupiter.api.Test;

class CandidateStateTest {
    @Test
    void appliesOnlyUnclassifiedMcfpkgAddsAndRemoves() {
        CandidateState state = new CandidateState();
        state.apply(record(Record.Type.ARTIFACT_ADD, "mcfpkg", null));
        assertEquals(1, state.values().size());

        state.apply(record(Record.Type.ARTIFACT_ADD, "zip", null));
        assertEquals(1, state.values().size());

        state.apply(record(Record.Type.ARTIFACT_REMOVE, "mcfpkg", null));
        assertTrue(state.values().isEmpty());
    }

    @Test
    void ignoresSnapshotsAndClassifiers() {
        CandidateState state = new CandidateState();
        state.apply(record(Record.Type.ARTIFACT_ADD, "mcfpkg", "docs"));
        state.apply(record(Record.Type.ARTIFACT_ADD, "mcfpkg", null, "1.0.0-SNAPSHOT"));
        assertTrue(state.values().isEmpty());
    }

    private static Record record(Record.Type type, String extension, String classifier) {
        return record(type, extension, classifier, "1.2.3");
    }

    private static Record record(Record.Type type, String extension, String classifier, String version) {
        var fields = new HashMap<Record.EntryKey, Object>();
        fields.put(Record.GROUP_ID, "org.example");
        fields.put(Record.ARTIFACT_ID, "demo");
        fields.put(Record.VERSION, version);
        fields.put(Record.FILE_EXTENSION, extension);
        if (classifier != null) {
            fields.put(Record.CLASSIFIER, classifier);
        }
        return new Record(type, fields);
    }
}
