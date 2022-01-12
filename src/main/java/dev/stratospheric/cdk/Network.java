package dev.stratospheric.cdk;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.ec2.CfnSecurityGroupIngress;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ICluster;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetGroupsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListenerRule;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListenerRuleProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.IApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.IApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.IApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.IListenerCertificate;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerCertificate;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerCondition;
import software.amazon.awscdk.services.elasticloadbalancingv2.RedirectOptions;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

import static java.util.Arrays.asList;

/**
 * Creates a base network for an application served by ECS. The network stack contains a VPC,
 * two public and two isolated subnets, an ECS cluster, and an internet-facing load balancer with an HTTP and
 * an optional HTTPS listener. The listeners can be used in other stacks to attach to an ECS service,
 * for instance.
 * <p>
 * The construct exposes some output parameters to be used by other constructs. You can access them by:
 * <ul>
 *     <li>calling {@link #getOutputParameters()} to load the output parameters from a {@link Network} instance</li>
 *     <li>calling the static method {@link #getOutputParametersFromParameterStore(Construct, String)} to load them from the
 *     parameter store (requires that a {@link Network} construct has already been provisioned in a previous stack) </li>
 * </ul>
 */
public class Network extends Construct {

  private static final String PARAMETER_VPC_ID = "vpcId";
  private static final String PARAMETER_HTTP_LISTENER = "httpListenerArn";
  private static final String PARAMETER_HTTPS_LISTENER = "httpsListenerArn";
  private static final String PARAMETER_LOADBALANCER_SECURITY_GROUP_ID = "loadBalancerSecurityGroupId";
  private static final String PARAMETER_ECS_CLUSTER_NAME = "ecsClusterName";
  private static final String PARAMETER_ISOLATED_SUBNET_ONE = "isolatedSubnetIdOne";
  private static final String PARAMETER_ISOLATED_SUBNET_TWO = "isolatedSubnetIdTwo";
  private static final String PARAMETER_PUBLIC_SUBNET_ONE = "publicSubnetIdOne";
  private static final String PARAMETER_PUBLIC_SUBNET_TWO = "publicSubnetIdTwo";
  private static final String PARAMETER_AVAILABILITY_ZONE_ONE = "availabilityZoneOne";
  private static final String PARAMETER_AVAILABILITY_ZONE_TWO = "availabilityZoneTwo";
  private static final String PARAMETER_LOAD_BALANCER_ARN = "loadBalancerArn";
  private static final String PARAMETER_LOAD_BALANCER_DNS_NAME = "loadBalancerDnsName";
  private static final String PARAMETER_LOAD_BALANCER_HOSTED_ZONE_ID = "loadBalancerCanonicalHostedZoneId";
  private final IVpc vpc;
  private final String environmentName;
  private final ICluster ecsCluster;
  private IApplicationListener httpListener;
  private IApplicationListener httpsListener;
  private ISecurityGroup loadbalancerSecurityGroup;
  private IApplicationLoadBalancer loadBalancer;

  public Network(
    final Construct scope,
    final String id,
    final Environment environment,
    final String environmentName,
    final NetworkInputParameters networkInputParameters) {

    super(scope, id);

    this.environmentName = environmentName;

    this.vpc = createVpc(environmentName);

    // We're preparing an ECS cluster in the network stack and using it in the ECS stack.
    // If the cluster were in the ECS stack, it would interfere with deleting the ECS stack,
    // because an ECS service would still depend on it.
    this.ecsCluster = Cluster.Builder.create(this, "cluster")
      .vpc(this.vpc)
      .clusterName(prefixWithEnvironmentName("ecsCluster"))
      .build();

    createLoadBalancer(vpc, networkInputParameters.getSslCertificateArn());

    Tags.of(this).add("environment", environmentName);
  }

