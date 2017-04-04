package mesosphere.marathon
package api.v2.validation

import com.wix.accord.scalatest.ResultMatchers
import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.raml._
import mesosphere.UnitTest

class AppValidationTest extends UnitTest with ResultMatchers with ValidationTestLike {

  import Normalization._

  "network validation" when {
    val basicValidator = AppValidation.validateCanonicalAppAPI(Set.empty)

    def bridgeNetworkedContainer(portMappings: Seq[ContainerPortMapping], networkCount: Int = 1) =
      App(
        id = "/foo",
        cmd = Some("bar"),
        networks = 1.to(networkCount).map { i => Network(mode = NetworkMode.Container, name = Some(i.toString)) },
        container = Some(Container(`type` = EngineType.Mesos, portMappings = Some(portMappings))))

    "multiple container networks are specified for an app" should {

      "require networkName for hostPort to containerPort mapping" in {
        val badApp = bridgeNetworkedContainer(
          Seq(ContainerPortMapping(hostPort = Option(0))), networkCount = 2)

        basicValidator(badApp).normalize should failWith(
          "/container/portMappings(0)" ->
            AppValidationMessages.NetworkNameRequiredForMultipleContainerNetworks)
      }

      "allow portMappings that don't declare hostPort nor networkName" in {
        val badApp = bridgeNetworkedContainer(
          Seq(ContainerPortMapping()), networkCount = 2)
        basicValidator(badApp) shouldBe (aSuccess)
      }

      "allow portMappings that both declare a hostPort and a networkName" in {
        val badApp = bridgeNetworkedContainer(Seq(
          ContainerPortMapping(
            hostPort = Option(0),
            networkName = Some("1"))), networkCount = 2)
        basicValidator(badApp) shouldBe (aSuccess)
      }
    }

    "single container network" should {

      val validNetworks = List(Network(Some("container-network"), NetworkMode.Container))
      implicit val portMappingValidator =
        AppValidation.portMappingsValidator(validNetworks)

      "consider a valid portMapping with a name as valid" in {
        basicValidator(
          bridgeNetworkedContainer(
            Seq(
              ContainerPortMapping(
                hostPort = Some(80),
                containerPort = 80,
                networkName = Some("1"))))) shouldBe (aSuccess)
      }

      "consider a portMapping with no name as valid" in {
        basicValidator(
          bridgeNetworkedContainer(
            Seq(
              ContainerPortMapping(
                hostPort = Some(80),
                containerPort = 80,
                networkName = None)))) shouldBe (aSuccess)
      }

      "consider a portMapping without a hostport as valid" in {
        basicValidator(
          bridgeNetworkedContainer(
            Seq(
              ContainerPortMapping(
                hostPort = None)))) shouldBe (aSuccess)
      }

      "consider portMapping with zero hostport as valid" in {
        basicValidator(
          bridgeNetworkedContainer(
            Seq(
              ContainerPortMapping(
                containerPort = 80,
                hostPort = Some(0))))) shouldBe (aSuccess)
      }

      "consider portMapping with a non-matching network name as invalid" in {
        val result = basicValidator(
          bridgeNetworkedContainer(
            Seq(
              ContainerPortMapping(
                containerPort = 80,
                hostPort = Some(80),
                networkName = Some("undefined-network-name")))))
        result.isFailure shouldBe true
      }

      "consider portMapping without networkName nor hostPort as valid" in {
        basicValidator(
          bridgeNetworkedContainer(
            Seq(
              ContainerPortMapping(
                containerPort = 80,
                hostPort = None,
                networkName = None)))) shouldBe (aSuccess)
      }
    }

    "general port validation" in {
      basicValidator(
        bridgeNetworkedContainer(
          Seq(
            ContainerPortMapping(
              name = Some("name"),
              hostPort = Some(123)),
            ContainerPortMapping(
              name = Some("name"),
              hostPort = Some(123))))).isFailure shouldBe true
    }
  }
}
