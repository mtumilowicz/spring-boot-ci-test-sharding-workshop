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
* each shard annotation combines `@Tag("sharded")` with its dedicated shard tag
* contains an untagged `TestShardConsistencyTest`, which compares dedicated shard tags with workflow shard names
  * purpose: fail CI when a dedicated test has no matching workflow job or a workflow shard has no matching test tag
* each greeting test sleeps for 60 seconds to represent slow integration work
* the payment test fails intentionally
  * a complete test run is therefore expected to fail to demonstrate that one shard can fail without cancelling report collection from the other shards
  * the aggregate job still reports the workflow as failed
  * remove this failure before using the workflow as a required branch check

## Test sharding

* meaning
  * split one test suite into smaller groups called shards
  * execute each shard in an independent GitHub Actions job, runner, and JVM
  * reduce wall-clock time by running the jobs at the same time
* correctness
  * every Surefire-discovered test must run in one job
  * a test should not run in multiple jobs unless duplicate execution is intentional
  * tests must not depend on execution order or shared mutable state
* balancing
  * use recorded test duration, not the number of test methods
    * example: one 60-second test and ten 1-second tests are not balanced by assigning the same number of tests to each shard
  * the slowest shard determines when the test stage finishes
* latency and cost

  ```text
  unsharded wall time: setup + sum(test durations)
  sharded wall time:   max(shard setup + shard test durations) + aggregation
  total compute:       sum(all shard setup + shard test durations)
  ```

  * this project takes at least 240 seconds plus setup when run without sharding
  * four available runners reduce the test portion of the critical path to approximately 60 seconds
  * sharding repeats runner, JVM, application-context, and infrastructure setup
  * sharding is useful only when the saved test time exceeds this repeated overhead
* difference from JUnit parallel execution
  * matrix sharding uses separate jobs, runner machines, and JVMs
  * JUnit parallel execution uses concurrent threads within one Surefire test JVM
  * JUnit parallel execution can reuse one cached Spring `ApplicationContext`
  * to run top-level `@SpringBootTest` classes concurrently while keeping methods within each class sequential, add `src/test/resources/junit-platform.properties`

    ```properties
    junit.jupiter.execution.parallel.enabled=true
    junit.jupiter.execution.parallel.mode.default=same_thread
    junit.jupiter.execution.parallel.mode.classes.default=concurrent
    ```

  * do not enable parallel execution for tests that use `@DirtiesContext`, replace Spring beans with mocks, depend on execution order, or mutate shared databases, files, ports, queues, or other external state

## JUnit tag strategy

* composed shard annotation
  * purpose
    * apply the `sharded` marker and one dedicated shard name together
    * prevent a test author from remembering two separate annotations
  * definition

    ```java
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("sharded")
    @Tag("customer")
    public @interface CustomerShard {
    }
    ```

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
  * it therefore runs tests with no tags and tests carrying only unrelated tags such as `slow`
  * it also runs `TestShardConsistencyTest`
* consistency contract
  * JUnit Platform introspection discovers every compiled test carrying `sharded`
  * each such test must have exactly one other tag containing its shard name
  * Jackson YAML reads the shard names from `.github/workflows/test-shards.yml`
  * the test removes the special `unsharded` matrix value and compares the remaining workflow values with the discovered shard tags
  * a raw `@Tag("sharded")`, an unknown shard tag, multiple shard tags, or an unused workflow shard fails the consistency test
  * adding a shard requires both a composed annotation and a matching matrix value

## Maven commands

* `./mvnw --batch-mode verify`
  * standard CI command for compiling, testing, packaging, and running verification checks
  * useful for an ordinary non-sharded Maven job
* `./mvnw --batch-mode test`
  * runs every Surefire-discovered test
  * expected to fail in this workshop because `PaymentGreetingTest` fails intentionally
* `./mvnw --batch-mode test "-Dgroups=sharded & customer"`
  * runs the `customer` dedicated shard
  * replace `customer` with the matrix shard value
* `./mvnw --batch-mode test -DexcludedGroups=sharded`
  * runs tests that are not assigned to a dedicated shard