  /**
   * Collects the output parameters of an already deployed {@link Network} construct from the parameter store. This requires
   * that a {@link Network} construct has been deployed previously. If you want to access the parameters from the same
   * stack that the {@link Network} construct is in, use the plain {@link #getOutputParameters()} method.
   *
   * @param scope           the construct in which we need the output parameters
   * @param environmentName the name of the environment for which to load the output parameters. The deployed {@link Network}
   *                        construct must have been deployed into this environment.
   */
  public static NetworkOutputParameters getOutputParametersFromParameterStore(Construct scope, String environmentName) {
    return new NetworkOutputParameters(
      getVpcIdFromParameterStore(scope, environmentName),
      getHttpListenerArnFromParameterStore(scope, environmentName),
      getHttpsListenerArnFromParameterStore(scope, environmentName),
      getLoadbalancerSecurityGroupIdFromParameterStore(scope, environmentName),
      getEcsClusterNameFromParameterStore(scope, environmentName),
      getIsolatedSubnetsFromParameterStore(scope, environmentName),
      getPublicSubnetsFromParameterStore(scope, environmentName),
      getAvailabilityZonesFromParameterStore(scope, environmentName),
      getLoadBalancerArnFromParameterStore(scope, environmentName),
      getLoadBalancerDnsNameFromParameterStore(scope,environmentName),
      getLoadBalancerCanonicalHostedZoneIdFromParameterStore(scope,environmentName)
    );
  }

  @NotNull
  private static String createParameterName(String environmentName, String parameterName) {
    return environmentName + "-Network-" + parameterName;
  }

  private static String getVpcIdFromParameterStore(Construct scope, String environmentName) {
    return StringParameter.fromStringParameterName(scope, PARAMETER_VPC_ID, createParameterName(environmentName, PARAMETER_VPC_ID))
      .getStringValue();
  }

  private static String getHttpListenerArnFromParameterStore(Construct scope, String environmentName) {
    return StringParameter.fromStringParameterName(scope, PARAMETER_HTTP_LISTENER, createParameterName(environmentName, PARAMETER_HTTP_LISTENER))
      .getStringValue();
  }

  private static Optional<String> getHttpsListenerArnFromParameterStore(Construct scope, String environmentName) {
    String value = StringParameter.fromStringParameterName(scope, PARAMETER_HTTPS_LISTENER, createParameterName(environmentName, PARAMETER_HTTPS_LISTENER))
      .getStringValue();
    if ("null".equals(value)) {
      return Optional.empty();
    } else {
      return Optional.ofNullable(value);
    }
  }

  private static String getLoadbalancerSecurityGroupIdFromParameterStore(Construct scope, String environmentName) {
    return StringParameter.fromStringParameterName(scope, PARAMETER_LOADBALANCER_SECURITY_GROUP_ID, createParameterName(environmentName, PARAMETER_LOADBALANCER_SECURITY_GROUP_ID))
      .getStringValue();
  }

  private static String getEcsClusterNameFromParameterStore(Construct scope, String environmentName) {
    return StringParameter.fromStringParameterName(scope, PARAMETER_ECS_CLUSTER_NAME, createParameterName(environmentName, PARAMETER_ECS_CLUSTER_NAME))
      .getStringValue();
  }

  private static List<String> getIsolatedSubnetsFromParameterStore(Construct scope, String environmentName) {

    String subnetOneId = StringParameter.fromStringParameterName(scope, PARAMETER_ISOLATED_SUBNET_ONE, createParameterName(environmentName, PARAMETER_ISOLATED_SUBNET_ONE))
      .getStringValue();

    String subnetTwoId = StringParameter.fromStringParameterName(scope, PARAMETER_ISOLATED_SUBNET_TWO, createParameterName(environmentName, PARAMETER_ISOLATED_SUBNET_TWO))
      .getStringValue();

    return asList(subnetOneId, subnetTwoId);
  }

  private static List<String> getPublicSubnetsFromParameterStore(Construct scope, String environmentName) {

    String subnetOneId = StringParameter.fromStringParameterName(scope, PARAMETER_PUBLIC_SUBNET_ONE, createParameterName(environmentName, PARAMETER_PUBLIC_SUBNET_ONE))
      .getStringValue();

    String subnetTwoId = StringParameter.fromStringParameterName(scope, PARAMETER_PUBLIC_SUBNET_TWO, createParameterName(environmentName, PARAMETER_PUBLIC_SUBNET_TWO))
      .getStringValue();

    return asList(subnetOneId, subnetTwoId);
  }

