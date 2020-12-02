package dev.stratospheric.cdk;

import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.ec2.CfnSecurityGroup;
import software.amazon.awscdk.services.ec2.CfnSecurityGroupIngress;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.CfnService;
import software.amazon.awscdk.services.ecs.CfnTaskDefinition;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnListenerRule;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnTargetGroup;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;

import java.util.*;

import static java.util.Collections.singletonList;

/**
 * Creates an ECS service on top of a {@link Network}. Loads Docker images from a {@link DockerRepository}
 * and places them into ECS task. Creates a log group for each ECS task. Creates a target group for the ECS tasks
 * that is attached to the load balancer from the {@link Network}.
 */
public class Service extends Stack {

    public static class DockerImageSource {
        private final String dockerRepositoryName;
        private final String dockerImageTag;
        private final String dockerImageUrl;

        /**
         * Loads a Docker image from the given URL.
         */
        public DockerImageSource(String dockerImageUrl) {
            Objects.requireNonNull(dockerImageUrl);
            this.dockerImageUrl = dockerImageUrl;
            this.dockerImageTag = null;
            this.dockerRepositoryName = null;
        }

        /**
         * Loads a Docker image from the given ECR repository and image tag.
         */
        public DockerImageSource(String dockerRepositoryName, String dockerImageTag) {
            Objects.requireNonNull(dockerRepositoryName);
            Objects.requireNonNull(dockerImageTag);
            this.dockerRepositoryName = dockerRepositoryName;
            this.dockerImageTag = dockerImageTag;
            this.dockerImageUrl = null;
        }

        public boolean isEcrSource() {
            return this.dockerRepositoryName != null;
        }

        public String getDockerRepositoryName() {
            return dockerRepositoryName;
        }

        public String getDockerImageTag() {
            return dockerImageTag;
        }

        public String getDockerImageUrl() {
            return dockerImageUrl;
        }
    }

    public static class ServiceProperties {
        private final DockerImageSource dockerImageSource;
        private final Map<String, String> environmentVariables;
        private final List<String> securityGroupIdsToGrantIngressFromEcs;
        private int healthCheckIntervalSeconds = 15;
        private String healthCheckPath = "/actuator/health";
        private int containerPort = 8080;
        private String containerProtocol = "HTTP";
        private int healthCheckTimeoutSeconds = 5;
        private int healthyThresholdCount = 2;
        private int unhealthyThresholdCount = 8;
        private RetentionDays logRetention = RetentionDays.ONE_WEEK;
        private int cpu = 256;
        private int memory = 512;
        private int desiredInstancesCount = 2;
        private int maximumInstancesPercent = 200;
        private int minimumHealthyInstancesPercent = 50;

        /**
         * Knobs and dials you can configure to run a Docker image in an ECS service. The default values are set in a way
         * to work out of the box with a Spring Boot application.
         *
         * @param dockerImageSource                     the source from where to load the Docker image that we want to deploy.
         * @param securityGroupIdsToGrantIngressFromEcs Ids of the security groups that the ECS containers should be granted access to.
         * @param environmentVariables                  the environment variables provided to the Java runtime within the Docker containers.
         */
        public ServiceProperties(
                DockerImageSource dockerImageSource,
                List<String> securityGroupIdsToGrantIngressFromEcs,
                Map<String, String> environmentVariables) {
            this.dockerImageSource = dockerImageSource;
            this.environmentVariables = environmentVariables;
            this.securityGroupIdsToGrantIngressFromEcs = securityGroupIdsToGrantIngressFromEcs;
        }

        /**
         * The interval to wait between two health checks.
         * <p>
         * Default: 15.
         */
        public ServiceProperties withHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) {
            this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
            return this;
        }

        /**
         * The path of the health check URL.
         * <p>
         * Default: "/actuator/health".
         */
        public ServiceProperties withHealthCheckPath(String healthCheckPath) {
            Objects.requireNonNull(healthCheckPath);
            this.healthCheckPath = healthCheckPath;
            return this;
        }

        /**
         * The port the application listens on within the container.
         * <p>
         * Default: 8080.
         */
        public ServiceProperties withContainerPort(int containerPort) {
            Objects.requireNonNull(containerPort);
            this.containerPort = containerPort;
            return this;
        }

        /**
         * The protocol to access the application within the container. Default: "HTTP".
         */
        public ServiceProperties withContainerProtocol(String containerProtocol) {
            Objects.requireNonNull(containerProtocol);
            this.containerProtocol = containerProtocol;
            return this;
        }

