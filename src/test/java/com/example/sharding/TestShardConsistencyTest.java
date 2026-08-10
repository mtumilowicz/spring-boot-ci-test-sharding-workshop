package com.example.sharding;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

class TestShardConsistencyTest {

    private static final Path WORKFLOW = Path.of(".github/workflows/test-shards.yml");

    private static final Pattern WORKFLOW_TAG = Pattern.compile("filter: -Dgroups=([^\\s]+)");
    private static final Pattern REMAINDER_FILTER = Pattern.compile(
            "filter: \"-DexcludedGroups=([^\"]+)\"");

    @Test
    void workflowContainsAllTestTags() throws IOException, URISyntaxException {
        Set<String> testTags = discoverTestTags();
        String workflow = Files.readString(WORKFLOW);
        Set<String> workflowTags = findMatches(WORKFLOW_TAG, workflow);

        assertEquals(testTags, workflowTags, "Workflow tags must match test tags");

        var remainderMatcher = REMAINDER_FILTER.matcher(workflow);
        assertTrue(remainderMatcher.find(), "Remainder filter is missing");

        Set<String> excludedTags = Arrays.stream(remainderMatcher.group(1).split("\\s*\\|\\s*"))
                .collect(Collectors.toSet());
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

    private static Set<String> findMatches(Pattern pattern, String text) {
        return pattern.matcher(text).results()
                .map(result -> result.group(1))
                .collect(Collectors.toSet());
    }
}