  private static List<String> getAvailabilityZonesFromParameterStore(Construct scope, String environmentName) {

    String availabilityZoneOne = StringParameter.fromStringParameterName(scope, PARAMETER_AVAILABILITY_ZONE_ONE, createParameterName(environmentName, PARAMETER_AVAILABILITY_ZONE_ONE))
      .getStringValue();

    String availabilityZoneTwo = StringParameter.fromStringParameterName(scope, PARAMETER_AVAILABILITY_ZONE_TWO, createParameterName(environmentName, PARAMETER_AVAILABILITY_ZONE_TWO))
      .getStringValue();

    return asList(availabilityZoneOne, availabilityZoneTwo);
  }

  private static String getLoadBalancerArnFromParameterStore(Construct scope, String environmentName) {
    return StringParameter.fromStringParameterName(scope, PARAMETER_LOAD_BALANCER_ARN, createParameterName(environmentName, PARAMETER_LOAD_BALANCER_ARN))
      .getStringValue();
  }

  private static String getLoadBalancerDnsNameFromParameterStore(Construct scope, String environmentName) {
    return StringParameter.fromStringParameterName(scope, PARAMETER_LOAD_BALANCER_DNS_NAME, createParameterName(environmentName, PARAMETER_LOAD_BALANCER_DNS_NAME))
      .getStringValue();
  }

  private static String getLoadBalancerCanonicalHostedZoneIdFromParameterStore(Construct scope, String environmentName) {
    return StringParameter.fromStringParameterName(scope, PARAMETER_LOAD_BALANCER_HOSTED_ZONE_ID, createParameterName(environmentName, PARAMETER_LOAD_BALANCER_HOSTED_ZONE_ID))
      .getStringValue();
  }

  public IVpc getVpc() {
    return vpc;
  }

  public IApplicationListener getHttpListener() {
    return httpListener;
  }

  /**
   * The load balancer's HTTPS listener. May be null if the load balancer is configured for HTTP only!
   */
  @Nullable
  public IApplicationListener getHttpsListener() {
    return httpsListener;
  }

  public ISecurityGroup getLoadbalancerSecurityGroup() {
    return loadbalancerSecurityGroup;
  }

  public IApplicationLoadBalancer getLoadBalancer() {
    return loadBalancer;
  }

  public ICluster getEcsCluster() {
    return ecsCluster;
  }

  /**
   * Creates a VPC with 2 private and 2 public subnets in different AZs and without a NAT gateway
   * (i.e. the private subnets have no access to the internet).
   */
  private IVpc createVpc(final String environmentName) {

    SubnetConfiguration publicSubnets = SubnetConfiguration.builder()
      .subnetType(SubnetType.PUBLIC)
      .name(prefixWithEnvironmentName("publicSubnet"))
      .build();

    SubnetConfiguration isolatedSubnets = SubnetConfiguration.builder()
      .subnetType(SubnetType.PRIVATE_ISOLATED)
      .name(prefixWithEnvironmentName("isolatedSubnet"))
      .build();

    return Vpc.Builder.create(this, "vpc")
      .natGateways(0)
      .maxAzs(2)
      .subnetConfiguration(asList(
        publicSubnets,
        isolatedSubnets
      ))
      .build();
  }

  private String prefixWithEnvironmentName(String string) {
    return this.environmentName + "-" + string;
  }

