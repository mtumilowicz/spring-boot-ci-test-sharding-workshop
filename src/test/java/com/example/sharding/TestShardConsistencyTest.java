package com.example.sharding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

class TestShardConsistencyTest {

    private static final Path WORKFLOW = Path.of(".github/workflows/test-shards.yml");
    private static final String REMAINDER = "remainder";
    private static final String INCLUDED_GROUPS_PREFIX = "-Dgroups=";
    private static final String EXCLUDED_GROUPS_PREFIX = "-DexcludedGroups=";

    @Test
    void workflowShardsMatchDiscoveredTestTags() throws IOException {
        List<Shard> shards = loadShards();
        Set<String> shardNames = shards.stream()
                .map(Shard::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(shards.size(), shardNames.size(), "Shard names must be unique");

        List<Shard> remainderShards = shards.stream()
                .filter(shard -> REMAINDER.equals(shard.name()))
                .toList();
        assertEquals(1, remainderShards.size(), "Exactly one remainder shard is required");

        List<Shard> taggedShards = shards.stream()
                .filter(shard -> !REMAINDER.equals(shard.name()))
                .toList();
        assertFalse(taggedShards.isEmpty(), "At least one tagged shard is required");

        Set<String> configuredTags = taggedShards.stream()
                .map(Shard::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        taggedShards.forEach(shard -> assertEquals(
                INCLUDED_GROUPS_PREFIX + shard.name(),
                shard.filter(),
                () -> "Invalid filter for shard " + shard.name()));

        String expectedRemainderFilter = EXCLUDED_GROUPS_PREFIX + String.join(" | ", configuredTags);
        assertEquals(expectedRemainderFilter, remainderShards.getFirst().filter(),
                "The remainder must exclude every configured shard tag");

        Set<TestIdentifier> discoveredTests = discoverTests();
        assertFalse(discoveredTests.isEmpty(), "JUnit did not discover any tests");

        Set<String> discoveredTags = new LinkedHashSet<>();
        for (TestIdentifier test : discoveredTests) {
            Set<String> tags = test.getTags().stream()
                    .map(TestTag::getName)
                    .collect(Collectors.toSet());
            discoveredTags.addAll(tags);

            Set<String> shardTags = tags.stream()
                    .filter(configuredTags::contains)
                    .collect(Collectors.toSet());
            assertTrue(shardTags.size() <= 1,
                    () -> test.getUniqueId() + " belongs to multiple shards: " + shardTags);
        }

        Set<String> unknownTags = new LinkedHashSet<>(discoveredTags);
        unknownTags.removeAll(configuredTags);
        assertTrue(unknownTags.isEmpty(), () -> "Tags missing from the workflow: " + unknownTags);

        Set<String> unusedTags = new LinkedHashSet<>(configuredTags);
        unusedTags.removeAll(discoveredTags);
        assertTrue(unusedTags.isEmpty(), () -> "Workflow shards without tests: " + unusedTags);
    }

    private static Set<TestIdentifier> discoverTests() {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectPackage("com.example.sharding"))
                .build();
        TestPlan testPlan = LauncherFactory.create().discover(request);

        return testPlan.getRoots().stream()
                .flatMap(root -> testPlan.getDescendants(root).stream())
                .filter(TestIdentifier::isTest)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<Shard> loadShards() throws IOException {
        Object document;
        try (var input = Files.newInputStream(WORKFLOW)) {
            document = new Yaml().load(input);
        }

        Map<?, ?> root = requireMap(document, "workflow");
        Map<?, ?> jobs = requireMap(root.get("jobs"), "jobs");
        Map<?, ?> shardJob = requireMap(jobs.get("shards"), "jobs.shards");
        Map<?, ?> strategy = requireMap(shardJob.get("strategy"), "jobs.shards.strategy");
        Map<?, ?> matrix = requireMap(strategy.get("matrix"), "jobs.shards.strategy.matrix");
        Object includeValue = matrix.get("include");
        assertInstanceOf(List.class, includeValue, "matrix.include must be a list");

        return ((List<?>) includeValue).stream()
                .map(value -> requireMap(value, "matrix.include entry"))
                .map(value -> new Shard(
                        requireString(value.get("shard"), "matrix shard"),
                        requireString(value.get("filter"), "matrix filter")))
                .toList();
    }

    private static Map<?, ?> requireMap(Object value, String name) {
        assertInstanceOf(Map.class, value, name + " must be a map");
        return (Map<?, ?>) value;
    }

    private static String requireString(Object value, String name) {
        assertInstanceOf(String.class, value, name + " must be a string");
        return (String) value;
    }

    private record Shard(String name, String filter) {
    }
}
