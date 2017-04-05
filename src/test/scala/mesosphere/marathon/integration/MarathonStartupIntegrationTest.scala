package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import org.scalatest.concurrent.{ Eventually, TimeLimits }
import org.scalatest.time.{ Seconds, Span }

@IntegrationTest
class MarathonStartupIntegrationTest extends AkkaIntegrationTest
    with MesosClusterTest
    with ZookeeperServerTest
    with MarathonFixture
    with TimeLimits
    with Eventually {

  "Marathon" should {
    "fail during start, if the HTTP port is already bound" in withMarathon(suiteName){ (marathonServer, facade) =>
      Given(s"a Marathon process already running on port ${marathonServer.httpPort}")

      When("starting another Marathon process using an HTTP port that is already bound")

      val args = Map(
        "http_port" -> marathonServer.httpPort.toString,
        "zk_timeout" -> "2000"
      )
      val conflictingMarathon = LocalMarathon(true, s"$suiteName-conflict", marathonServer.masterUrl, marathonServer.zkUrl, args)

      Then("The Marathon process should exit with code > 0")
      // Let's try and see if we get the deadlock.
  //   try {
        eventually {
          conflictingMarathon.isRunning() should be(false)
        } withClue ("The conflicting Marathon did not suicide.")
        conflictingMarathon.exitValue().get should be > 0 withClue (s"Conflicting Marathon exited with ${conflictingMarathon.exitValue()} instead of an error code > 0.")
  //   } finally {
  //     // Destroy process if it did not exit in time.
  //     conflictingMarathon.stop()
  //   }
    }
  }
}
