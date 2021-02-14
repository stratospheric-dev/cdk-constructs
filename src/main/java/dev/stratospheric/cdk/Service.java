package dev.stratospheric.cdk;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Environment;
import software.amazon.awscdk.core.RemovalPolicy;
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
import software.amazon.awscdk.services.ssm.StringParameter;

import java.util.*;

import static java.util.Collections.singletonList;

/**
 * Creates an ECS service on top of a {@link Network}. Loads Docker images from a {@link DockerImageSource}
 * and places them into ECS task. Creates a log group for each ECS task. Creates a target group for the ECS tasks
 * that is attached to the load balancer from the {@link Network}.
 */
public class Service extends Construct {

  private static final String PARAMETER_ECS_SECURITY_GROUP_ID = "ecsSecurityGroupId";
  private final CfnSecurityGroup ecsSecurityGroup;

  public Service(
    final Construct scope,
    final String id,
    final Environment awsEnvironment,
    final ApplicationEnvironment applicationEnvironment,
    final ServiceInputParameters serviceInputParameters,
    final Network.NetworkOutputParameters networkOutputParameters
  ) {
    super(scope, id);

    List<CfnTargetGroup.TargetGroupAttributeProperty> stickySessionConfiguration = Arrays.asList(
      CfnTargetGroup.TargetGroupAttributeProperty.builder().key("stickiness.enabled").value("true").build(),
      CfnTargetGroup.TargetGroupAttributeProperty.builder().key("stickiness.type").value("lb_cookie").build(),
      CfnTargetGroup.TargetGroupAttributeProperty.builder().key("stickiness.lb_cookie.duration_seconds").value("3600").build()
    );

    CfnTargetGroup targetGroup = CfnTargetGroup.Builder.create(this, "targetGroup")
      .healthCheckIntervalSeconds(serviceInputParameters.healthCheckIntervalSeconds)
      .healthCheckPath(serviceInputParameters.healthCheckPath)
      .healthCheckPort(String.valueOf(serviceInputParameters.containerPort))
      .healthCheckProtocol(serviceInputParameters.containerProtocol)
      .healthCheckTimeoutSeconds(serviceInputParameters.healthCheckTimeoutSeconds)
      .healthyThresholdCount(serviceInputParameters.healthyThresholdCount)
      .unhealthyThresholdCount(serviceInputParameters.unhealthyThresholdCount)
      .targetGroupAttributes(serviceInputParameters.stickySessionsEnabled ? stickySessionConfiguration : Arrays.asList())
      .targetType("ip")
      .port(serviceInputParameters.containerPort)
      .protocol(serviceInputParameters.containerProtocol)
      .vpcId(networkOutputParameters.getVpcId())
      .build();

    CfnListenerRule.ActionProperty actionProperty = CfnListenerRule.ActionProperty.builder()
      .targetGroupArn(targetGroup.getRef())
      .type("forward")
      .build();

    CfnListenerRule.RuleConditionProperty condition = CfnListenerRule.RuleConditionProperty.builder()
      .field("path-pattern")
      .values(singletonList("*"))
      .build();

    Optional<String> httpsListenerArn = networkOutputParameters.getHttpsListenerArn();
    CfnListenerRule httpsListenerRule = null;
    if (httpsListenerArn.isPresent()) {
      httpsListenerRule = CfnListenerRule.Builder.create(this, "httpsListenerRule")
        .actions(singletonList(actionProperty))
        .conditions(singletonList(condition))
        .listenerArn(httpsListenerArn.get())
        .priority(1)
        .build();
    }

    CfnListenerRule httpListenerRule = CfnListenerRule.Builder.create(this, "httpListenerRule")
      .actions(singletonList(actionProperty))
      .conditions(singletonList(condition))
      .listenerArn(networkOutputParameters.getHttpListenerArn())
      .priority(2)
      .build();

    LogGroup logGroup = LogGroup.Builder.create(this, "ecsLogGroup")
      .logGroupName(applicationEnvironment.prefix("logs"))
      .retention(serviceInputParameters.logRetention)
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

    Role.Builder roleBuilder = Role.Builder.create(this, "ecsTaskRole")
      .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
      .path("/");

    if (!serviceInputParameters.taskRolePolicyStatements.isEmpty()) {
      roleBuilder.inlinePolicies(Map.of(
        applicationEnvironment.prefix("ecsTaskRolePolicy"),
        PolicyDocument.Builder.create()
          .statements(serviceInputParameters.taskRolePolicyStatements)
          .build()))
        .build();
    }

    Role ecsTaskRole = roleBuilder.build();

    String dockerRepositoryUrl = null;
    if (serviceInputParameters.dockerImageSource.isEcrSource()) {
      IRepository dockerRepository = Repository.fromRepositoryName(this, "ecrRepository", serviceInputParameters.dockerImageSource.getDockerRepositoryName());
      dockerRepository.grantPull(ecsTaskExecutionRole);
      dockerRepositoryUrl = dockerRepository.repositoryUriForTag(serviceInputParameters.dockerImageSource.getDockerImageTag());
    } else {
      dockerRepositoryUrl = serviceInputParameters.dockerImageSource.dockerImageUrl;
    }

    CfnTaskDefinition.ContainerDefinitionProperty container = CfnTaskDefinition.ContainerDefinitionProperty.builder()
      .name(containerName(applicationEnvironment))
      .cpu(serviceInputParameters.cpu)
      .memory(serviceInputParameters.memory)
      .image(dockerRepositoryUrl)
      .logConfiguration(CfnTaskDefinition.LogConfigurationProperty.builder()
        .logDriver("awslogs")
        .options(Map.of(
          "awslogs-group", logGroup.getLogGroupName(),
          "awslogs-region", awsEnvironment.getRegion(),
          "awslogs-stream-prefix", applicationEnvironment.prefix("stream"),
          "awslogs-multiline-pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        .build())
      .portMappings(singletonList(CfnTaskDefinition.PortMappingProperty.builder()
        .containerPort(serviceInputParameters.containerPort)
        .build()))
      .environment(toKeyValuePairs(serviceInputParameters.environmentVariables))
      .build();

    CfnTaskDefinition taskDefinition = CfnTaskDefinition.Builder.create(this, "taskDefinition")
      // skipped family
      .cpu(String.valueOf(serviceInputParameters.cpu))
      .memory(String.valueOf(serviceInputParameters.memory))
      .networkMode("awsvpc")
      .requiresCompatibilities(singletonList("FARGATE"))
      .executionRoleArn(ecsTaskExecutionRole.getRoleArn())
      .taskRoleArn(ecsTaskRole.getRoleArn())
      .containerDefinitions(singletonList(container))
      .build();

    ecsSecurityGroup = CfnSecurityGroup.Builder.create(this, "ecsSecurityGroup")
      .vpcId(networkOutputParameters.getVpcId())
      .groupDescription("SecurityGroup for the ECS containers")
      .build();

    // allow ECS containers to access each other
    CfnSecurityGroupIngress ecsIngressFromSelf = CfnSecurityGroupIngress.Builder.create(this, "ecsIngressFromSelf")
      .ipProtocol("-1")
      .sourceSecurityGroupId(ecsSecurityGroup.getAttrGroupId())
      .groupId(ecsSecurityGroup.getAttrGroupId())
      .build();

    // allow the load balancer to access the containers
    CfnSecurityGroupIngress ecsIngressFromLoadbalancer = CfnSecurityGroupIngress.Builder.create(this, "ecsIngressFromLoadbalancer")
      .ipProtocol("-1")
      .sourceSecurityGroupId(networkOutputParameters.getLoadbalancerSecurityGroupId())
      .groupId(ecsSecurityGroup.getAttrGroupId())
      .build();

    allowIngressFromEcs(serviceInputParameters.securityGroupIdsToGrantIngressFromEcs, ecsSecurityGroup);

    CfnService service = CfnService.Builder.create(this, "ecsService")
      .cluster(networkOutputParameters.getEcsClusterName())
      .launchType("FARGATE")
      .deploymentConfiguration(CfnService.DeploymentConfigurationProperty.builder()
        .maximumPercent(serviceInputParameters.maximumInstancesPercent)
        .minimumHealthyPercent(serviceInputParameters.minimumHealthyInstancesPercent)
        .build())
      .desiredCount(serviceInputParameters.desiredInstancesCount)
      .taskDefinition(taskDefinition.getRef())
      .loadBalancers(singletonList(CfnService.LoadBalancerProperty.builder()
        .containerName(containerName(applicationEnvironment))
        .containerPort(serviceInputParameters.containerPort)
        .targetGroupArn(targetGroup.getRef())
        .build()))
      .networkConfiguration(CfnService.NetworkConfigurationProperty.builder()
        .awsvpcConfiguration(CfnService.AwsVpcConfigurationProperty.builder()
          .assignPublicIp("ENABLED")
          .securityGroups(singletonList(ecsSecurityGroup.getAttrGroupId()))
          .subnets(networkOutputParameters.getPublicSubnets())
          .build())
        .build())
      .build();

    // Adding an explicit dependency from the service to the listeners to avoid "has no load balancer associated" error
    // (see https://stackoverflow.com/questions/61250772/how-can-i-create-a-dependson-relation-between-ec2-and-rds-using-aws-cdk).
    if (httpsListenerArn.isPresent()) {
      service.addDependsOn(httpsListenerRule);
    }
    service.addDependsOn(httpListenerRule);

    applicationEnvironment.tag(this);

    createOutputParameters(applicationEnvironment.getEnvironmentName());
  }

  @NotNull
  private static String createParameterName(String environmentName, String parameterName) {
    return environmentName + "-Service-" + parameterName;
  }

  private static String getEcsSecurityGroupIdFromParameterStore(Construct scope, String environmentName) {
    return StringParameter.fromStringParameterName(scope, PARAMETER_ECS_SECURITY_GROUP_ID, createParameterName(environmentName, PARAMETER_ECS_SECURITY_GROUP_ID))
      .getStringValue();
  }

  /**
   * Collects the output parameters of an already deployed {@link Service} construct from the parameter store. This requires
   * that a {@link Service} construct has been deployed previously. If you want to access the parameters from the same
   * stack that the {@link Service} construct is in, use the plain {@link #getOutputParameters()} method.
   *
   * @param scope           the construct in which we need the output parameters
   * @param environmentName the name of the environment for which to load the output parameters. The deployed {@link Network}
   *                        construct must have been deployed into this environment.
   */
  public static Service.ServiceOutputParameters getOutputParametersFromParameterStore(Construct scope, String environmentName) {
    return new Service.ServiceOutputParameters(
      getEcsSecurityGroupIdFromParameterStore(scope, environmentName)
    );
  }

  public CfnSecurityGroup getEcsSecurityGroup() {
    return ecsSecurityGroup;
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

  /**
   * Stores output parameters of this stack in the parameter store so they can be retrieved by other stacks
   * or constructs as necessary.
   */
  private void createOutputParameters(String environmentName) {

    StringParameter.Builder.create(this, PARAMETER_ECS_SECURITY_GROUP_ID)
      .parameterName(createParameterName(environmentName, PARAMETER_ECS_SECURITY_GROUP_ID))
      .stringValue(this.ecsSecurityGroup.getAttrGroupId())
      .build();
  }

  /**
   * Collects the output parameters of this construct that might be of interest to other constructs.
   */
  public Service.ServiceOutputParameters getOutputParameters() {
    return new Service.ServiceOutputParameters(
      this.ecsSecurityGroup.getAttrGroupId()
    );
  }

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

  public static class ServiceInputParameters {
    private final DockerImageSource dockerImageSource;
    private final Map<String, String> environmentVariables;
    private final List<String> securityGroupIdsToGrantIngressFromEcs;
    private List<PolicyStatement> taskRolePolicyStatements = new ArrayList<>();
    private int healthCheckIntervalSeconds = 15;
    private String healthCheckPath = "/";
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
    private boolean stickySessionsEnabled = false;

    /**
     * Knobs and dials you can configure to run a Docker image in an ECS service. The default values are set in a way
     * to work out of the box with a Spring Boot application.
     *
     * @param dockerImageSource                     the source from where to load the Docker image that we want to deploy.
     * @param securityGroupIdsToGrantIngressFromEcs Ids of the security groups that the ECS containers should be granted access to.
     * @param environmentVariables                  the environment variables provided to the Java runtime within the Docker containers.
     */
    public ServiceInputParameters(
      DockerImageSource dockerImageSource,
      List<String> securityGroupIdsToGrantIngressFromEcs,
      Map<String, String> environmentVariables) {
      this.dockerImageSource = dockerImageSource;
      this.environmentVariables = environmentVariables;
      this.securityGroupIdsToGrantIngressFromEcs = securityGroupIdsToGrantIngressFromEcs;
    }

    /**
     * Knobs and dials you can configure to run a Docker image in an ECS service. The default values are set in a way
     * to work out of the box with a Spring Boot application.
     *
     * @param dockerImageSource    the source from where to load the Docker image that we want to deploy.
     * @param environmentVariables the environment variables provided to the Java runtime within the Docker containers.
     */
    public ServiceInputParameters(
      DockerImageSource dockerImageSource,
      Map<String, String> environmentVariables) {
      this.dockerImageSource = dockerImageSource;
      this.environmentVariables = environmentVariables;
      this.securityGroupIdsToGrantIngressFromEcs = Collections.emptyList();
    }

    /**
     * The interval to wait between two health checks.
     * <p>
     * Default: 15.
     */
    public ServiceInputParameters withHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) {
      this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
      return this;
    }

    /**
     * The path of the health check URL.
     * <p>
     * Default: "/actuator/health".
     */
    public ServiceInputParameters withHealthCheckPath(String healthCheckPath) {
      Objects.requireNonNull(healthCheckPath);
      this.healthCheckPath = healthCheckPath;
      return this;
    }

    /**
     * The port the application listens on within the container.
     * <p>
     * Default: 8080.
     */
    public ServiceInputParameters withContainerPort(int containerPort) {
      Objects.requireNonNull(containerPort);
      this.containerPort = containerPort;
      return this;
    }

    /**
     * The protocol to access the application within the container. Default: "HTTP".
     */
    public ServiceInputParameters withContainerProtocol(String containerProtocol) {
      Objects.requireNonNull(containerProtocol);
      this.containerProtocol = containerProtocol;
      return this;
    }

    /**
     * The number of seconds to wait for a response until a health check is deemed unsuccessful.
     * <p>
     * Default: 5.
     */
    public ServiceInputParameters withHealthCheckTimeoutSeconds(int healthCheckTimeoutSeconds) {
      this.healthCheckTimeoutSeconds = healthCheckTimeoutSeconds;
      return this;
    }

    /**
     * The number of consecutive successful health checks after which an instance is declared healthy.
     * <p>
     * Default: 2.
     */
    public ServiceInputParameters withHealthyThresholdCount(int healthyThresholdCount) {
      this.healthyThresholdCount = healthyThresholdCount;
      return this;
    }

    /**
     * The number of consecutive unsuccessful health checks after which an instance is declared unhealthy.
     * <p>
     * Default: 8.
     */
    public ServiceInputParameters withUnhealthyThresholdCount(int unhealthyThresholdCount) {
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
    public ServiceInputParameters withCpu(int cpu) {
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
    public ServiceInputParameters withMemory(int memory) {
      this.memory = memory;
      return this;
    }

    /**
     * The duration the logs of the application should be retained.
     * <p>
     * Default: 1 week.
     */
    public ServiceInputParameters withLogRetention(RetentionDays logRetention) {
      Objects.requireNonNull(logRetention);
      this.logRetention = logRetention;
      return this;
    }

    /**
     * The number of instances that should run in parallel behind the load balancer.
     * <p>
     * Default: 2.
     */
    public ServiceInputParameters withDesiredInstances(int desiredInstances) {
      this.desiredInstancesCount = desiredInstances;
      return this;
    }

    /**
     * The maximum percentage in relation to the desired instances that may be running at the same time
     * (for example during deployments).
     * <p>
     * Default: 200.
     */
    public ServiceInputParameters withMaximumInstancesPercent(int maximumInstancesPercent) {
      this.maximumInstancesPercent = maximumInstancesPercent;
      return this;
    }

    /**
     * The minimum percentage in relation to the desired instances that must be running at the same time
     * (for example during deployments).
     * <p>
     * Default: 50.
     */
    public ServiceInputParameters withMinimumHealthyInstancesPercent(int minimumHealthyInstancesPercent) {
      this.minimumHealthyInstancesPercent = minimumHealthyInstancesPercent;
      return this;
    }

    /**
     * The list of PolicyStatement objects that define which operations this service can perform on other
     * AWS resources (for example ALLOW sqs:GetQueueUrl for all SQS queues).
     * <p>
     * Default: none (empty list).
     */
    public ServiceInputParameters withTaskRolePolicyStatements(List<PolicyStatement> taskRolePolicyStatements) {
      this.taskRolePolicyStatements = taskRolePolicyStatements;
      return this;
    }

    /**
     * Disable or enable sticky sessions for the the load balancer.
     * <p>
     * Default: false.
     */
    public ServiceInputParameters withStickySessionsEnabled(boolean stickySessionsEnabled) {
      this.stickySessionsEnabled = stickySessionsEnabled;
      return this;
    }

  }

  public static class ServiceOutputParameters {

    private final String ecsSecurityGroupId;

    public ServiceOutputParameters(
      String ecsSecurityGroupId
    ) {
      this.ecsSecurityGroupId = ecsSecurityGroupId;
    }

    /**
     * The ID of the ECS security group.
     */
    public String getEcsSecurityGroupId() {
      return this.ecsSecurityGroupId;
    }
  }
}
