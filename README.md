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
    * in particular: every Surefire-discovered test must run in exactly one job unless duplicate execution is intentional
  * execute each shard in an independent GitHub Actions job, runner, and JVM
  * reduce wall-clock time by running the jobs at the same time
    * the slowest shard determines when the test stage finishes
    * balance shards by total test duration, not test count
      * example
        * shard A: one 60-second test = 60 seconds
        * shard B: ten 1-second tests = 10 seconds
        * shard A finishes last despite containing fewer tests
    * sharding is useful only when the saved test time exceeds this repeated overhead
        * sharding repeats runner, JVM, application-context, and infrastructure setup
        * overview 
            ```text
            unsharded wall time: setup + sum(test durations)
            sharded wall time:   max(shard setup + shard test durations) + aggregation
            total compute:       sum(all shard setup + shard test durations)
            ```
        * example
            1. project takes at least 240 seconds plus setup when run without sharding
            1. with four available runners, the test portion of the critical path is approximately 60 seconds
* vs JUnit parallel execution
  * sharding uses separate jobs, runner machines, and JVMs
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
* shard contract
  * adding a dedicated shard requires a composed annotation and a matching workflow matrix value
    * example: `@CustomerShard` defines `@Tag("customer")`, and the matrix contains `customer`
  * each test marked `sharded` must have exactly one dedicated shard tag
    * the tag must match `customer`, `order`, or `payment` from the workflow matrix
  * the contract fails when
    * a test has `sharded` but no dedicated shard tag
    * a dedicated shard tag is absent from the workflow matrix
    * a test has more than one dedicated shard tag
    * a dedicated workflow shard has no matching test
* consistency test
  * `TestShardConsistencyTest` reads shard values from the workflow matrix
  * it excludes `unsharded` because that value identifies the catch-all job
  * it discovers compiled tests and their JUnit tags
      * example
          ```java
          var request = LauncherDiscoveryRequestBuilder.request()
                  .selectors(selectClasspathRoots(Set.of(testClassesRoot)))
                  .build();
          var testPlan = LauncherFactory.create().discover(request);
          ```
      * discovery reads test identifiers and tags without executing test methods
      * discovery invokes registered JUnit test engines and may load test classes
      * discovery is not equivalent to reading class files without running test-framework code
      * with ordinary Spring Boot tests, discovery does not
        * execute `@SpringBootTest`
        * create the Spring `ApplicationContext`
        * run test lifecycle callbacks
        * start containers managed by the Testcontainers JUnit extension
      * class loading or a custom extension can still start infrastructure
    
        ```java
        static PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>("postgres:17").start();
        ```
    
      * avoid starting containers or other infrastructure in static initializers
      * Quarkus 3.22 and later performs augmentation during discovery of `@QuarkusTest` classes
        * augmentation analyzes the application and its extensions
        * augmentation creates metadata and generated code required to run the application
        * Dev Services start during this phase
      * in such a Quarkus project, the consistency test may start Dev Services and containers
        * the check can become slow
        * the check can require Docker or external resources
        * the check can fail in a restricted environment

  * it compares the dedicated workflow values with the discovered dedicated tags

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
    * example
      ```yaml
      name: Test shards # workflow name in GitHub
    
      on: # events that start the workflow
        push: # every push
        pull_request: # every pull request
        workflow_dispatch: # manual start
    
      permissions: # default GITHUB_TOKEN permissions
        contents: read # read repository content

      jobs: # workflow jobs
        example: # job identifier
          runs-on: ubuntu-latest # runner
          steps: # sequential job steps
            - name: Print message # step name
              run: echo "Workflow started" # shell command
      ```

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
  * a job contains an ordered list of `steps`
  * steps run from top to bottom
  * a step starts after the previous step finishes
  * all steps in one job use the same runner and workspace
  * `uses` invokes a reusable action
    * `actions/checkout`
      * checks out repository files into the runner workspace
      * supports a partial checkout with `sparse-checkout`

        ```yaml
        - uses: actions/checkout@v4
          with:
            sparse-checkout: |
              .github
              .mvn
              src
        ```

      * cone mode also includes root files such as `mvnw` and `pom.xml`
      * a partial checkout provides little benefit in this project
        * Maven needs `.mvn`, `mvnw`, `pom.xml`, and `src`
        * `TestShardConsistencyTest` also needs `.github/workflows/test-shards.yml`
    * `actions/setup-java`
      * selects Temurin Java 21 for the job

        ```yaml
        - uses: actions/setup-java@v4
          with:
            distribution: temurin
            java-version: "21"
            cache: maven
        ```

      * Maven requires Java to start
      * Maven also uses Java to compile the application and run the tests
      * the action makes Java 21 available to later steps through `JAVA_HOME` and `PATH`
      * `cache: maven` caches downloaded Maven dependencies for later workflow runs
  * `run` executes a shell command such as `./mvnw --batch-mode test`
