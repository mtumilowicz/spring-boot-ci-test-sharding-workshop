package com.example.sharding;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

class TestShardConsistencyTest {

    private static final Path WORKFLOW = Path.of(".github/workflows/test-shards.yml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void workflowContainsAllTestTags() throws IOException, URISyntaxException {
        Set<String> testTags = discoverTestTags();
        List<Shard> shards = YAML.readValue(WORKFLOW.toFile(), Workflow.class)
                .jobs()
                .get("shards")
                .strategy()
                .matrix()
                .include();
        Set<String> workflowTags = shards.stream()
                .map(Shard::includedTag)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        assertEquals(testTags, workflowTags, "Workflow tags must match test tags");

        Set<String> excludedTags = shards.stream()
                .filter(shard -> "remainder".equals(shard.shard()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Remainder shard is missing"))
                .excludedTags();
        assertEquals(testTags, excludedTags, "Remainder must exclude every test tag");
    }

    private static Set<String> discoverTestTags() throws URISyntaxException {
        Path testClassesRoot = Path.of(TestShardConsistencyTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClasspathRoots(Set.of(testClassesRoot)))
                .build();
        var testPlan = LauncherFactory.create().discover(request);

        return testPlan.getRoots().stream()
                .flatMap(root -> testPlan.getDescendants(root).stream())
                .flatMap(test -> test.getTags().stream())
                .map(TestTag::getName)
                .collect(Collectors.toSet());
    }

    private record Workflow(Map<String, Job> jobs) {
    }

    private record Job(Strategy strategy) {
    }

    private record Strategy(Matrix matrix) {
    }

    private record Matrix(List<Shard> include) {
    }

    private record Shard(String shard, String includedTag, Set<String> excludedTags) {
    }
}