  /**
   * Creates a load balancer that accepts HTTP and HTTPS requests from the internet and puts it into
   * the VPC's public subnets.
   */
  private void createLoadBalancer(
    final IVpc vpc,
    final Optional<String> sslCertificateArn) {

    loadbalancerSecurityGroup = SecurityGroup.Builder.create(this, "loadbalancerSecurityGroup")
      .securityGroupName(prefixWithEnvironmentName("loadbalancerSecurityGroup"))
      .description("Public access to the load balancer.")
      .vpc(vpc)
      .build();

    CfnSecurityGroupIngress ingressFromPublic = CfnSecurityGroupIngress.Builder.create(this, "ingressToLoadbalancer")
      .groupId(loadbalancerSecurityGroup.getSecurityGroupId())
      .cidrIp("0.0.0.0/0")
      .ipProtocol("-1")
      .build();

    loadBalancer = ApplicationLoadBalancer.Builder.create(this, "loadbalancer")
      .loadBalancerName(prefixWithEnvironmentName("loadbalancer"))
      .vpc(vpc)
      .internetFacing(true)
      .securityGroup(loadbalancerSecurityGroup)
      .build();

    IApplicationTargetGroup dummyTargetGroup = ApplicationTargetGroup.Builder.create(this, "defaultTargetGroup")
      .vpc(vpc)
      .port(8080)
      .protocol(ApplicationProtocol.HTTP)
      .targetGroupName(prefixWithEnvironmentName("no-op-targetGroup"))
      .targetType(TargetType.IP)
      .build();

    httpListener = loadBalancer.addListener("httpListener", BaseApplicationListenerProps.builder()
      .port(80)
      .protocol(ApplicationProtocol.HTTP)
      .open(true)
      .build());

    httpListener.addTargetGroups("http-defaultTargetGroup", AddApplicationTargetGroupsProps.builder()
      .targetGroups(Collections.singletonList(dummyTargetGroup))
      .build());

    if (sslCertificateArn.isPresent()) {
      IListenerCertificate certificate = ListenerCertificate.fromArn(sslCertificateArn.get());
      httpsListener = loadBalancer.addListener("httpsListener", BaseApplicationListenerProps.builder()
        .port(443)
        .protocol(ApplicationProtocol.HTTPS)
        .certificates(Collections.singletonList(certificate))
        .open(true)
        .build());

      httpsListener.addTargetGroups("https-defaultTargetGroup", AddApplicationTargetGroupsProps.builder()
        .targetGroups(Collections.singletonList(dummyTargetGroup))
        .build());

      ListenerAction redirectAction = ListenerAction.redirect(
        RedirectOptions.builder()
          .protocol("HTTPS")
          .port("443")
          .build()
      );
      ApplicationListenerRule applicationListenerRule = new ApplicationListenerRule(
        this,
        "HttpListenerRule",
        ApplicationListenerRuleProps.builder()
          .listener(httpListener)
          .priority(1)
          .conditions(List.of(ListenerCondition.pathPatterns(List.of("*"))))
          .action(redirectAction)
          .build()
      );
    }

    createOutputParameters();
  }

