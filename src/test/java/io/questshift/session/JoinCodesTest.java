package io.questshift.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JoinCodesTest {

    @Test
    void normalizeIsTrimmedLowercase() {
        assertEquals("thorn-golem", JoinCodes.normalize(" THORN-GOLEM "));
        assertEquals("", JoinCodes.normalize(null));
    }

    @Test
    void twoWordHyphenIsAJoinCodeAndUuidIsNot() {
        assertTrue(JoinCodes.isJoinCode("THORN-GOLEM"));
        assertFalse(JoinCodes.isJoinCode("11111111-2222-3333-4444-555555555555"));
        assertFalse(JoinCodes.isJoinCode(""));
    }

    @Test
    void allocateSkipsTakenCodesThenSuffixes() {
        assertTrue(JoinCodes.allocate(Set.of()).matches("[a-z]+-[a-z]+"));
        Set<String> taken = new HashSet<>();
        for (String first : JoinCodes.WORDS) {
            for (String second : JoinCodes.WORDS) {
                if (!first.equals(second)) {
                    taken.add(first + "-" + second);
                }
            }
        }
        String overflow = JoinCodes.allocate(taken);
        assertTrue(overflow.startsWith(JoinCodes.WORDS[0] + "-" + JoinCodes.WORDS[1] + "-"));
        taken.add(overflow);
        String next = JoinCodes.allocate(taken);
        assertTrue(next.startsWith(JoinCodes.WORDS[0] + "-" + JoinCodes.WORDS[1] + "-"));
        assertFalse(next.equals(overflow));
    }
}
