package com.aermini.managers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class CategoryData {
    private final String name;
    private final String displayName;
    private final String method;
    private final List<String> redisModes;
    private final Set<String> joinableStates;
    private final String redisChannel;
    private final String redisKeyPrefix;
    private final List<Pattern> serverFilter;
    private final List<Pattern> arenaExclude;
    private final Set<String> serverNames;
    private final Set<Pattern> joinablePatterns;
    private final Set<Pattern> spectatablePatterns;
    private final Map<String, Set<Pattern>> prefixJoinablePatterns;

    public CategoryData(String name, String displayName, String method,
                        List<String> redisModes, Set<String> joinableStates,
                        String redisChannel, String redisKeyPrefix,
                        List<Pattern> serverFilter, List<Pattern> arenaExclude,
                        Set<String> serverNames, Set<Pattern> joinablePatterns,
                        Set<Pattern> spectatablePatterns, Map<String, Set<Pattern>> prefixJoinablePatterns) {
        this.name = name;
        this.displayName = displayName;
        this.method = method;
        this.redisModes = redisModes;
        this.joinableStates = joinableStates;
        this.redisChannel = redisChannel;
        this.redisKeyPrefix = redisKeyPrefix;
        this.serverFilter = serverFilter;
        this.arenaExclude = arenaExclude;
        this.serverNames = serverNames;
        this.joinablePatterns = joinablePatterns;
        this.spectatablePatterns = spectatablePatterns;
        this.prefixJoinablePatterns = prefixJoinablePatterns;
    }

    public String getName() { return name; }
    public String getDisplayName() { return displayName; }
    public String getMethod() { return method; }
    public List<String> getRedisModes() { return redisModes; }
    public Set<String> getJoinableStates() { return joinableStates; }
    public String getRedisChannel() { return redisChannel; }
    public String getRedisKeyPrefix() { return redisKeyPrefix; }
    public List<Pattern> getServerFilter() { return serverFilter; }
    public List<Pattern> getArenaExclude() { return arenaExclude; }
    public Set<String> getServerNames() { return serverNames; }
    public Set<Pattern> getJoinablePatterns() { return joinablePatterns; }
    public Set<Pattern> getSpectatablePatterns() { return spectatablePatterns; }
    public Map<String, Set<Pattern>> getPrefixJoinablePatterns() { return prefixJoinablePatterns; }
}