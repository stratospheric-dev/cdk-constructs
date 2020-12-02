package dev.stratospheric.cdk;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Environment;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;

import java.util.Collections;
import java.util.Map;

public class SpringBootApplication extends Stack {

    public SpringBootApplication(
            final Construct scope,
            final String id,
            final Environment awsEnvironment,
            final String sslCertificateArn,
            final String dockerImageUrl) {
        super(scope, id, StackProps.builder()
                .stackName("SpringBootApplication")
                .env(awsEnvironment).build());

        Network network = new Network(this, "network", awsEnvironment, "prod", new Network.NetworkProperties(sslCertificateArn));
        Service service = new Service(this, "Service", awsEnvironment, new ApplicationEnvironment("SpringBootApplication", "prod"), new Service.ServiceProperties(
                new Service.DockerImageSource(dockerImageUrl),
                Collections.emptyList(),
                Map.of(
                        "AWS_REGION", awsEnvironment.getRegion(),
                        "SPRING_PROFILES_ACTIVE", "prod"
                )
        ));

    }
}