* `./mvnw --batch-mode -Dtest=TestShardConsistencyTest test`
  * runs only the workflow-to-tag consistency check
* command details
  * `./mvnw` uses the Maven version defined by the project wrapper
  * `--batch-mode` disables interactive output and is appropriate for CI logs
  * `groups` is Surefire's JUnit tag inclusion expression
  * `excludedGroups` excludes tests matching a JUnit tag expression
  * shard jobs use `test` because packaging the same application in every shard would duplicate work

## GitHub Actions workflow

Workflow: [`.github/workflows/test-shards.yml`](.github/workflows/test-shards.yml)

* workflow file
  * GitHub loads YAML workflow files from `.github/workflows/`
  * `name` identifies the workflow in the GitHub UI
  * `on` selects events such as `push`, `pull_request`, and manual `workflow_dispatch`
  * `permissions` restricts the default `GITHUB_TOKEN`; grant only the access required by the jobs
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
  * `fail-fast: false` guarantees non-cancellation
* matrices and expressions
  * `strategy.matrix` expands one job definition into one job per value or value combination
  * `${{ matrix.shard }}` reads the current matrix value
  * `env` passes a value to a shell command without duplicating the command
  * `if` conditionally executes a job or step
  * `fail-fast: false` prevents one failed matrix job from cancelling its siblings; it does not hide the failure
* dependencies and reports
  * `needs` delays a job until its prerequisite jobs finish
  * a dependent job is normally skipped when a prerequisite fails
  * `if: always()` allows report collection to continue after success, failure, or cancellation
  * upload and download artifact actions transfer Surefire reports between isolated runners
* current report handling
  * every shard uploads `target/surefire-reports/` with `if: always()`
  * the aggregate job uses `needs: shards` and `if: always()` to wait for all shard jobs, including failed jobs
  * it downloads and merges the report artifacts
  * `dorny/test-reporter` publishes one combined JUnit check
  * the final step fails when any shard failed, preserving the original workflow result

## Spring and Testcontainers implications

* Spring context caching
  * the Spring TestContext cache is static and JVM-local
  * equivalent test classes can reuse one cached context in an unsharded Surefire JVM
  * separate matrix jobs cannot share that cache
  * the current workflow therefore loads an equivalent Spring context once per shard
* Testcontainers
  * a static singleton container is singleton per JVM, not per GitHub workflow
  * a PostgreSQL Testcontainer reached by every current greeting-test shard starts at least four times

    ```text
    customer runner -> PostgreSQL container A
    order runner    -> PostgreSQL container B
    payment runner  -> PostgreSQL container C
    unsharded runner -> PostgreSQL container D
    ```

  * an external database avoids container startup but requires isolated databases or schemas for concurrent shards
* nested JUnit discovery
  * `TestShardConsistencyTest` calls `Launcher.discover()` to inspect every compiled test
  * discovery invokes registered JUnit test engines and may load test classes; it is not equivalent to reading class files without running test-framework code
  * with ordinary Spring Boot tests, discovery does not execute `@SpringBootTest`, create the application context, run lifecycle callbacks, or start containers managed by the Testcontainers JUnit extension
  * discovery can still trigger infrastructure when class loading or a custom extension has side effects

    ```java
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17").start();
    ```

  * avoid starting containers or other infrastructure in static initializers
  * this design is unsuitable for Quarkus 3.22 and later because Quarkus performs augmentation during JUnit discovery and starts Dev Services in that phase
    * consequence: running `TestShardConsistencyTest` in such a Quarkus project may start Dev Services and containers, making a consistency check slow, dependent on Docker and external resources, or unable to run in restricted environments


## Production guidance

* keep an unsharded catch-all job
  * the current workflow excludes `sharded`, so every test without the marker enters the unsharded job
  * the consistency test rejects missing and ambiguous dedicated shard tags
* keep report paths unique because `merge-multiple: true` can overwrite equal filenames
* rebalance shards from Surefire XML durations when one shard becomes the critical path
* reduce the shard count when repeated context or container startup consumes the latency gain
