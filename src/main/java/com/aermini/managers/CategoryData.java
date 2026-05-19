package com.aermini.managers;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class CategoryData {
    private final String name;
    private final String displayName;
    private final Set<String> serverNames;
    private final Set<Pattern> joinablePatterns;
    private final Set<Pattern> spectatablePatterns;
    private final Map<String, Set<Pattern>> prefixJoinablePatterns;

    public CategoryData(String name, String displayName, Set<String> serverNames,
                       Set<Pattern> joinablePatterns, Set<Pattern> spectatablePatterns,
                       Map<String, Set<Pattern>> prefixJoinablePatterns) {
        this.name = name;
        this.displayName = displayName;
        this.serverNames = serverNames;
        this.joinablePatterns = joinablePatterns;
        this.spectatablePatterns = spectatablePatterns;
        this.prefixJoinablePatterns = prefixJoinablePatterns;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<String> getServerNames() {
        return serverNames;
    }

    public Set<Pattern> getJoinablePatterns() {
        return joinablePatterns;
    }

    public Set<Pattern> getSpectatablePatterns() {
        return spectatablePatterns;
    }

    public Map<String, Set<Pattern>> getPrefixJoinablePatterns() {
        return prefixJoinablePatterns;
    }
}