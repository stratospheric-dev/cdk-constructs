package dev.stratospheric.cdk;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ICluster;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.ssm.StringParameter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static java.util.Arrays.asList;

/**
 * Creates a base network for an application served by ECS. The network stack contains a VPC,
 * two public and two isolated subnets, an ECS cluster, and an internet-facing load balancer with an HTTP and
 * an HTTPS listener. The listeners can be used in other stacks to attach to an ECS service,
 * for instance.
 * <p>
 * The stack exposes the following output parameters in the SSM parameter store to be used in other stacks:
 * <ul>
 *     <li><strong>&lt;environmentName&gt;-Network-vpcId:</strong> ID of the VPC created in this stack</li>
 *     <li><strong>&lt;environmentName&gt;-Network-httpListenerArn:</strong> ARN of the load balancer's HTTP listener</li>
 *     <li><strong>&lt;environmentName&gt;-Network-httpsListenerArn:</strong> ARN of the load balancer's HTTPS listener</li>
 *     <li><strong>&lt;environmentName&gt;-Network-loadBalancerSecurityGroupId:</strong> ID of the load balancer's security group</li>
 *     <li><strong>&lt;environmentName&gt;-Network-ecsClusterName:</strong> name of the ECS cluster created in this stack</li>
 *     <li><strong>&lt;environmentName&gt;-Network-availabilityZoneOne:</strong> name of the first AZ of this stack's VPC</li>
 *     <li><strong>&lt;environmentName&gt;-Network-availabilityZoneTwo:</strong> name of the second AZ of this stack's VPC</li>
 *     <li><strong>&lt;environmentName&gt;-Network-isolatedSubnetIdOne:</strong> ID of the first isolated subnet of this stack's VPC</li>
 *     <li><strong>&lt;environmentName&gt;-Network-isolatedSubnetIdTwo:</strong> ID of the second isolated subnet of this stack's VPC</li>
 *     <li><strong>&lt;environmentName&gt;-Network-publicSubnetIdOne:</strong> ID of the first public subnet of this stack's VPC</li>
 *     <li><strong>&lt;environmentName&gt;-Network-publicSubnetIdTwo:</strong> ID of the second public subnet of this stack's VPC</li>
 * </ul>
 * The static getter methods provide a convenient access to retrieve these parameters from the parameter store
 * for use in other stacks.
 */
public class Network extends Stack {

    public static class NetworkProperties {
        private final String sslCertificateArn;

