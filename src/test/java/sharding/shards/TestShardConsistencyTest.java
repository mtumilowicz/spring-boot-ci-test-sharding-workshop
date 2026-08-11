package sharding.shards;

import java.io.IOException;
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
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

class TestShardConsistencyTest {

    private static final String SHARDED_TAG = "sharded";
    private static final Path WORKFLOW = Path.of(".github/workflows/test-shards.yml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void testTagsMatchWorkflowShards() throws Exception {
        Set<String> workflowShardTags = findWorkflowShardTags();

        assertTrue(workflowShardTags.contains("unsharded"), "Unsharded test job is missing");
        workflowShardTags.remove("unsharded");

        Set<String> junitShardTags = findTestsTaggedAsSharded().stream()
                .map(TestShardConsistencyTest::findShardNameTag)
                .collect(Collectors.toSet());

        assertEquals(workflowShardTags, junitShardTags,
                "Dedicated shard tags must match configured shards");
    }

    private static Set<String> findWorkflowShardTags() throws IOException {
        Workflow workflow = YAML.readValue(WORKFLOW.toFile(), Workflow.class);

        return new HashSet<>(workflow.jobs()
                .get("shards")
                .strategy()
                .matrix()
                .shard());
    }

    private static Set<TestIdentifier> findTestsTaggedAsSharded() throws Exception {
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
                .filter(TestIdentifier::isTest)
                .filter(test -> test.getTags().stream()
                        .map(TestTag::getName)
                        .anyMatch(SHARDED_TAG::equals))
                .collect(Collectors.toSet());
    }

    private static String findShardNameTag(TestIdentifier test) {
        Set<String> shardTags = test.getTags().stream()
                .map(TestTag::getName)
                .filter(tag -> !SHARDED_TAG.equals(tag))
                .collect(Collectors.toSet());

        assertEquals(1, shardTags.size(),
                () -> "Sharded test must have exactly one dedicated shard tag: "
                        + test.getDisplayName());

        return shardTags.iterator().next();
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
