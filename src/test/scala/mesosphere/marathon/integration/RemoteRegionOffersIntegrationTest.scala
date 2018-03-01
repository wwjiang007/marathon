package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ FaultDomain, PathId, Region, Zone }
import mesosphere.mesos.Constraints
import org.scalatest.Inside

@IntegrationTest
class RemoteRegionOffersIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Inside {

  override lazy val mesosNumMasters = 1
  override lazy val mesosNumSlaves = 3

  class Fixture {
    val homeRegion = Region("home_region")
    val homeZone = Zone("home_zone")

    val remoteRegion = Region("remote_region")
    val remoteZone1 = Zone("remote_zone1")
    val remoteZone2 = Zone("remote_zone2")
  }

  val f = new Fixture

  override def mastersFaultDomains = Seq(Some(FaultDomain(region = f.homeRegion, zone = f.homeZone)))

  override def agentsFaultDomains = Seq(
    Some(FaultDomain(region = f.remoteRegion, zone = f.remoteZone1)),
    Some(FaultDomain(region = f.remoteRegion, zone = f.remoteZone2)),
    Some(FaultDomain(region = f.homeRegion, zone = f.homeZone)))

  def appId(suffix: String): PathId = testBasePath / s"app-${suffix}"

  "Region Aware marathon" must {
    "Launch an instance of the app in the default region if region is not specified" in {
      val applicationId = appId("must-be-placed-in-home-region")
      val app = appProxy(applicationId, "v1", instances = 1, healthCheck = None)

      When("The app is deployed without specifying region")
      val result = marathon.createAppV2(app)

      Then("The app is created in the default region")
      result should be(Created)

      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1)
      val task = marathon.tasks(applicationId).value.head
      task.region shouldBe Some(f.homeRegion.value)
    }

    "Launch an instance of the app in the specified region" in {
      val applicationId = appId("must-be-placed-in-remote-region")
      val app = appProxy(applicationId, "v1", instances = 1, healthCheck = None).copy(constraints =
        Set(Constraints.regionField :: "LIKE" :: f.remoteRegion.value :: Nil))

      When("The app is deployed with specific region constraint")
      val result = marathon.createAppV2(app)

      Then("The app is created in the specified region")
      result should be(Created)
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1)
      val task = marathon.tasks(applicationId).value.head
      task.region shouldBe Some(f.remoteRegion.value)
    }

    "Launch an instance of the app in the specified region and zone" in {
      val applicationId = appId("must-be-placed-in-remote-region-and-zone")
      val app = appProxy(applicationId, "v1", instances = 1, healthCheck = None).copy(constraints = Set(
        Constraints.regionField :: "LIKE" :: f.remoteRegion.value :: Nil,
        Constraints.zoneField :: "LIKE" :: f.remoteZone2.value :: Nil
      ))

      When("The app is deployed with specific region and zone constraints")
      val result = marathon.createAppV2(app)

      Then("The app is created in the proper region and a proper zone")
      result should be(Created)
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1)
      val task = marathon.tasks(applicationId).value.head
      task.region shouldBe Some(f.remoteRegion.value)
      task.zone shouldBe Some(f.remoteZone2.value)
    }
  }

}
