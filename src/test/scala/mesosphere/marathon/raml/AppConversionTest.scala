package mesosphere.marathon
package raml

import mesosphere.marathon.api.v2.{ AppNormalization, AppHelpers }
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.core.pod.{ BridgeNetwork, HostNetwork }
import mesosphere.marathon.state._
import mesosphere.{ UnitTest, ValidationTestLike }
import org.apache.mesos.{ Protos => Mesos }

class AppConversionTest extends UnitTest with ValidationTestLike {
  private lazy val dockerBridgeApp = {
    val constraint = Protos.Constraint.newBuilder()
      .setField("foo")
      .setOperator(Protos.Constraint.Operator.CLUSTER)
      .setValue("1")
      .build()

    AppDefinition(
      id = PathId("/docker-bridge-app"),
      cmd = Some("test"),
      user = Some("user"),
      env = Map("A" -> state.EnvVarString("test"), "password" -> state.EnvVarSecretRef("secret0")),
      instances = 23,
      resources = Resources(),
      executor = "executor",
      constraints = Set(constraint),
      fetch = Seq(FetchUri("http://test.this")),
      backoffStrategy = BackoffStrategy(),
      container = Some(state.Container.Docker(
        volumes = Seq(state.VolumeWithMount(
          volume = state.HostVolume(None, "/host"),
          mount = state.VolumeMount(None, "/container"))),
        image = "foo/bla",
        portMappings = Seq(state.Container.PortMapping(12, name = Some("http-api"), hostPort = Some(23), servicePort = 123)),
        privileged = true
      )),
      networks = Seq(BridgeNetwork()),
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference.ByIndex(0)))),
      readinessChecks = Seq(core.readiness.ReadinessCheck()),
      acceptedResourceRoles = Set("*"),
      killSelection = state.KillSelection.OldestFirst,
      secrets = Map("secret0" -> state.Secret("/path/to/secret"))
    )
  }
  private lazy val hostApp = AppDefinition(
    id = PathId("/host-app"),
    networks = Seq(HostNetwork),
    cmd = Option("whatever"),
    requirePorts = true,
    portDefinitions = state.PortDefinitions(1, 2, 3),
    unreachableStrategy = state.UnreachableDisabled
  )
  private lazy val argsOnlyApp = AppDefinition(
    id = PathId("/args-only-app"),
    args = Seq("whatever", "one", "two", "three")
  )
  private lazy val simpleDockerApp = AppDefinition(
    id = PathId("/simple-docker-app"),
    container = Some(state.Container.Docker(image = "foo/bla"))
  )
  private lazy val dockerWithArgsApp = AppDefinition(
    id = PathId("/docker-with-args-app"),
    args = Seq("whatever", "one", "two", "three"),
    container = Some(state.Container.Docker(image = "foo/bla"))
  )

  def convertToRamlAndBack(app: AppDefinition): Unit = {
    s"app ${app.id.toString} is written to json and can be read again via formats" in {
      Given("An app")
      val ramlApp = app.toRaml[App]

      When("The app is translated to json and read back from formats")
      val features = Set(Features.SECRETS)
      val readApp: AppDefinition = withValidationClue {
        Raml.fromRaml(
          AppHelpers.appNormalization(
            features, AppNormalization.Configuration(None, "bridge-name")).normalized(ramlApp)
        )
      }
      Then("The app is identical")
      readApp should be(app)
    }
  }

  def convertToProtobufThenToRAML(app: AppDefinition): Unit = {
    s"app ${app.id.toString} is written as protobuf then converted to RAML matches directly converting the app to RAML" in {
      Given("A RAML app")
      val ramlApp = app.toRaml[App]

      When("The app is translated to proto, then to RAML")
      val protoRamlApp = app.toProto.toRaml[App]

      Then("The direct and indirect RAML conversions are identical")
      val config = AppNormalization.Configuration(None, "bridge-name")
      val normalizedProtoRamlApp = AppNormalization(
        config).normalized(AppNormalization.forDeprecated(config).normalized(protoRamlApp))
      normalizedProtoRamlApp should be(ramlApp)
    }
  }

  "AppConversion" should {
    behave like convertToRamlAndBack(dockerBridgeApp)
    behave like convertToProtobufThenToRAML(dockerBridgeApp)

    behave like convertToRamlAndBack(hostApp)
    behave like convertToProtobufThenToRAML(hostApp)

    behave like convertToRamlAndBack(argsOnlyApp)
    behave like convertToProtobufThenToRAML(argsOnlyApp)

    behave like convertToRamlAndBack(simpleDockerApp)
    behave like convertToProtobufThenToRAML(simpleDockerApp)

    behave like convertToRamlAndBack(dockerWithArgsApp)
    behave like convertToProtobufThenToRAML(dockerWithArgsApp)

    "convert legacy service definitions to RAML" in {
      val legacy = Protos.ServiceDefinition.newBuilder()
        .setId("/legacy")
        .setCmd(Mesos.CommandInfo.newBuilder().setValue("sleep 60"))
        .setOBSOLETEIpAddress(Protos.ObsoleteIpAddress.newBuilder()
          .setNetworkName("fubar")
          .addLabels(Mesos.Label.newBuilder().setKey("try").setValue("me"))
          .addGroups("group1").addGroups("group2")
          .setDiscoveryInfo(Protos.ObsoleteDiscoveryInfo.newBuilder()
            .addPorts(Mesos.Port.newBuilder()
              .setNumber(234)
              .setName("port1")
              .setProtocol("udp")
              .setLabels(
                Mesos.Labels.newBuilder.addLabels(Mesos.Label.newBuilder.setKey("VIP_0").setValue("named:234")))
            )
          )
        )
        .setLastScalingAt(0)
        .setLastConfigChangeAt(0)
        .setExecutor("//cmd")
        .setInstances(2)
        .build
      val expectedRaml = App(
        id = "/legacy",
        cmd = Option("sleep 60"),
        executor = "//cmd",
        instances = 2,
        ipAddress = Option(IpAddress(
          discovery = Option(IpDiscovery(
            ports = Seq(
              IpDiscoveryPort(234, "port1", NetworkProtocol.Udp, Map("VIP_0" -> "named:234"))
            )
          )),
          groups = Set("group1", "group2"),
          labels = Map("try" -> "me"),
          networkName = Option("fubar")
        )),
        versionInfo = Option(VersionInfo(
          lastScalingAt = Timestamp.zero.toOffsetDateTime,
          lastConfigChangeAt = Timestamp.zero.toOffsetDateTime
        )),
        portDefinitions = None
      )
      legacy.toRaml[App] should be(expectedRaml)
    }
  }
}
