package dev.stratospheric.cdk;

import software.amazon.awscdk.Tags;
import software.constructs.IConstruct;

/**
 * An application can be deployed into multiple environments (staging, production, ...).
 * An {@link ApplicationEnvironment} object serves as a descriptor in which environment
 * an application is deployed.
 * <p>
 * The constructs in this package will use this descriptor when naming AWS resources so that they
 * can be deployed into multiple environments at the same time without conflicts.
 */
public class ApplicationEnvironment {

  private final String applicationName;
  private final String environmentName;

  /**
   * Constructor.
   *
   * @param applicationName the name of the application that you want to deploy.
   * @param environmentName the name of the environment the application shall be deployed into.
   */
  public ApplicationEnvironment(String applicationName, String environmentName) {
    this.applicationName = applicationName;
    this.environmentName = environmentName;
  }

  public String getApplicationName() {
    return applicationName;
  }

  public String getEnvironmentName() {
    return environmentName;
  }

  /**
   * Strips non-alphanumeric characters from a String since some AWS resources don't cope with
   * them when using them in resource names.
   */
  private String sanitize(String environmentName) {
    return environmentName.replaceAll("[^a-zA-Z0-9-]", "");
  }

  @Override
  public String toString() {
    return sanitize(environmentName + "-" + applicationName);
  }

  /**
   * Prefixes a string with the application name and environment name.
   */
  public String prefix(String string) {
    return this.toString() + "-" + string;
  }

  /**
   * Prefixes a string with the application name and environment name. Returns only the last <code>characterLimit</code>
   * characters from the name.
   */
  public String prefix(String string, int characterLimit) {
    String name = this.toString() + "-" + string;
    if (name.length() <= characterLimit) {
      return name;
    }
    return name.substring(name.length() - 21);
  }

  public void tag(IConstruct construct) {
    Tags.of(construct).add("environment", environmentName);
    Tags.of(construct).add("application", applicationName);
  }
}
