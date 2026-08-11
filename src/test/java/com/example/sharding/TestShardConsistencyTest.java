package com.example.sharding;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

class TestShardConsistencyTest {

    private static final Path WORKFLOW = Path.of(".github/workflows/test-shards.yml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void testTagsMatchWorkflowShards() throws Exception {
        Workflow workflow = YAML.readValue(WORKFLOW.toFile(), Workflow.class);
        Set<String> workflowShardTags = new HashSet<>(workflow.jobs()
                .get("shards")
                .strategy()
                .matrix()
                .shard());

        assertTrue(workflowShardTags.contains("remainder"), "Remainder shard is missing");
        workflowShardTags.remove("remainder");

        assertEquals(findJUnitTags(), workflowShardTags,
                "JUnit tags must match configured shards");
    }

    private static Set<String> findJUnitTags() throws Exception {
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

    private record Matrix(Set<String> shard) {
    }
}
