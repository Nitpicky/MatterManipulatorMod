package com.ethermod.trait;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Short shareable build codes, e.g. {@code 1GABA2HAAA3BBBA}: for each filled slot its number
 * (1-3), the Module's letter, then the choice (A or B) of each tier in order. Choices stop at the
 * first tier left open, so {@code 1GA} is Gravitic in slot 1 with only its first choice.
 */
public final class BuildCode {
    private BuildCode() {}

    /** One letter per Module (the P Modules take a letter of their element). */
    public static final Map<String, Character> LETTERS =
            Map.of(
                    "plasmic", 'P',
                    "kinetic", 'K',
                    "tectonic", 'T',
                    "hydraulic", 'H',
                    "pyrotechnic", 'F',
                    "pneumatic", 'W',
                    "photonic", 'L',
                    "gravitic", 'G',
                    "botanic", 'B');

    /** One slot of a code: slot index (0-2), Module path and choices (0 = A, 1 = B), up to 3. */
    public record Slot(int slot, String module, int[] choices) {}

    public static String moduleOf(char letter) {
        char c = Character.toUpperCase(letter);
        for (var e : LETTERS.entrySet()) if (e.getValue() == c) return e.getKey();
        return null;
    }

    /** Parses a code; throws IllegalArgumentException with the offending part. */
    public static List<Slot> parse(String code) {
        String s = code.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        List<Slot> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char d = s.charAt(i);
            if (d < '1' || d > '3') throw new IllegalArgumentException(s.substring(i));
            if (i + 1 >= s.length()) throw new IllegalArgumentException(s.substring(i));
            String module = moduleOf(s.charAt(i + 1));
            if (module == null) throw new IllegalArgumentException(s.substring(i, i + 2));
            int j = i + 2;
            List<Integer> picks = new ArrayList<>();
            while (j < s.length() && (s.charAt(j) == 'A' || s.charAt(j) == 'B') && picks.size() < 3) {
                picks.add(s.charAt(j) == 'A' ? 0 : 1);
                j++;
            }
            int slot = d - '1';
            for (Slot o : out) if (o.slot == slot || o.module.equals(module)) throw new IllegalArgumentException(s.substring(i, j));
            out.add(new Slot(slot, module, picks.stream().mapToInt(Integer::intValue).toArray()));
            i = j;
        }
        return out;
    }

    /** "A"/"B" (any case) to 0/1, or -1. */
    public static int choice(String s) {
        if (s.equalsIgnoreCase("A")) return 0;
        if (s.equalsIgnoreCase("B")) return 1;
        return -1;
    }
}
