package dev.stratospheric.cdk;

import java.util.Objects;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.services.ec2.CfnInstance;
import software.amazon.awscdk.services.ec2.CfnSecurityGroup;
import software.amazon.awscdk.services.ec2.CfnSecurityGroupIngress;
import software.constructs.Construct;

import static java.util.Collections.singletonList;

/**
 * A stack that deploys an EC2 instance to use as a jump host (or "bastion host") to create an SSH tunnel to the RDS instance.
 * The jump host is placed into the public subnets of a given VPC and it will have access the database's security group.
 * <p>
 * The following parameters need to exist in the SSM parameter store for this stack to successfully deploy:
 * <ul>
 *     <li><strong>&lt;environmentName&gt;-Network-vpcId:</strong> ID of the VPC to deploy the database into.</li>
 *     <li><strong>&lt;environmentName&gt;-Network-publicSubnetIdOne:</strong> ID of the first public subnet in which to deploy this jump host.</li>
 *     <li><strong>&lt;environmentName&gt;-Network-publicSubnetIdTwo:</strong> ID of the second public subnet in which to deploy this jump host.</li>
 *     <li><strong>&lt;environmentName&gt;-&lt;applicationName&gt;-Database-securityGroupId:</strong> ID of the database's security group</li>
 * </ul>
 * <p>
 * The stack creates its public IP address as output, so you can look up its IP address in the CloudFormation AWS console
 * when you need it to access it via SSH.
 */
public class JumpHost extends Construct {

  private final ApplicationEnvironment applicationEnvironment;

  public JumpHost(
    final Construct scope,
    final String id,
    final Environment environment,
    final ApplicationEnvironment applicationEnvironment,
    final JumpHostInputParameters jumpHostInputParameters,
    final PostgresDatabase.DatabaseOutputParameters databaseOutputParameters) {

    super(scope, id);

    this.applicationEnvironment = applicationEnvironment;

    Network.NetworkOutputParameters networkOutputParameters = Network.getOutputParametersFromParameterStore(this, applicationEnvironment.getEnvironmentName());

    CfnSecurityGroup jumpHostSecurityGroup = CfnSecurityGroup.Builder.create(this, "securityGroup")
      .groupName(applicationEnvironment.prefix("jumpHostSecurityGroup"))
      .groupDescription("SecurityGroup containing the jump host")
      .vpcId(networkOutputParameters.getVpcId())
      .build();

    String databaseSecurityGroupId = databaseOutputParameters.getDatabaseSecurityGroupId();

    allowAccessToJumpHost(jumpHostSecurityGroup);
    allowAccessToDatabase(jumpHostSecurityGroup, databaseSecurityGroupId);

    CfnInstance instance = createEc2Instance(
      jumpHostInputParameters.keyName,
      jumpHostSecurityGroup,
      networkOutputParameters);

    CfnOutput publicIpOutput = CfnOutput.Builder.create(this, "publicIp")
      .value(instance.getAttrPublicIp())
      .build();

    applicationEnvironment.tag(this);

  }

  private CfnInstance createEc2Instance(
    String keyName,
    CfnSecurityGroup jumpHostSecurityGroup,
    Network.NetworkOutputParameters networkOutputParameters) {

    return CfnInstance.Builder.create(this, "jumpHostInstance")
      .instanceType("t2.nano")
      .securityGroupIds(singletonList(jumpHostSecurityGroup.getAttrGroupId()))
      .imageId("ami-0f96495a064477ffb")
      .subnetId(networkOutputParameters.getPublicSubnets().get(0))
      .keyName(keyName)
      .build();

  }

  private void allowAccessToDatabase(CfnSecurityGroup fromSecurityGroup, String toSecurityGroupId) {
    CfnSecurityGroupIngress dbSecurityGroupIngress = CfnSecurityGroupIngress.Builder.create(this, "IngressFromJumpHost")
      .sourceSecurityGroupId(fromSecurityGroup.getAttrGroupId())
      .groupId(toSecurityGroupId)
      .fromPort(5432)
      .toPort(5432)
      .ipProtocol("TCP")
      .build();
  }

  private void allowAccessToJumpHost(CfnSecurityGroup jumpHostSecurityGroup) {
    CfnSecurityGroupIngress jumpHostSecurityGroupIngress = CfnSecurityGroupIngress.Builder.create(this, "IngressFromOutside")
      .groupId(jumpHostSecurityGroup.getAttrGroupId())
      .fromPort(22)
      .toPort(22)
      .ipProtocol("TCP")
      .cidrIp("0.0.0.0/0")
      .build();
  }

  public static class JumpHostInputParameters {
    private final String keyName;

    /**
     * @param keyName the name of the key pair that will be installed in the jump host EC2 instance so you can
     *                access it via SSH. This key pair must be created via the EC2 console beforehand.
     */
    public JumpHostInputParameters(String keyName) {
      Objects.requireNonNull(keyName, "parameter 'keyName' cannot be null");
      this.keyName = keyName;
    }
  }
}
