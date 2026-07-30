# spring-boot-ci-test-sharding-workshop

A minimal Java 21 and Spring Boot 3 project demonstrating CI test sharding with
JUnit 5 tags.

## Run locally

Each command selects one `@Tag` through Maven Surefire:

```bash
./mvnw test -Dgroups=customer
./mvnw test -Dgroups=order
./mvnw test -Dgroups=payment
```

Every shard contains one `@SpringBootTest` that calls
`GET /api/greetings/{name}` and sleeps for 60 seconds. The customer and order
shards pass. The payment shard fails intentionally and deterministically after
the delay, so its non-zero exit code is expected.

## CI sharding

The GitHub Actions workflow creates a matrix containing `customer`, `order`,
and `payment`. With `fail-fast: false`, all three roughly 60-second shards run
in parallel even when one fails, instead of taking roughly three minutes
sequentially.

Each matrix job uploads its `target/surefire-reports/` directory with
`if: always()`. The final job downloads every artifact and publishes all XML
files as one combined JUnit report. It then preserves the failed workflow
status caused by the intentionally failing payment shard.