        /**
         * The number of seconds to wait for a response until a health check is deemed unsuccessful.
         * <p>
         * Default: 5.
         */
        public ServiceProperties withHealthCheckTimeoutSeconds(int healthCheckTimeoutSeconds) {
            this.healthCheckTimeoutSeconds = healthCheckTimeoutSeconds;
            return this;
        }

        /**
         * The number of consecutive successful health checks after which an instance is declared healthy.
         * <p>
         * Default: 2.
         */
        public ServiceProperties withHealthyThresholdCount(int healthyThresholdCount) {
            this.healthyThresholdCount = healthyThresholdCount;
            return this;
        }

        /**
         * The number of consecutive unsuccessful health checks after which an instance is declared unhealthy.
         * <p>
         * Default: 8.
         */
        public ServiceProperties withUnhealthyThresholdCount(int unhealthyThresholdCount) {
            this.unhealthyThresholdCount = unhealthyThresholdCount;
            return this;
        }

        /**
         * The number of CPU units allocated to each instance of the application. See
         * <a ahref="https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-cpu-memory-error.html"?>the docs</a>
         * for a table of valid values.
         * <p>
         * Default: 256 (0.25 CPUs).
         */
        public ServiceProperties withCpu(int cpu) {
            this.cpu = cpu;
            return this;
        }

        /**
         * The memory allocated to each instance of the application in megabytes. See
         * <a ahref="https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-cpu-memory-error.html"?>the docs</a>
         * for a table of valid values.
         * <p>
         * Default: 512.
         */
        public ServiceProperties withMemory(int memory) {
            this.memory = memory;
            return this;
        }

        /**
         * The duration the logs of the application should be retained.
         * <p>
         * Default: 1 week.
         */
        public ServiceProperties withLogRetention(RetentionDays logRetention) {
            Objects.requireNonNull(logRetention);
            this.logRetention = logRetention;
            return this;
        }

        /**
         * The number of instances that should run in parallel behind the load balancer.
         * <p>
         * Default: 2.
         */
        public ServiceProperties withDesiredInstances(int desiredInstances) {
            this.desiredInstancesCount = desiredInstances;
            return this;
        }

        /**
         * The maximum percentage in relation to the desired instances that may be running at the same time
         * (for example during deployments).
         * <p>
         * Default: 200.
         */
        public ServiceProperties withMaximumInstancesPercent(int maximumInstancesPercent) {
            this.maximumInstancesPercent = maximumInstancesPercent;
            return this;
        }

