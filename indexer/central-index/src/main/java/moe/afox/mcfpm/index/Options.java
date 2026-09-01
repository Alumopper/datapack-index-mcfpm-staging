package moe.afox.mcfpm.index;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;

record Options(URI remote, Path cache, Path state, Path output, Path marker) {
    static Options parse(String[] arguments) {
        var values = new HashMap<String, String>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (index + 1 >= arguments.length || !arguments[index].startsWith("--")) {
                throw usage();
            }
            values.put(arguments[index].substring(2), arguments[index + 1]);
        }
        URI remote = URI.create(values.getOrDefault("remote", "https://repo.maven.apache.org/maven2/.index/"));
        if (!"https".equalsIgnoreCase(remote.getScheme())) {
            throw new IllegalArgumentException("Central index remote must use HTTPS");
        }
        Path cache = requiredPath(values, "cache");
        Path state = requiredPath(values, "state");
        Path output = requiredPath(values, "output");
        Path marker = Path.of(values.getOrDefault("marker", cache.resolve("candidate-state.commit").toString()));
        return new Options(remote, cache, state, output, marker);
    }

    private static Path requiredPath(HashMap<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw usage();
        }
        return Path.of(value);
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "usage: central-index-scanner --cache DIR --state FILE --output FILE [--remote HTTPS_URL] [--marker FILE]");
    }
}