* matrices and expressions

  ```yaml
  jobs:
    build:
      strategy:
        matrix:
          # Two operating systems and two Java versions create four jobs.
          os: [ubuntu-latest, windows-latest]
          java: [17, 21]

      # Use the operating system for the current job.
      runs-on: ${{ matrix.os }}

      steps:
        # Run this step only in the Ubuntu jobs.
        - name: Print Java version
          if: matrix.os == 'ubuntu-latest'

          # Expose the current Java version as a shell variable.
          env:
            JAVA_VERSION: ${{ matrix.java }}
          run: echo "Java $JAVA_VERSION"
  ```

  * `fail-fast: false` prevents one failed matrix job from cancelling its siblings; it does not hide the failure
* job dependencies
  * jobs are independent by default
  * their order in the YAML file does not control execution order
  * `needs` creates a dependency between jobs
  * `needs: build` makes a job wait for the `build` job
  * if `build` uses a matrix, the dependent job waits for all matrix runs
  * `needs.build.result` reads the result of `build`
  * a dependent job is normally skipped when a prerequisite fails

  ```yaml
  jobs:
    build:
      runs-on: ubuntu-latest
      steps:
        - run: ./build.sh

    report:
      needs: build # evaluated after build
      runs-on: ubuntu-latest
      steps:
        - run: ./report.sh
  ```

  * `report` waits for `build`
  * YAML order alone does not create this dependency
* status conditions
  * `success()` runs after success and is the default
  * `failure()` runs after a failure
  * `cancelled()` runs after cancellation
  * `always()` runs after success, failure, or cancellation
  * `!cancelled()` runs after success or failure, but not cancellation

  ```yaml
  steps: # run from top to bottom
    - name: Build
      run: ./build.sh
    - name: Cleanup # evaluated after Build
      if: always() # ignore results of all earlier steps
      run: ./cleanup.sh
  ```

* artifacts between jobs
  * each job has a separate runner and filesystem
  * files created by one job are not available to another job
  * an artifact is a named collection of files stored by GitHub
  * a job can upload reports, logs, binaries, or other files
  * another job can download the artifact

    ```text
    producer job -> upload -> GitHub artifact storage -> download -> consumer job
    ```

  ```yaml
  - uses: actions/upload-artifact@v4 # upload files to GitHub artifact storage
    with:
      name: build-output # artifact name
      path: build/ # directory from the current runner
  ```

  * this example stores `build/` as the artifact named `build-output`
* current workflow aggregation
  * `download-artifact` selects artifacts matching `surefire-reports-*`

    ```yaml
    - uses: actions/download-artifact@v4 # download stored artifacts
      with:
        pattern: surefire-reports-* # select artifact names
        path: combined-reports # destination on the current runner
        merge-multiple: true # extract all matches into one directory
    ```

  * `merge-multiple: true` extracts all selected artifacts into `combined-reports`
  * it does not combine the XML content
  * equal relative filenames can overwrite each other
  * report filenames must therefore be unique across shards
  * `dorny/test-reporter` reads the remaining `TEST-*.xml` files and creates one GitHub check
  * the final step fails when any shard failed, preserving the original workflow result
