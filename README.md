# spring-boot-ci-test-sharding-workshop

A Java 21/Spring Boot 3.4.5 experiment in job-level CI test sharding with JUnit 5 tags, Maven Surefire, and a GitHub Actions matrix.

The 60-second sleeps model slow integration work. The `payment` test fails intentionally; this repository's complete test workflow is therefore expected to fail.

## References

* [JUnit 5 tagging and filtering](https://docs.junit.org/5.11.4/user-guide/index.html#writing-tests-tagging-and-filtering)
* [Maven Surefire 3.5.3: filtering JUnit tests by tags](https://maven.apache.org/surefire-archives/surefire-3.5.3/maven-surefire-plugin/examples/junit-platform.html#filtering-by-tags)
* [GitHub Actions matrix jobs](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/run-job-variations)
* [GitHub-hosted runner isolation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job)
* [GitHub Actions artifacts](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts)
* [Spring TestContext caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
* [Testcontainers singleton lifecycle](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/#singleton-containers)

## Test sharding

Test sharding partitions a suite `T` into subsets executed by independent workers:

```text
T = S1 union S2 union ... union Sn
Si intersect Sj = empty, for i != j
```

For correct static sharding:

* every required test must belong to a matrix-selected shard;
* a test should belong to exactly one shard unless duplicate execution is intentional;
* shards should be balanced by measured duration, not test count;
* shards must not depend on execution order or shared mutable state.

JUnit tags do not enforce these invariants. An untagged test is omitted by this workflow; a test with two selected tags runs twice.

Approximate wall time:

```text
single job:   setup + sum(test durations)
N shards:     max(shard setup + shard test durations) + aggregation
compute cost: sum(all shard setup + shard test durations)
```

Sharding reduces critical-path latency by spending more runner capacity and repeating setup. It differs from JUnit parallel execution: matrix shards use isolated machines/JVMs; JUnit parallel execution uses threads inside one test JVM.

## Implementation

| Tag | Test class | Simulated work | Result |
|---|---|---:|---|
| `customer` | `CustomerGreetingTest` | 60 s | pass |
| `order` | `OrderGreetingTest` | 60 s | pass |
| `payment` | `PaymentGreetingTest` | 60 s | intentional failure |

Each class:

* uses class-level `@Tag` as the shard key;
* uses `@SpringBootTest` to load the full application context;
* uses `@AutoConfigureMockMvc` to invoke `GET /api/greetings/{name}` in-process;
* sleeps after the MVC call, so the delay is simulated test work, not endpoint latency.

`MockMvc` does not open an HTTP port. The Spring application context runs inside the Surefire test JVM.

## Maven commands

| Command | Effect | Expected result |
|---|---|---|
| `./mvnw --batch-mode test` | Compiles and runs all three tests sequentially in one reused Surefire JVM | at least 180 s plus setup; exit `1` |
| `./mvnw --batch-mode test -Dgroups=customer` | Includes only the `customer` tag | 60 s plus setup; exit `0` |
| `./mvnw --batch-mode test -Dgroups=order` | Includes only the `order` tag | 60 s plus setup; exit `0` |
| `./mvnw --batch-mode test -Dgroups=payment` | Includes only the `payment` tag | 60 s plus setup; exit `1` |
| `./mvnw --batch-mode test -Dgroups='customer \| order'` | Uses a JUnit tag expression to include two tags | 120 s plus setup; exit `0` |

`./mvnw` selects the repository-defined Maven 3.9.11 distribution. `--batch-mode` disables interactive/progress output. `test` runs the Maven lifecycle through test compilation and Surefire execution. `-Dgroups=...` sets Surefire's JUnit tag filter.

Surefire writes machine-readable XML and text output to `target/surefire-reports/`.

## Process and container isolation

The matrix expands one job definition into three independent jobs:

```text
workflow run
|-- customer job: fresh runner VM -> Maven JVM -> Surefire JVM -> Spring context
|-- order job:    fresh runner VM -> Maven JVM -> Surefire JVM -> Spring context
|-- payment job:  fresh runner VM -> Maven JVM -> Surefire JVM -> Spring context
+-- aggregate job: fresh runner VM -> downloaded XML -> GitHub check
```

Therefore:

* yes, the shards are separate processes, and on GitHub-hosted runners they are also separate VMs;
* the application is not a fourth standalone process inside each shard; its context lives in that shard's test JVM;
* Spring's context cache is static and JVM-local, so it cannot be shared across shards;
* this workflow loads the equivalent Spring context three times, once per shard.

An unsharded run can reuse one cached context across these three test classes because their context configuration is equivalent.

### PostgreSQL Testcontainer

If each shard reaches one context-scoped or static PostgreSQL Testcontainer definition, PostgreSQL is bootstrapped **three times**:

```text
customer VM -> PostgreSQL container A
order VM    -> PostgreSQL container B
payment VM  -> PostgreSQL container C
```

A static singleton is singleton per JVM, not per workflow. Testcontainers reuse cannot cross runner VMs or Docker daemons. Fresh GitHub-hosted runners may also pull the image independently. `setup-java`'s Maven cache does not cache Spring contexts, compiled classes, Docker containers, or database state.

Three starts are the expected minimum for one definition reached by every shard. Per-class definitions can increase the count. Context-scoped containers can also restart for different configurations or `@DirtiesContext`; a JVM-static singleton does not. An externally provisioned database avoids container startup but requires a separate database/schema per shard to prevent interference.

Shard only when saved test time exceeds duplicated context, container, queue, artifact, and reporting overhead.

## GitHub workflow

Workflow: [`.github/workflows/test-shards.yml`](.github/workflows/test-shards.yml)

| YAML/action/command | Semantics |
|---|---|
| `push`, `pull_request`, `workflow_dispatch` | Run for commits, pull requests, or manual dispatch |
| `permissions: contents: read` | Default every job to read-only repository access |
| `matrix.tag: [customer, order, payment]` | Materialize three `shards` jobs |
| `fail-fast: false` | Do not cancel sibling shards after one fails; it does not suppress failure |
| `runs-on: ubuntu-latest` | Allocate a fresh GitHub-hosted runner for each job |
| `actions/checkout@v4` | Check out the tested revision |
| `actions/setup-java@v4` | Install Temurin 21 and restore/save the Maven dependency cache |
| `./mvnw --batch-mode test -Dgroups=${{ matrix.tag }}` | Run only the tag assigned to the current matrix job |
| `if: always()` on upload | Attempt report upload even after the Maven step fails |
| `actions/upload-artifact@v4` | Store each shard's `target/surefire-reports/` under a unique artifact name |
| `if-no-files-found: error` | Fail the upload step if Surefire produced no report directory |
| `needs: shards` plus job-level `if: always()` | Wait for every shard, then aggregate even when a shard failed |
| `actions/download-artifact@v4` | Download `surefire-reports-*` and merge their files into `combined-reports/` |
| `dorny/test-reporter@v2` | Parse `TEST-*.xml` and publish one `Combined JUnit report` check |
| `checks: write` | Permit only the aggregate job to create that check |
| `fail-on-error: false` | Report failed tests without making the reporter step authoritative |
| `exit 1` when `needs.shards.result != 'success'` | Preserve the original shard failure after publishing the report |

Parallel start is subject to runner availability. `fail-fast: false` guarantees non-cancellation, not simultaneous execution.

## GitHub CLI

Requires an authenticated [GitHub CLI](https://cli.github.com/manual/).

| Command | Effect |
|---|---|
| `gh workflow view test-shards.yml --yaml` | Show the workflow registered on GitHub |
| `gh workflow run test-shards.yml --ref <branch>` | Create a manual `workflow_dispatch` run from a branch |
| `gh run list --workflow=test-shards.yml --limit 5` | List recent runs and IDs |
| `gh run watch <run-id> --exit-status` | Stream job progress and return the workflow's final status |
| `gh run view <run-id> --log-failed` | Print only failed-step logs |
| `gh run download <run-id> --pattern 'surefire-reports-*' --dir combined-reports` | Download all shard report artifacts |
| `gh run rerun <run-id> --failed` | Rerun failed jobs and their required dependencies |

## Real-world SOPs

### Reduce feedback latency

1. Measure the baseline with `time ./mvnw --batch-mode test`; this project takes at least 180 seconds plus setup.
2. Run `gh workflow run test-shards.yml --ref <branch>`.
3. Obtain the ID with `gh run list --workflow=test-shards.yml --limit 1`.
4. Watch with `gh run watch <run-id> --exit-status`.
5. Compare the matrix critical path: 60 seconds plus runner/setup/aggregation overhead.

Use this for large integration suites when individual tests are independent and runner capacity is available.

### Isolate a domain failure

1. Reproduce only the failing area with `./mvnw --batch-mode test -Dgroups=payment`.
2. Confirm unaffected areas with the `customer` and `order` commands.
3. Inspect `target/surefire-reports/TEST-com.example.sharding.PaymentGreetingTest.xml`.
4. In CI, read the combined check; `fail-fast: false` retains results from all domains.

Use domain tags when teams own bounded contexts and need targeted reproduction.

### Evaluate Testcontainers overhead

1. Start one tag command per terminal from three independent checkouts.
2. Run `jps -lv`; observe three Surefire JVMs and three Spring startup sequences.
3. In a project with a context-started PostgreSQL Testcontainer, run `docker ps` during execution; expect one PostgreSQL container per command.
4. Compare `max(shard duration)` with the unsharded run.
5. Reduce shard count when repeated container startup consumes the latency gain.

This repository does not include Testcontainers; the procedure describes the direct consequence of adding one to the current topology.

### Add a production shard

1. Add exactly one `@Tag("<shard>")` to each selected test class.
2. Add `<shard>` to `matrix.tag`.
3. Run `./mvnw --batch-mode test -Dgroups=<shard>`.
4. Verify the generated XML contains every intended test exactly once.
5. Rebalance from measured XML durations when one shard becomes the critical path.

The current workflow has no automatic completeness check. Untagged tests and tags absent from the matrix are silently skipped.

## Production constraints

* Do not share mutable databases, queues, ports, accounts, or filesystem paths across concurrent shards.
* Keep artifact report filenames unique before `merge-multiple: true`; equal paths can overwrite each other.
* Forked pull requests normally receive a read-only token, so the aggregate `checks: write` report may require GitHub's recommended two-workflow pattern.
* Pin third-party actions to reviewed full commit SHAs when immutability is required; major-version tags are movable.
* Remove the intentional `payment` failure before using this workflow as a required branch check.
