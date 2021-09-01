package cz.kramerius.shared;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Title {
    private static Set<String> nonsortsWithExpectedSpaces;
    private final String nonsort;
    private final String value;

    private static Set<String> initNonsortsWithExpectedSpaces() {
        Set<String> result = new HashSet<>();
        //english
        result.addAll(Arrays.asList(new String[]{"The", "A", "An"}));
        //german
        result.addAll(Arrays.asList(new String[]{"Der", "Die", "Das", "Des", "Dem", "Den", "Ein", "Eine", "Einer", "Eines", "Einem", "Einen"}));
        //spanish
        result.addAll(Arrays.asList(new String[]{"El", "La", "Lo", "Los", "Las", "Un", "Una", "Unos", "Unas"}));
        //french
        result.addAll(Arrays.asList(new String[]{"Le", "La", "Les", "Un", "Une", "Des", "De", "D", "Du", "De la", "Des"}));
        //italian
        result.addAll(Arrays.asList(new String[]{"Il", "Li", "Lo", "La", "I", "Gli", "Le", "Del", "Dello", "Della", "Dei", "Degli", "Delle", "Uno", "Una", "Un"}));
        return result;
    }

    private static Set<String> getNonsortsWithExpectedSpaces() {
        if (nonsortsWithExpectedSpaces == null) {
            nonsortsWithExpectedSpaces = initNonsortsWithExpectedSpaces();
        }
        return nonsortsWithExpectedSpaces;
    }

    public Title(String nonsort, String value) {
        this.nonsort = nonsort;
        this.value = value;
    }

    public Title(String value) {
        this(null, value);
    }

    @Override
    public String toString() {
        //original logic, that fixes possible errors, but generally accepts spaces as generated by data producer
        //return toStringWithMostlyCorrectNonsorts();
        //new logic based on very messy data from MZK: https://docs.google.com/spreadsheets/d/1xkAhIgjN-DSSpYauMNkVUddo3y-mzT3qR_TEKOd9-2c/edit#gid=1128581108
        return toStringWithMostlyIncorrectNonsorts();
    }

    private String toStringWithMostlyIncorrectNonsorts() {
        if (nonsort == null || nonsort.trim().isEmpty()) {
            return value;
        } else {
            String nonsortTrimmed = nonsort.trim();
            if (nonsortTrimmed.endsWith("'") || nonsortTrimmed.endsWith("[") || nonsortTrimmed.endsWith("\"")) {
                return nonsortTrimmed + value;
            } else {
                return nonsortTrimmed + ' ' + value;
            }
        }
    }

    private String toStringWithMostlyCorrectNonsorts() {
        if (nonsort == null || nonsort.trim().isEmpty()) {
            return value;
        } else if (nonsort.endsWith(" ")) {
            return nonsort + value;
        } else if (getNonsortsWithExpectedSpaces().contains(nonsort)) { //probably missing space, adding
            return nonsort + ' ' + value;
        } else { //nonSort not ending with space, possibly intentionally (<nonSort>L'</nonSort><title>Enfant</title> -> L'Enfant)
            return nonsort + value;
        }
    }

    public String getValueWithoutNonsort() {
        return value;
    }
}