        /**
         * The minimum percentage in relation to the desired instances that must be running at the same time
         * (for example during deployments).
         * <p>
         * Default: 50.
         */
        public ServiceProperties withMinimumHealthyInstancesPercent(int minimumHealthyInstancesPercent) {
            this.minimumHealthyInstancesPercent = minimumHealthyInstancesPercent;
            return this;
        }


    }

    public Service(
            final Construct scope,
            final String id,
            final Environment awsEnvironment,
            final ApplicationEnvironment applicationEnvironment,
            ServiceProperties serviceProperties) {
        super(scope, id, StackProps.builder()
                .stackName(applicationEnvironment.prefix("Service"))
                .env(awsEnvironment).build());

        String vpcId = Network.getVpcId(this, applicationEnvironment.getEnvironmentName());

        CfnTargetGroup targetGroup = CfnTargetGroup.Builder.create(this, "targetGroup")
                .healthCheckIntervalSeconds(serviceProperties.healthCheckIntervalSeconds)
                .healthCheckPath(serviceProperties.healthCheckPath)
                .healthCheckPort(String.valueOf(serviceProperties.containerPort))
                .healthCheckProtocol(serviceProperties.containerProtocol)
                .healthCheckTimeoutSeconds(serviceProperties.healthCheckTimeoutSeconds)
                .healthyThresholdCount(serviceProperties.healthyThresholdCount)
                .unhealthyThresholdCount(serviceProperties.unhealthyThresholdCount)
                .targetType("ip")
                .name(applicationEnvironment.prefix("targetGroup"))
                .port(serviceProperties.containerPort)
                .protocol(serviceProperties.containerProtocol)
                .vpcId(vpcId)
                .build();

        CfnListenerRule.ActionProperty actionProperty = CfnListenerRule.ActionProperty.builder()
                .targetGroupArn(targetGroup.getRef())
                .type("forward")
                .build();

        CfnListenerRule.RuleConditionProperty condition = CfnListenerRule.RuleConditionProperty.builder()
                .field("path-pattern")
                .values(singletonList("*"))
                .build();

        String httpsListenerArn = Network.getHttpsListenerArn(this, applicationEnvironment.getEnvironmentName());
        CfnListenerRule httpsListenerRule = CfnListenerRule.Builder.create(this, "httpsListenerRule")
                .actions(singletonList(actionProperty))
                .conditions(singletonList(condition))
                .listenerArn(httpsListenerArn)
                .priority(1)
                .build();

        String httpListenerArn = Network.getHttpListenerArn(this, applicationEnvironment.getEnvironmentName());
        CfnListenerRule httpListenerRule = CfnListenerRule.Builder.create(this, "httpListenerRule")
                .actions(singletonList(actionProperty))
                .conditions(singletonList(condition))
                .listenerArn(httpListenerArn)
                .priority(2)
                .build();

        LogGroup logGroup = LogGroup.Builder.create(this, "ecsLogGroup")
                .logGroupName(applicationEnvironment.prefix("logs"))
                .retention(serviceProperties.logRetention)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Role ecsTaskExecutionRole = Role.Builder.create(this, "ecsTaskExecutionRole")
                .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
                .path("/")
                .inlinePolicies(Map.of(
                        applicationEnvironment.prefix("ecsTaskExecutionRolePolicy"),
                        PolicyDocument.Builder.create()
                                .statements(singletonList(PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(singletonList("*"))
                                        .actions(Arrays.asList(
                                                "ecr:GetAuthorizationToken",
                                                "ecr:BatchCheckLayerAvailability",
                                                "ecr:GetDownloadUrlForLayer",
                                                "ecr:BatchGetImage",
                                                "logs:CreateLogStream",
                                                "logs:PutLogEvents"))
                                        .build()))
                                .build()))
                .build();

        Role ecsTaskRole = Role.Builder.create(this, "ecsTaskRole")
                .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
                .path("/")
                .inlinePolicies(Map.of(
                        applicationEnvironment.prefix("ecsTaskRolePolicy"),
                        PolicyDocument.Builder.create()
                                .statements(singletonList(PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(singletonList("*"))
                                        .actions(Arrays.asList(
                                                "sqs:DeleteMessage",
                                                "sqs:GetQueueUrl",
                                                "sqs:ListDeadLetterSourceQueues",
                                                "sqs:ListQueues",
                                                "sqs:ListQueueTags",
                                                "sqs:ReceiveMessage",
                                                "sqs:SendMessage",
                                                "sqs:ChangeMessageVisibility",
                                                "sqs:GetQueueAttributes"))
                                        .build()))
                                .build()))
                .build();

        String dockerRepositoryUrl = null;
        if (serviceProperties.dockerImageSource.isEcrSource()) {
            IRepository dockerRepository = Repository.fromRepositoryName(this, "ecrRepository", serviceProperties.dockerImageSource.getDockerRepositoryName());
            dockerRepository.grantPull(ecsTaskExecutionRole);
            dockerRepositoryUrl = dockerRepository.repositoryUriForTag(serviceProperties.dockerImageSource.getDockerImageTag());
        } else {
            dockerRepositoryUrl = serviceProperties.dockerImageSource.dockerImageUrl;
        }

        CfnTaskDefinition.ContainerDefinitionProperty container = CfnTaskDefinition.ContainerDefinitionProperty.builder()
                .name(containerName(applicationEnvironment))
                .cpu(serviceProperties.cpu)
                .memory(serviceProperties.memory)
                .image(dockerRepositoryUrl)
                .logConfiguration(CfnTaskDefinition.LogConfigurationProperty.builder()
                        .logDriver("awslogs")
                        .options(Map.of(
                                "awslogs-group", logGroup.getLogGroupName(),
                                "awslogs-region", getRegion(),
                                "awslogs-stream-prefix", applicationEnvironment.prefix("stream"),
                                "awslogs-multiline-pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                        .build())
                .portMappings(singletonList(CfnTaskDefinition.PortMappingProperty.builder()
                        .containerPort(serviceProperties.containerPort)
                        .build()))
                .environment(toKeyValuePairs(serviceProperties.environmentVariables))
                .build();

        CfnTaskDefinition taskDefinition = CfnTaskDefinition.Builder.create(this, "taskDefinition")
                // skipped family
                .cpu(String.valueOf(serviceProperties.cpu))
                .memory(String.valueOf(serviceProperties.memory))
                .networkMode("awsvpc")
                .requiresCompatibilities(singletonList("FARGATE"))
                .executionRoleArn(ecsTaskExecutionRole.getRoleArn())
                .taskRoleArn(ecsTaskRole.getRoleArn())
                .containerDefinitions(singletonList(container))
                .build();

        CfnSecurityGroup ecsSecurityGroup = CfnSecurityGroup.Builder.create(this, "ecsSecurityGroup")
                .vpcId(vpcId)
                .groupDescription("SecurityGroup for the ECS containers")
                .groupName(applicationEnvironment.prefix("ecsSecurityGroup"))
                .build();

        // allow ECS containers to access each other
        CfnSecurityGroupIngress ecsIngressFromSelf = CfnSecurityGroupIngress.Builder.create(this, "ecsIngressFromSelf")
                .ipProtocol("-1")
                .sourceSecurityGroupId(ecsSecurityGroup.getAttrGroupId())
                .groupId(ecsSecurityGroup.getAttrGroupId())
                .build();

        // allow the load balancer to access the containers
        String loadbalancerSecurityGroupId = Network.getLoadbalancerSecurityGroupId(this, applicationEnvironment.getEnvironmentName());
        CfnSecurityGroupIngress ecsIngressFromLoadbalancer = CfnSecurityGroupIngress.Builder.create(this, "ecsIngressFromLoadbalancer")
                .ipProtocol("-1")
                .sourceSecurityGroupId(loadbalancerSecurityGroupId)
                .groupId(ecsSecurityGroup.getAttrGroupId())
                .build();

        allowIngressFromEcs(serviceProperties.securityGroupIdsToGrantIngressFromEcs, ecsSecurityGroup);

        String ecsClusterName = Network.getEcsClusterName(this, applicationEnvironment.getEnvironmentName());
        List<String> publicSubnetIds = Network.getPublicSubnets(this, applicationEnvironment.getEnvironmentName());
        CfnService service = CfnService.Builder.create(this, "ecsService")
                .cluster(ecsClusterName)
                .launchType("FARGATE")
                .deploymentConfiguration(CfnService.DeploymentConfigurationProperty.builder()
                        .maximumPercent(serviceProperties.maximumInstancesPercent)
                        .minimumHealthyPercent(serviceProperties.minimumHealthyInstancesPercent)
                        .build())
                .desiredCount(serviceProperties.desiredInstancesCount)
                .taskDefinition(taskDefinition.getRef())
                .loadBalancers(singletonList(CfnService.LoadBalancerProperty.builder()
                        .containerName(containerName(applicationEnvironment))
                        .containerPort(serviceProperties.containerPort)
                        .targetGroupArn(targetGroup.getRef())
                        .build()))
                .networkConfiguration(CfnService.NetworkConfigurationProperty.builder()
                        .awsvpcConfiguration(CfnService.AwsVpcConfigurationProperty.builder()
                                .assignPublicIp("ENABLED")
                                .securityGroups(singletonList(ecsSecurityGroup.getAttrGroupId()))
                                .subnets(publicSubnetIds)
                                .build())
                        .build())
                .build();

        applicationEnvironment.tag(this);

    }

    private void allowIngressFromEcs(List<String> securityGroupIds, CfnSecurityGroup ecsSecurityGroup) {
        int i = 1;
        for (String securityGroupId : securityGroupIds) {
            CfnSecurityGroupIngress ingress = CfnSecurityGroupIngress.Builder.create(this, "securityGroupIngress" + i)
                    .sourceSecurityGroupId(ecsSecurityGroup.getAttrGroupId())
                    .groupId(securityGroupId)
                    .ipProtocol("-1")
                    .build();
            i++;
        }
    }

    private String containerName(ApplicationEnvironment applicationEnvironment) {
        return applicationEnvironment.prefix("container");
    }


    private CfnTaskDefinition.KeyValuePairProperty keyValuePair(String key, String value) {
        return CfnTaskDefinition.KeyValuePairProperty.builder()
                .name(key)
                .value(value)
                .build();
    }

    public List<CfnTaskDefinition.KeyValuePairProperty> toKeyValuePairs(Map<String, String> map) {
        List<CfnTaskDefinition.KeyValuePairProperty> keyValuePairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            keyValuePairs.add(keyValuePair(entry.getKey(), entry.getValue()));
        }
        return keyValuePairs;
    }

}