  /**
   * Stores output parameters of this stack in the parameter store so they can be retrieved by other stacks
   * or constructs as necessary.
   */
  private void createOutputParameters() {

    StringParameter vpcId = StringParameter.Builder.create(this, "vpcId")
      .parameterName(createParameterName(environmentName, PARAMETER_VPC_ID))
      .stringValue(this.vpc.getVpcId())
      .build();

    StringParameter httpListener = StringParameter.Builder.create(this, "httpListener")
      .parameterName(createParameterName(environmentName, PARAMETER_HTTP_LISTENER))
      .stringValue(this.httpListener.getListenerArn())
      .build();

    if (this.httpsListener != null) {
      StringParameter httpsListener = StringParameter.Builder.create(this, "httpsListener")
        .parameterName(createParameterName(environmentName, PARAMETER_HTTPS_LISTENER))
        .stringValue(this.httpsListener.getListenerArn())
        .build();
    } else {
      StringParameter httpsListener = StringParameter.Builder.create(this, "httpsListener")
        .parameterName(createParameterName(environmentName, PARAMETER_HTTPS_LISTENER))
        .stringValue("null")
        .build();
    }

    StringParameter loadbalancerSecurityGroup = StringParameter.Builder.create(this, "loadBalancerSecurityGroupId")
      .parameterName(createParameterName(environmentName, PARAMETER_LOADBALANCER_SECURITY_GROUP_ID))
      .stringValue(this.loadbalancerSecurityGroup.getSecurityGroupId())
      .build();

    StringParameter cluster = StringParameter.Builder.create(this, "ecsClusterName")
      .parameterName(createParameterName(environmentName, PARAMETER_ECS_CLUSTER_NAME))
      .stringValue(this.ecsCluster.getClusterName())
      .build();

    // I would have liked to use StringListParameter to store a list of AZs, but it's currently broken (https://github.com/aws/aws-cdk/issues/3586).
    StringParameter availabilityZoneOne = StringParameter.Builder.create(this, "availabilityZoneOne")
      .parameterName(createParameterName(environmentName, PARAMETER_AVAILABILITY_ZONE_ONE))
      .stringValue(vpc.getAvailabilityZones().get(0))
      .build();

    StringParameter availabilityZoneTwo = StringParameter.Builder.create(this, "availabilityZoneTwo")
      .parameterName(createParameterName(environmentName, PARAMETER_AVAILABILITY_ZONE_TWO))
      .stringValue(vpc.getAvailabilityZones().get(1))
      .build();

    // I would have liked to use StringListParameter to store a list of AZs, but it's currently broken (https://github.com/aws/aws-cdk/issues/3586).
    StringParameter isolatedSubnetOne = StringParameter.Builder.create(this, "isolatedSubnetOne")
      .parameterName(createParameterName(environmentName, PARAMETER_ISOLATED_SUBNET_ONE))
      .stringValue(this.vpc.getIsolatedSubnets().get(0).getSubnetId())
      .build();

    StringParameter isolatedSubnetTwo = StringParameter.Builder.create(this, "isolatedSubnetTwo")
      .parameterName(createParameterName(environmentName, PARAMETER_ISOLATED_SUBNET_TWO))
      .stringValue(this.vpc.getIsolatedSubnets().get(1).getSubnetId())
      .build();

    // I would have liked to use StringListParameter to store a list of AZs, but it's currently broken (https://github.com/aws/aws-cdk/issues/3586).
    StringParameter publicSubnetOne = StringParameter.Builder.create(this, "publicSubnetOne")
      .parameterName(createParameterName(environmentName, PARAMETER_PUBLIC_SUBNET_ONE))
      .stringValue(this.vpc.getPublicSubnets().get(0).getSubnetId())
      .build();

    StringParameter publicSubnetTwo = StringParameter.Builder.create(this, "publicSubnetTwo")
      .parameterName(createParameterName(environmentName, PARAMETER_PUBLIC_SUBNET_TWO))
      .stringValue(this.vpc.getPublicSubnets().get(1).getSubnetId())
      .build();


    StringParameter loadBalancerArn = StringParameter.Builder.create(this, "loadBalancerArn")
      .parameterName(createParameterName(environmentName, PARAMETER_LOAD_BALANCER_ARN))
      .stringValue(this.loadBalancer.getLoadBalancerArn())
      .build();

    StringParameter loadBalancerDnsName = StringParameter.Builder.create(this, "loadBalancerDnsName")
      .parameterName(createParameterName(environmentName, PARAMETER_LOAD_BALANCER_DNS_NAME))
      .stringValue(this.loadBalancer.getLoadBalancerDnsName())
      .build();

    StringParameter loadBalancerCanonicalHostedZoneId = StringParameter.Builder.create(this, "loadBalancerCanonicalHostedZoneId")
      .parameterName(createParameterName(environmentName, PARAMETER_LOAD_BALANCER_HOSTED_ZONE_ID))
      .stringValue(this.loadBalancer.getLoadBalancerCanonicalHostedZoneId())
      .build();
  }

  /**
   * Collects the output parameters of this construct that might be of interest to other constructs.
   */
  public NetworkOutputParameters getOutputParameters() {
    return new NetworkOutputParameters(
      this.vpc.getVpcId(),
      this.httpListener.getListenerArn(),
      this.httpsListener != null ? Optional.of(this.httpsListener.getListenerArn()) : Optional.empty(),
      this.loadbalancerSecurityGroup.getSecurityGroupId(),
      this.ecsCluster.getClusterName(),
      this.vpc.getIsolatedSubnets().stream().map(ISubnet::getSubnetId).collect(Collectors.toList()),
      this.vpc.getPublicSubnets().stream().map(ISubnet::getSubnetId).collect(Collectors.toList()),
      this.vpc.getAvailabilityZones(),
      this.loadBalancer.getLoadBalancerArn(),
      this.loadBalancer.getLoadBalancerDnsName(),
      this.loadBalancer.getLoadBalancerCanonicalHostedZoneId()
    );
  }

