package dev.stratospheric.cdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationEnvironmentTest {

  @Test
  void prefix(){
    ApplicationEnvironment env = new ApplicationEnvironment("myapp", "prod");
    assertThat(env.prefix("foo")).isEqualTo("prod-myapp-foo");
  }

  @Test
  void longPrefix(){
    ApplicationEnvironment env = new ApplicationEnvironment("my-long-application-name", "my-long-env-name");
    assertThat(env.prefix("my-long-prefix", 20)).isEqualTo("-name-my-long-prefix");
  }

}
