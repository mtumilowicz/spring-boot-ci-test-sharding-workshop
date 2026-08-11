# spring-boot-ci-test-sharding-workshop

## References

* [JUnit 5.11.4 tagging and filtering](https://docs.junit.org/5.11.4/user-guide/index.html#writing-tests-tagging-and-filtering)
* [JUnit 5.11.4 tag expressions](https://docs.junit.org/5.11.4/user-guide/index.html#running-tests-tag-expressions)
* [JUnit Platform Launcher API](https://docs.junit.org/5.11.4/api/org.junit.platform.launcher/org/junit/platform/launcher/Launcher.html)
* [Maven Surefire 3.5.3: filtering JUnit tests by tags](https://maven.apache.org/surefire-archives/surefire-3.5.3/maven-surefire-plugin/examples/junit-platform.html#filtering-by-tags)
* [GitHub Actions matrix jobs](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/run-job-variations)
* [GitHub-hosted runners](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job)
* [GitHub Actions artifacts](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts)
* [Spring TestContext caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
* [Testcontainers singleton lifecycle](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/#singleton-containers)

## Workshop purpose

* demonstrates job-level test sharding with Java 21, Spring Boot 3.4.5, JUnit 5 tags, Maven Surefire, and a GitHub Actions matrix
* contains four Spring Boot integration test classes
  * `CustomerGreetingTest` uses `@Tag("customer")`
  * `OrderGreetingTest` uses `@Tag("order")`
  * `PaymentGreetingTest` uses `@Tag("payment")`
  * `UntaggedGreetingTest` has no tag and runs in the remainder shard
* contains an untagged `TestShardConsistencyTest`, which compares JUnit-discovered tags with workflow shard names
* each greeting test sleeps for 60 seconds to represent slow integration work
* the payment test fails intentionally
  * a complete test run is therefore expected to fail
  * remove this failure before using the workflow as a required branch check

The tests call `GET /api/greetings/{name}` through `MockMvc`. The application and Spring context run inside the Surefire test JVM; no HTTP server port is opened.

## Test sharding

* definition
  * a test suite `T` is divided into subsets executed by independent workers

    ```text
    T = S1 union S2 union ... union Sn
    Si intersect Sj = empty, for i != j
    ```

* correctness requirements
  * every Surefire-discovered test must belong to at least one selected shard
  * every test should belong to exactly one shard unless duplicate execution is intentional
  * tests must not depend on execution order or shared mutable state
  * shard balance should use measured duration rather than test count
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

## JUnit tag strategy

### Current workflow

Each matrix shard except `remainder` is a JUnit tag:

```yaml
strategy:
  fail-fast: false
  matrix:
    shard:
      - customer
      - order
      - payment
      - remainder

steps:
  - name: Run ${{ matrix.shard }} tests
    if: matrix.shard != 'remainder'
    env:
      SHARD: ${{ matrix.shard }}
    run: ./mvnw --batch-mode test "-Dgroups=$SHARD"

  - name: Run remainder tests
    if: matrix.shard == 'remainder'
    run: ./mvnw --batch-mode test -Dgroups='none()'
```

The named shards pass their names to Surefire's `groups` property. The remainder uses JUnit's `none()` expression.

* untagged tests run in the remainder
* a tag absent from the matrix is not executed
* the untagged consistency test fails CI when such a tag exists

### Consistency contract

`TestShardConsistencyTest`:

* discovers tags from the compiled test classpath through the JUnit Platform
* deserializes `.github/workflows/test-shards.yml` with Jackson YAML
* removes the special `remainder` value from `matrix.shard`
* requires the discovered tag set to equal the remaining shard names

A new `@Tag("inventory")` therefore fails CI until `inventory` is added to `matrix.shard`. The contract does not prevent one test from carrying multiple configured tags.

### Exclusion-based remainder alternative

The remainder can instead exclude the union of configured tags:

```shell
./mvnw --batch-mode test -DexcludedGroups='customer | order | payment'
```

This alternative:

* includes untagged tests
* includes a test carrying only a tag absent from the matrix
* excludes tests already assigned to `customer`, `order`, or `payment`
* guarantees that every Surefire-discovered test runs in at least one shard

It requires repeating the configured tag union in the remainder command. The current `none()` design avoids that duplication and uses the consistency test to reject unknown tags.

## Maven commands

| Command | Selection | Expected result in this project |
|---|---|---|
| `./mvnw --batch-mode test` | All discovered tests | at least 240 s plus setup; exit `1` |
| `./mvnw --batch-mode test -Dgroups=customer` | `customer` shard | approximately 60 s plus setup; exit `0` |
| `./mvnw --batch-mode test -Dgroups=order` | `order` shard | approximately 60 s plus setup; exit `0` |
| `./mvnw --batch-mode test -Dgroups=payment` | `payment` shard | approximately 60 s plus setup; exit `1` |
| `./mvnw --batch-mode test -Dgroups='none()'` | Current remainder shard | approximately 60 s plus setup; exit `0` |
| `./mvnw --batch-mode test -DexcludedGroups='customer \| order \| payment'` | Exclusion-based remainder alternative | approximately 60 s plus setup; exit `0` |
| `./mvnw --batch-mode -Dtest=TestShardConsistencyTest test` | Workflow consistency contract | less than one second of test execution; exit `0` |

`./mvnw` uses the repository-defined Maven distribution. `--batch-mode` disables interactive output. Surefire writes XML and text reports to `target/surefire-reports/`.

## GitHub Actions workflow

Workflow: [`.github/workflows/test-shards.yml`](.github/workflows/test-shards.yml)

* shard execution
  * `matrix.shard` creates the `customer`, `order`, `payment`, and `remainder` jobs
  * each named shard is passed directly to Surefire as a JUnit tag
  * `remainder` selects untagged tests with `none()`
  * `fail-fast: false` prevents one failed shard from cancelling its siblings
    * it does not suppress the failure
  * each job receives a fresh GitHub-hosted runner
  * `actions/setup-java` installs Temurin 21 and caches Maven dependencies
* report handling
  * every shard uploads `target/surefire-reports/` with `if: always()`
  * the aggregate job waits for all shard jobs, including failed jobs
  * it downloads and merges the report artifacts
  * `dorny/test-reporter` publishes one combined JUnit check
  * the final step fails when any shard failed, preserving the original workflow result

Parallel start depends on runner availability. `fail-fast: false` guarantees non-cancellation, not simultaneous execution.

The current process topology is:

```text
workflow run
|-- customer runner -> Maven -> Surefire JVM -> Spring context
|-- order runner    -> Maven -> Surefire JVM -> Spring context
|-- payment runner  -> Maven -> Surefire JVM -> Spring context
|-- remainder runner -> Maven -> Surefire JVM -> Spring context and consistency contract
+-- aggregate runner -> downloaded XML reports -> GitHub check
```

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
    remainder runner -> PostgreSQL container D
    ```

  * per-class definitions, different context configurations, or `@DirtiesContext` can increase the number of starts
  * Maven dependency caching does not cache Spring contexts, Docker containers, images, or database state
  * an external database avoids container startup but requires isolated databases or schemas for concurrent shards

This repository does not include Testcontainers. These consequences apply if container-backed integration tests are added.

## Operating the workflow

Requires an authenticated [GitHub CLI](https://cli.github.com/manual/).

| Command | Purpose |
|---|---|
| `gh workflow run test-shards.yml --ref <branch>` | Start a manual workflow run |
| `gh run list --workflow=test-shards.yml --limit 5` | Find recent run IDs |
| `gh run watch <run-id> --exit-status` | Watch a run and return its final status |
| `gh run view <run-id> --log-failed` | Print failed-step logs |
| `gh run download <run-id> --pattern 'surefire-reports-*' --dir combined-reports` | Download all shard reports |
| `gh run rerun <run-id> --failed` | Rerun failed jobs and required dependencies |

## Production guidance

* choose a remainder policy explicitly
  * the current workflow uses `none()` and fails consistency when a tag is absent from the matrix
  * use `excludedGroups` when unknown tagged tests must execute in the remainder
* verify exact-once execution separately because Surefire does not reject a test carrying multiple configured tags
  * compare discovered tests with aggregated report entries
  * keep report paths unique because `merge-multiple: true` can overwrite equal filenames
* rebalance shards from Surefire XML durations when one shard becomes the critical path
* isolate mutable databases, queues, ports, accounts, and filesystem paths between shards
* reduce the shard count when repeated context or container startup consumes the latency gain
* review permissions for pull requests from forks before relying on `checks: write`
* pin third-party actions to reviewed commit SHAs when immutable dependencies are required
* remove the intentional payment failure before making the workflow a required check
