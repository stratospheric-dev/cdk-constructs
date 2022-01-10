# Stratospheric CDK Constructs

![Build & Release](https://github.com/stratospheric-dev/cdk-constructs/workflows/Build%20&%20Release/badge.svg?branch=release)
![CodeQL](https://github.com/stratospheric-dev/cdk-constructs/workflows/CodeQL/badge.svg)

[![CDK Constructs Maven Central](https://img.shields.io/maven-central/v/dev.stratospheric/cdk-constructs.svg?label=CDK%20Constructs%20Maven%20Central)](https://search.maven.org/search?q=g:%22dev.stratospheric%22%20AND%20a:%22cdk-constructs%22)

A collection of Java CDK constructs that play well together to deploy an application and a database to Amazon ECS.

The constructs have been built to deploy a real application into production and will be updated as this application evolves.

These constructs are explained in further detail in [our book](https://stratospheric.dev).

From version 0.1.0 onwards, this constructs library only supports the AWS CDK v2. For migrating your existing AWS CDK v1 setup, follow the [official AWS migration guide](https://docs.aws.amazon.com/cdk/v2/guide/migrating-v2.html).

## Installation

Load the dependency from Maven Central by adding this to your `pom.xml`:

```xml
<dependencies>
  <dependency>
    <groupId>dev.stratospheric</groupId>
    <artifactId>cdk-constructs</artifactId>
    <version>${latestVersion}</version>
  </dependency>
</dependencies>
```

... our your `build.gradle`:

```groovy
implementation('dev.stratospheric:cdk-constructs:${latestVersion}')
```

The `latestVersion` is: [![](https://img.shields.io/maven-central/v/dev.stratospheric/cdk-constructs.svg?label=)](https://search.maven.org/search?q=g:%22dev.stratospheric%22%20AND%20a:%22cdk-constructs%22)

Use this version without the `v` prefix inside your `pom.xml` or `build.gradle`: `v0.0.13` -> `0.0.13`.

To override the version of the AWS Java CDK library, use a `<dependencyManagement>` inside your `pom.xml`:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>software.amazon.awscdk</groupId>
      <artifactId>aws-cdk-lib</artifactId>
      <version>2.3.0</version>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Construct Overview

A short description of the constructs in this project. For a more details description have a look at the Javadocs.

* **[DockerRepository](src/main/java/dev/stratospheric/cdk/DockerRepository.java)**: a stack that contains a single ECR Docker repository and grants push and pull permissions to all users of the given account.
* **[Network](src/main/java/dev/stratospheric/cdk/Network.java)**: creates a VPC with public and isolated subnets and a loadbalancer. Exposes parameters in the parameter store to be consumed by other constructs so they can be placed into that network.
* **[PostgresDatabase](src/main/java/dev/stratospheric/cdk/PostgresDatabase.java)**: creates a PostgreSQL database in the isolated subnets of a given network. Requires a running `Network` stack (or at least the parameters it would expose in the SSM parameter store). Exposes the database connection parameters in the parameter store.
* **[JumpHost](src/main/java/dev/stratospheric/cdk/JumpHost.java)**: creates an EC2 instance in a `Network`'s public subnet that has access to the PostgreSQL instance in a `PostgreSQLDatabase` stack. This EC2 instance can act as a jump host (aka bastion host) to connect to the database from your local machine.
* **[Service](src/main/java/dev/stratospheric/cdk/Service.java)**: creates an ECS service that deploys a given Docker image into the public subnets of a given `Network`. Allows configuration of things like health check and environment variables.
* **[SpringBootApplicationStack](src/main/java/dev/stratospheric/cdk/Service.java)**: a stack that combines the [Network](src/main/java/dev/stratospheric/cdk/Network.java) and [Service](src/main/java/dev/stratospheric/cdk/Service.java) constructs, configured for easy deployment of a Spring Boot Docker image.


## Usage

An example usage of the database construct might look like this:

```java
public class DatabaseApp {

  public static void main(final String[] args) {
  App app = new App();

  String environmentName = (String) app.getNode().tryGetContext("environmentName");
  requireNonEmpty(environmentName, "context variable 'environmentName' must not be null");

  String applicationName = (String) app.getNode().tryGetContext("applicationName");
  requireNonEmpty(applicationName, "context variable 'applicationName' must not be null");

  String accountId = (String) app.getNode().tryGetContext("accountId");
  requireNonEmpty(accountId, "context variable 'accountId' must not be null");

  String region = (String) app.getNode().tryGetContext("region");
  requireNonEmpty(region, "context variable 'region' must not be null");

  Environment awsEnvironment = makeEnv(accountId, region);

  ApplicationEnvironment applicationEnvironment = new ApplicationEnvironment(
    applicationName,
    environmentName
  );

  PostgresDatabase database = new PostgresDatabase(
    app,
    "Database",
    awsEnvironment,
    applicationEnvironment,
    new PostgresDatabase.DatabaseProperties());

  app.synth();
  }

  static Environment makeEnv(String account, String region) {
  return Environment.builder()
    .account(account)
    .region(region)
    .build();
  }

}
```

**An instance of `ApplicationEnvironment` specifies which environment the construct should be deployed into**. You can have multiple instances of each construct running, each with a different application (i.e. the name of the service you want to deploy) and a different environment (i.e. "test", "staging", "prod" or similar).

**Most constructs take a properties object in the constructor**. Use these to configure the constructs. They have sensible defaults.

**Most constructs require certain parameters to be available in the SSM parameter store**. The `PostgresDatabase` construct, for example, needs the parameters exposed by the `Network` construct to be available in the SSM parameter store. Read the Javadocs to see which parameters are required. You can set those parameters manually, but it's easiest to have deployed a `Network` construct in the same environment beforehand.

While it's totally possible to put all constructs into the same CDK app, **we recommend to put each construct into its own CDK app**. The reason for this is flexibility. You may want to deploy and destroy a jump host separately from the rest. Or you may want to move a database between two environments. In these cases, having everything in the same app makes you very inflexible.

Also, a monolithic CDK app would require you to pass the parameters for ALL constructs, even if you only want to deploy or destroy a single one.

## Release Process

Head over to the [release information document](RELEASE.md).


