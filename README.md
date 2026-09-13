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

* demonstrates job-level test sharding
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
  * `--errors`
    * writes Maven exception stack traces to the CI log
    * used for dependency-resolution, plugin, or Maven execution failures
        * ordinary test assertion failures are already available in the Surefire reports
* test-selection properties
  * example: `./mvnw --batch-mode test "-Dgroups=sharded & customer"`
  * `groups`
    * selects tests whose JUnit tags match an expression
    * example: `sharded & customer` selects tests carrying both tags
  * `excludedGroups`
    * excludes tests whose JUnit tags match an expression
  * `failIfNoTests`
    * fails the Maven command when the test selection is empty

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
  * everything under `jobs.shards` defines one job template
  * the matrix creates four copies of that job
  * for each copy
    * `name` and `runs-on` are evaluated
    * a separate runner starts
    * each step is evaluated
    * a step runs if it has no `if`
    * a step is skipped if its `if` condition is false
  * `strategy` configures the copies; it is not an executed step

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

## tag-based sharding

* overview
  * assign each dedicated test a shard tag
  * run one CI job for each shard tag
* JUnit tag
  * a text label added with `@Tag`
  * can label a test class or test method
  * the label has no effect until a test command uses it
  * Maven Surefire can select tests by tag

    ```shell
    ./mvnw -Dgroups=customer test
    ```

  * this command runs tests tagged `customer`
* shard contract
  * defining a shard requires modifications in two places
    * test code: assign a shard tag to the tests
    * CI workflow: add the same shard name to the matrix
  * both places must use exactly the same name
  * each sharded test must have exactly one shard name
    * consistency check
      * an automated test can detect differences between test tags and the CI matrix
      * example
        1. reads shard names from the workflow
        1. reads tags from tests
           * discovery behavior
             * example
    
               ```java
               // search request in selected directories for compiled test classes
               var request = LauncherDiscoveryRequestBuilder.request()
                       .selectors(selectClasspathRoots(Set.of(testClassesRoot)))
                       .build();
    
               // contains the discovered tests, identifiers, and tags
               var testPlan = LauncherFactory.create().discover(request);
    
               var tags = testPlan.getRoots().stream()
                       // get all test classes and methods below each test-engine root
                       // plan roots are the top-level test-engine nodes, ex.: JUnit Jupiter
                       .flatMap(root -> testPlan.getDescendants(root).stream())
                       // get the tags attached to each test
                       .flatMap(test -> test.getTags().stream())
                       // get each tag name
                       .map(TestTag::getName)
                       // remove duplicate tag names
                       .collect(Collectors.toSet());
               ```
             * discovery runs test-engine code
                * in particular: it does not only read `.class` files
                * example
                    * test engine may load a test class to inspect its annotations and methods
                        * a static initializer can start infrastructure
                           * example
                        
                             ```
                             static PostgreSQLContainer<?> postgres =
                                     new PostgreSQLContainer<>("postgres:17").start();
                             ```
                    * Quarkus 3.22 and later performs augmentation during discovery of `@QuarkusTest` classes
                        * augmentation analyzes the application and its extensions
                        * augmentation creates metadata and generated code required to run the application
                        * Dev Services start during this phase
                    * with ordinary Spring Boot tests, discovery does not
                        * execute `@SpringBootTest`
                        * create the Spring `ApplicationContext`
                        * run test lifecycle callbacks
                        * start containers managed by the Testcontainers JUnit extension
        1. compares the two sets of shard names