        /**
         * @param sslCertificateArn the ARN of the SSL certificate that the load balancer will use
         *                          to terminate HTTPS communication.
         */
        public NetworkProperties(String sslCertificateArn) {
            Objects.requireNonNull(sslCertificateArn);
            this.sslCertificateArn = sslCertificateArn;
        }
    }

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
            final NetworkProperties networkProperties) {

        super(scope, id, StackProps.builder()
                .env(environment)
                .stackName(environmentName + "-Network")
                .build());

        this.environmentName = environmentName;

        this.vpc = createVpc(environmentName);

        // We're preparing an ECS cluster in the network stack and using it in the ECS stack.
        // If the cluster were in the ECS stack, it would interfere with deleting the ECS stack,
        // because an ECS service would still depend on it.
        this.ecsCluster = Cluster.Builder.create(this, "cluster")
                .vpc(this.vpc)
                .clusterName(prefixWithEnvironmentName("ecsCluster"))
                .build();

        createLoadBalancer(vpc, networkProperties.sslCertificateArn);

        Tags.of(this).add("environment", environmentName);
    }

    public IVpc getVpc() {
        return vpc;
    }

    public IApplicationListener getHttpListener() {
        return httpListener;
    }

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
                .subnetType(SubnetType.ISOLATED)
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
            final String sslCertificateArn) {

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

        IApplicationTargetGroup dummyTargetGroup = ApplicationTargetGroup.Builder.create(this, "dummyTargetGroup")
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

        httpListener.addTargetGroups("http-dummy", AddApplicationTargetGroupsProps.builder()
                .targetGroups(Collections.singletonList(dummyTargetGroup))
                .build());

        IListenerCertificate certificate = ListenerCertificate.fromArn(sslCertificateArn);
        httpsListener = loadBalancer.addListener("httpsListener", BaseApplicationListenerProps.builder()
                .port(443)
                .protocol(ApplicationProtocol.HTTPS)
                .certificates(Collections.singletonList(certificate))
                .open(true)
                .build());

        httpsListener.addTargetGroups("https-dummy", AddApplicationTargetGroupsProps.builder()
                .targetGroups(Collections.singletonList(dummyTargetGroup))
                .build());

        createOutputParameters();
    }


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

        StringParameter httpsListener = StringParameter.Builder.create(this, "httpsListener")
                .parameterName(createParameterName(environmentName, PARAMETER_HTTPS_LISTENER))
                .stringValue(this.httpsListener.getListenerArn())
                .build();

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


    }

    @NotNull
    private static String createParameterName(String environmentName, String parameterName) {
        return environmentName + "-Network-" + parameterName;
    }

    /**
     * Gets the VPC ID of a deployed network stack created for a given environment from the parameter store.
     */
    public static String getVpcId(Construct scope, String environmentName) {
        return StringParameter.fromStringParameterName(scope, PARAMETER_VPC_ID, createParameterName(environmentName, PARAMETER_VPC_ID))
                .getStringValue();
    }

    /**
     * Gets the ARN of the HTTP listener of a deployed network stack created for a given environment from the parameter store.
     */
    public static String getHttpListenerArn(Construct scope, String environmentName) {
        return StringParameter.fromStringParameterName(scope, PARAMETER_HTTP_LISTENER, createParameterName(environmentName, PARAMETER_HTTP_LISTENER))
                .getStringValue();
    }

    /**
     * Gets the ARN of the HTTPS listener in a deployed network stack created for a given environment from the parameter store.
     */
    public static String getHttpsListenerArn(Construct scope, String environmentName) {
        return StringParameter.fromStringParameterName(scope, PARAMETER_HTTPS_LISTENER, createParameterName(environmentName, PARAMETER_HTTPS_LISTENER))
                .getStringValue();
    }

    /**
     * Gets the ID of the load balancer's security group in a deployed network stack created for a given environment from the parameter store.
     */
    public static String getLoadbalancerSecurityGroupId(Construct scope, String environmentName) {
        return StringParameter.fromStringParameterName(scope, PARAMETER_LOADBALANCER_SECURITY_GROUP_ID, createParameterName(environmentName, PARAMETER_LOADBALANCER_SECURITY_GROUP_ID))
                .getStringValue();
    }

    /**
     * Gets the name of the ECS cluster in a deployed network stack created for a given environment from the parameter store.
     */
    public static String getEcsClusterName(Construct scope, String environmentName) {
        return StringParameter.fromStringParameterName(scope, PARAMETER_ECS_CLUSTER_NAME, createParameterName(environmentName, PARAMETER_ECS_CLUSTER_NAME))
                .getStringValue();
    }

    /**
     * Gets the IDs of the isolated subnets in a deployed network stack created for a given environment from the parameter store.
     */
    public static List<String> getIsolatedSubnets(Construct scope, String environmentName) {

        String subnetOneId = StringParameter.fromStringParameterName(scope, PARAMETER_ISOLATED_SUBNET_ONE, createParameterName(environmentName, PARAMETER_ISOLATED_SUBNET_ONE))
                .getStringValue();

        String subnetTwoId = StringParameter.fromStringParameterName(scope, PARAMETER_ISOLATED_SUBNET_TWO, createParameterName(environmentName, PARAMETER_ISOLATED_SUBNET_TWO))
                .getStringValue();

        return asList(subnetOneId, subnetTwoId);
    }

    /**
     * Gets the IDs of the public subnets in a deployed network stack created for a given environment from the parameter store.
     */
    public static List<String> getPublicSubnets(Construct scope, String environmentName) {

        String subnetOneId = StringParameter.fromStringParameterName(scope, PARAMETER_PUBLIC_SUBNET_ONE, createParameterName(environmentName, PARAMETER_PUBLIC_SUBNET_ONE))
                .getStringValue();

        String subnetTwoId = StringParameter.fromStringParameterName(scope, PARAMETER_PUBLIC_SUBNET_TWO, createParameterName(environmentName, PARAMETER_PUBLIC_SUBNET_TWO))
                .getStringValue();

        return asList(subnetOneId, subnetTwoId);
    }

    /**
     * Gets the names of the availability zones of the VPC in a deployed network stack created for a given environment from the parameter store.
     */
    public static List<String> getAvailabilityZones(Construct scope, String environmentName) {

        String availabilityZoneOne = StringParameter.fromStringParameterName(scope, PARAMETER_AVAILABILITY_ZONE_ONE, createParameterName(environmentName, PARAMETER_AVAILABILITY_ZONE_ONE))
                .getStringValue();

        String availabilityZoneTwo = StringParameter.fromStringParameterName(scope, PARAMETER_AVAILABILITY_ZONE_TWO, createParameterName(environmentName, PARAMETER_AVAILABILITY_ZONE_TWO))
                .getStringValue();

        return asList(availabilityZoneOne, availabilityZoneTwo);
    }

}