  public static class NetworkInputParameters {
    private Optional<String> sslCertificateArn;

    /**
     * @param sslCertificateArn the ARN of the SSL certificate that the load balancer will use
     *                          to terminate HTTPS communication. If no SSL certificate is passed,
     *                          the load balancer will only listen to plain HTTP.
     * @deprecated use {@link #withSslCertificateArn(String)} instead
     */
    @Deprecated
    public NetworkInputParameters(String sslCertificateArn) {
      this.sslCertificateArn = Optional.ofNullable(sslCertificateArn);
    }

    public NetworkInputParameters() {
      this.sslCertificateArn = Optional.empty();
    }

    public NetworkInputParameters withSslCertificateArn(String sslCertificateArn){
      Objects.requireNonNull(sslCertificateArn);
      this.sslCertificateArn = Optional.of(sslCertificateArn);
      return this;
    }

    public Optional<String> getSslCertificateArn() {
      return sslCertificateArn;
    }
  }

  public static class NetworkOutputParameters {

    private final String vpcId;
    private final String httpListenerArn;
    private final Optional<String> httpsListenerArn;
    private final String loadbalancerSecurityGroupId;
    private final String ecsClusterName;
    private final List<String> isolatedSubnets;
    private final List<String> publicSubnets;
    private final List<String> availabilityZones;
    private final String loadBalancerArn;
    private final String loadBalancerDnsName;
    private final String loadBalancerCanonicalHostedZoneId;

    public NetworkOutputParameters(
      String vpcId,
      String httpListenerArn,
      Optional<String> httpsListenerArn,
      String loadbalancerSecurityGroupId,
      String ecsClusterName,
      List<String> isolatedSubnets,
      List<String> publicSubnets,
      List<String> availabilityZones,
      String loadBalancerArn,
      String loadBalancerDnsName,
      String loadBalancerCanonicalHostedZoneId
    ) {
      this.vpcId = vpcId;
      this.httpListenerArn = httpListenerArn;
      this.httpsListenerArn = httpsListenerArn;
      this.loadbalancerSecurityGroupId = loadbalancerSecurityGroupId;
      this.ecsClusterName = ecsClusterName;
      this.isolatedSubnets = isolatedSubnets;
      this.publicSubnets = publicSubnets;
      this.availabilityZones = availabilityZones;
      this.loadBalancerArn = loadBalancerArn;
      this.loadBalancerDnsName = loadBalancerDnsName;
      this.loadBalancerCanonicalHostedZoneId = loadBalancerCanonicalHostedZoneId;
    }

    /**
     * The VPC ID.
     */
    public String getVpcId() {
      return this.vpcId;
    }

    /**
     * The ARN of the HTTP listener.
     */
    public String getHttpListenerArn() {
      return this.httpListenerArn;
    }

    /**
     * The ARN of the HTTPS listener.
     */
    public Optional<String> getHttpsListenerArn() {
      return this.httpsListenerArn;
    }

    /**
     * The ID of the load balancer's security group.
     */
    public String getLoadbalancerSecurityGroupId() {
      return this.loadbalancerSecurityGroupId;
    }

    /**
     * The name of the ECS cluster.
     */
    public String getEcsClusterName() {
      return this.ecsClusterName;
    }

    /**
     * The IDs of the isolated subnets.
     */
    public List<String> getIsolatedSubnets() {
      return this.isolatedSubnets;
    }

    /**
     * The IDs of the public subnets.
     */
    public List<String> getPublicSubnets() {
      return this.publicSubnets;
    }

    /**
     * The names of the availability zones of the VPC.
     */
    public List<String> getAvailabilityZones() {
      return this.availabilityZones;
    }

    /**
     * The ARN of the load balancer.
     */
    public String getLoadBalancerArn() {
      return this.loadBalancerArn;
    }

    /**
     * The DNS name of the load balancer.
     */
    public String getLoadBalancerDnsName() {
      return loadBalancerDnsName;
    }

    /**
     * The hosted zone ID of the load balancer.
     */
    public String getLoadBalancerCanonicalHostedZoneId() {
      return loadBalancerCanonicalHostedZoneId;
    }
  }
}
