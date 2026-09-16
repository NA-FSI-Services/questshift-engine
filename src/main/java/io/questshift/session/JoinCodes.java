package io.questshift.session;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Shoutable party codes: two dungeon words, hyphenated, case-insensitive. */
public final class JoinCodes {

    static final String[] WORDS = {
        "ash", "cipher", "cluster", "ember", "forge", "gate", "glyph", "golem",
        "granite", "helm", "iron", "moss", "oak", "pipe", "rune", "shard",
        "shell", "spark", "thorn", "throne", "tome", "torch", "vault", "ward"
    };

    private JoinCodes() {}

    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isJoinCode(String raw) {
        return normalize(raw).matches("[a-z]+-[a-z]+");
    }

    public static String allocate(Set<String> taken) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int n = WORDS.length;
        for (int attempt = 0; attempt < n * (n - 1) * 2; attempt++) {
            int first = rng.nextInt(n);
            int second = rng.nextInt(n - 1);
            if (second >= first) {
                second++;
            }
            String code = WORDS[first] + "-" + WORDS[second];
            if (!taken.contains(code)) {
                return code;
            }
        }
        int suffix = 2;
        String candidate = WORDS[0] + "-" + WORDS[1] + "-" + suffix;
        while (taken.contains(candidate)) {
            suffix++;
            candidate = WORDS[0] + "-" + WORDS[1] + "-" + suffix;
        }
        return candidate;
    }
}
