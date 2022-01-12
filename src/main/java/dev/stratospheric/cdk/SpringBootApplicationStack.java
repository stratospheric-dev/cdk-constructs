package dev.stratospheric.cdk;

import java.util.Collections;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

/**
 * This stack creates a {@link Network} and a {@link Service} that deploys a given Docker image. The {@link Service} is
 * pre-configured for a Spring Boot application.
 * <p>
 * This construct is for demonstration purposes since it's not configurable and thus of little practical use.
 */
public class SpringBootApplicationStack extends Stack {

  public SpringBootApplicationStack(
    final Construct scope,
    final String id,
    final Environment environment,
    final String dockerImageUrl) {
    super(scope, id, StackProps.builder()
      .stackName("SpringBootApplication")
      .env(environment)
      .build());


    Network network = new Network(this, "network", environment, "prod", new Network.NetworkInputParameters());
    Service service = new Service(this, "Service", environment, new ApplicationEnvironment("SpringBootApplication", "prod"),
      new Service.ServiceInputParameters(
        new Service.DockerImageSource(dockerImageUrl),
        Collections.emptyList(),
        Collections.emptyMap()),
      network.getOutputParameters());

    CfnOutput httpsListenerOutput = new CfnOutput(this, "loadbalancerDnsName", CfnOutputProps.builder()
      .exportName("loadbalancerDnsName")
      .value(network.getLoadBalancer().getLoadBalancerDnsName())
      .build());
  }

}
