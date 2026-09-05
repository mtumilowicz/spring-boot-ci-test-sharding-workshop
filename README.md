# spring-boot-ci-test-sharding-workshop

## References

* [JUnit 5.11.4 tagging and filtering](https://docs.junit.org/5.11.4/user-guide/index.html#writing-tests-tagging-and-filtering)
* [JUnit 5.11.4 tag expressions](https://docs.junit.org/5.11.4/user-guide/index.html#running-tests-tag-expressions)
* [JUnit 5.11.4 parallel execution](https://docs.junit.org/5.11.4/user-guide/index.html#writing-tests-parallel-execution)
* [JUnit Platform Launcher API](https://docs.junit.org/5.11.4/api/org.junit.platform.launcher/org/junit/platform/launcher/Launcher.html)
* [Maven Surefire 3.5.3: filtering JUnit tests by tags](https://maven.apache.org/surefire-archives/surefire-3.5.3/maven-surefire-plugin/examples/junit-platform.html#filtering-by-tags)
* [GitHub Actions workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
* [Building and testing Java with Maven](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)
* [GitHub Actions matrix jobs](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/run-job-variations)
* [GitHub-hosted runners](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job)
* [GitHub Actions artifacts](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts)
* [Spring TestContext parallel execution](https://docs.spring.io/spring-framework/reference/6.2/testing/testcontext-framework/parallel-test-execution.html)
* [Spring TestContext caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
* [Testcontainers singleton lifecycle](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/#singleton-containers)
* [Quarkus test class-loading changes](https://quarkus.io/blog/test-classloading-rewrite/)

## Workshop purpose

* demonstrates job-level test sharding with Java 21, Spring Boot 3.4.5, JUnit 5 tags, Maven Surefire, and a GitHub Actions matrix
* contains four Spring Boot integration test classes
  * `CustomerGreetingTest` uses `@CustomerShard`
  * `OrderGreetingTest` uses `@OrderShard`
  * `PaymentGreetingTest` uses `@PaymentShard`
  * `UntaggedGreetingTest` has no tag and runs in the unsharded test job
* each dedicated shard annotation combines `@Tag("sharded")` with a dedicated shard tag
* tests without `@Tag("sharded")` run in the unsharded job
* contains an untagged `TestShardConsistencyTest`
  * compares dedicated shard tags with workflow shard names
  * fails CI when a test marked `sharded` has no matching dedicated workflow shard
  * fails CI when a workflow shard has no matching test tag
* each greeting test sleeps for 60 seconds to represent slow integration work
* the payment test fails intentionally
  * a complete test run is therefore expected to fail
  * the failure demonstrates that one shard can fail without cancelling report collection from the other shards
  * the aggregate job still reports the workflow as failed

## Test sharding

* meaning
  * split one test suite into smaller groups called shards
  * execute each shard in an independent GitHub Actions job, runner, and JVM
  * reduce wall-clock time by running the jobs at the same time
* correctness
  * every Surefire-discovered test must run in exactly one job unless duplicate execution is intentional
  * tests must not depend on execution order or shared mutable state
* balancing
  * use recorded test duration, not the number of test methods
    * example: one test takes 60 seconds, while ten tests take 1 second each
    * assigning the same number of tests to each shard does not balance this workload
  * the slowest shard determines when the test stage finishes
* latency and cost

  ```text
  unsharded wall time: setup + sum(test durations)
  sharded wall time:   max(shard setup + shard test durations) + aggregation
  total compute:       sum(all shard setup + shard test durations)
  ```

  * this project takes at least 240 seconds plus setup when run without sharding
  * with four available runners, the test portion of the critical path is approximately 60 seconds
  * sharding repeats runner, JVM, application-context, and infrastructure setup
  * sharding is useful only when the saved test time exceeds this repeated overhead
* difference from JUnit parallel execution
  * matrix sharding uses separate jobs, runner machines, and JVMs
  * JUnit parallel execution uses concurrent threads within one Surefire test JVM
  * JUnit parallel execution can reuse one cached Spring `ApplicationContext`

## JUnit tag strategy

* sharded test

    ```yaml
    - name: Run ${{ matrix.shard }} tests
      if: matrix.shard != 'unsharded'
      env:
        SHARD: ${{ matrix.shard }}
      run: ./mvnw --batch-mode test "-Dgroups=sharded & $SHARD"
    ```

   * definition
 
     ```java
     @Target({ElementType.TYPE, ElementType.METHOD})
     @Retention(RetentionPolicy.RUNTIME)
     @Tag("sharded")
     @Tag("customer")
     public @interface CustomerShard {
     }
     ```
  * purpose
    * apply the `sharded` marker and one dedicated shard name together
    * remove the need to apply two separate annotations
  * usage

    ```java
    @CustomerShard
    class CustomerGreetingTest {
    }
    ```

  * `@OrderShard` and `@PaymentShard` follow the same pattern
  * `sharded` identifies tests assigned to dedicated jobs
  * `customer`, `order`, or `payment` selects the dedicated job
* unsharded tests
  * the unsharded job excludes `sharded`

    ```yaml
    - name: Run unsharded tests
      if: matrix.shard == 'unsharded'
      run: ./mvnw --batch-mode test -DexcludedGroups=sharded
    ```

  * it therefore runs tests with no tags and tests carrying only unrelated tags such as `slow`
  * it also runs `TestShardConsistencyTest`
* consistency contract
  * adding a dedicated shard requires a composed annotation and a matching workflow matrix value
    * example: `@CustomerShard` defines `@Tag("customer")`, and the matrix contains `customer`
  * `TestShardConsistencyTest` uses JUnit Platform discovery to inspect compiled tests
    * inspection reads test identifiers and tags without executing test methods

    ```java
    var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClasspathRoots(Set.of(testClassesRoot)))
            .build();
    var testPlan = LauncherFactory.create().discover(request);
    ```

  * each test marked `sharded` must have exactly one dedicated shard tag
    * the tag must match `customer`, `order`, or `payment` from the workflow matrix
  * the test excludes `unsharded` from the comparison because it is the catch-all job
  * the consistency test fails when
    * a test has `sharded` but no dedicated shard tag
    * a dedicated shard tag is absent from the workflow matrix
    * a test has more than one dedicated shard tag
    * a dedicated workflow shard has no matching test

## CI Maven commands

* command prefix
  * `./mvnw`
    * uses the Maven version defined by the project wrapper
    * keeps developer and CI builds on the same Maven version
  * `--batch-mode`
    * runs Maven without requesting user input
    * prevents a CI job from waiting for input that nobody can provide
  * `--no-transfer-progress`
    * hides repeated progress updates while Maven transfers dependencies
    * keeps CI logs concise while preserving errors
  * `--show-version`
    * writes the Maven and Java versions to the log
    * helps diagnose differences between developer and CI environments
* test-selection properties
  * example: `./mvnw --batch-mode test "-Dgroups=sharded & customer"`
  * `groups`
    * selects tests whose JUnit tags match an expression
    * example: `sharded & customer` selects tests carrying both tags
  * `excludedGroups`
    * excludes tests whose JUnit tags match an expression
    * example: excluding `sharded` leaves tests for the unsharded catch-all job
  * `failIfNoTests`
    * fails the Maven command when the test selection is empty
    * example: an unknown shard value must fail instead of producing a successful empty job
* complete test suite without sharding

  ```shell
  ./mvnw --batch-mode --no-transfer-progress --show-version test
  ```

  * runs the complete test suite in one Maven process
  * provides the approximately 240-second baseline used to measure the benefit of sharding
  * expected to fail in this workshop because `PaymentGreetingTest` fails intentionally
* dedicated shard job

  ```shell
  ./mvnw --batch-mode --no-transfer-progress --show-version test \
    -DfailIfNoTests=true \
    "-Dgroups=sharded & $SHARD"
  ```

  * the workflow sets `SHARD` to the current dedicated matrix value
  * the tag expression selects tests assigned to that shard
  * `failIfNoTests` rejects an empty shard
* unsharded catch-all job

  ```shell
  ./mvnw --batch-mode --no-transfer-progress --show-version test \
    -DfailIfNoTests=true \
    -DexcludedGroups=sharded
  ```

  * runs tests that are not assigned to a dedicated shard
  * `failIfNoTests` rejects an empty catch-all job
* Maven failure diagnosis

  ```shell
  ./mvnw --batch-mode --no-transfer-progress --show-version --errors test \
    -DfailIfNoTests=true \
    "-Dgroups=sharded & $SHARD"
  ```

  * `--errors` writes Maven exception stack traces to the CI log
  * use it for dependency-resolution, plugin, or Maven execution failures
  * ordinary test assertion failures are already available in the Surefire reports

## GitHub Actions workflow

Workflow: [`.github/workflows/test-shards.yml`](.github/workflows/test-shards.yml)

* workflow file
  * GitHub loads YAML workflow files from `.github/workflows/`
  * `name` identifies the workflow in the GitHub UI
  * `on` selects events such as `push`, `pull_request`, and manual `workflow_dispatch`
  * `permissions` restricts the default `GITHUB_TOKEN`
  * grant only the access required by the jobs
* jobs and steps
  * `jobs` contains independent units of work
  * `runs-on` selects the runner machine for a job
  * jobs without dependencies can run concurrently when runners are available
    * parallel start depends on runner availability
  * `steps` run sequentially inside one job and share its checked-out workspace
  * `uses` invokes a reusable action
    * `actions/checkout` copies the repository onto the runner
    * `actions/setup-java` selects the JDK and can cache Maven dependencies
  * `run` executes a shell command such as `./mvnw --batch-mode test`
* matrices and expressions
  * `strategy.matrix` expands one job definition into one job per value or value combination
  * `${{ matrix.shard }}` reads the current matrix value
  * `env` passes a value to a shell command without duplicating the command
  * `if` conditionally executes a job or step
  * `fail-fast: false` prevents one failed matrix job from cancelling its siblings; it does not hide the failure
* dependencies and reports
  * `needs` delays a job until its prerequisite jobs finish
  * a dependent job is normally skipped when a prerequisite fails
  * `if: always()` makes the report steps run after success, failure, or cancellation
  * upload and download artifact actions transfer Surefire reports between isolated runners
* current report handling
  * every shard attempts to upload `target/surefire-reports/` with `if: always()`
  * the upload step fails when the directory contains no reports
  * the aggregate job uses `needs: shards` and `if: always()` to wait for all shard jobs, including failed jobs
  * it downloads and merges the report artifacts
  * `dorny/test-reporter` publishes one combined JUnit check
  * the final step fails when any shard failed, preserving the original workflow result

## Spring and Testcontainers implications

* Spring context caching
  * the Spring TestContext cache is static and JVM-local
  * equivalent test classes can reuse one cached context in an unsharded Surefire JVM
  * separate matrix jobs cannot share that cache
  * each current shard therefore loads its own Spring `ApplicationContext`
* Testcontainers
  * a static singleton container is singleton per JVM, not per GitHub workflow
  * if every greeting test used a PostgreSQL Testcontainer, the workflow would start at least four containers

    ```text
    customer runner -> PostgreSQL container A
    order runner    -> PostgreSQL container B
    payment runner  -> PostgreSQL container C
    unsharded runner -> PostgreSQL container D
    ```

  * an external database avoids container startup but requires isolated databases or schemas for concurrent shards
* nested JUnit discovery
  * `TestShardConsistencyTest` calls `Launcher.discover()` to inspect every compiled test
  * discovery invokes registered JUnit test engines and may load test classes
  * discovery is not equivalent to reading class files without running test-framework code
  * with ordinary Spring Boot tests, discovery does not execute `@SpringBootTest`
  * discovery does not create the application context or run lifecycle callbacks
  * discovery does not start containers managed by the Testcontainers JUnit extension
  * discovery can still trigger infrastructure when class loading or a custom extension has side effects

    ```java
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17").start();
    ```

  * avoid starting containers or other infrastructure in static initializers
  * this design is unsuitable for Quarkus 3.22 and later when discovery includes `@QuarkusTest` classes that use Dev Services
    * Quarkus performs augmentation during JUnit discovery
    * augmentation analyzes the application and its extensions
    * augmentation creates the metadata and generated code required to run the application
    * Dev Services start during this phase
  * in such a project, `TestShardConsistencyTest` may start Dev Services and containers
  * the consistency check can therefore become slow or require Docker and external resources
  * the consistency check may fail in a restricted environment

## Production guidance

* keep an unsharded catch-all job
  * the current workflow excludes `sharded`, so every test without the marker enters the unsharded job
  * the consistency test rejects missing and ambiguous dedicated shard tags
* keep report paths unique because `merge-multiple: true` can overwrite equal filenames
* rebalance shards from Surefire XML durations when one shard becomes the critical path
* reduce the shard count when repeated context or container startup consumes the latency gain
