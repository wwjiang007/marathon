package mesosphere.marathon
package api.akkahttp.v2

import akka.Done
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.marathon.api.akkahttp.EntityMarshallers.ValidationFailed
import mesosphere.marathon.api.akkahttp.LeaderDirectives.{ NoLeader, ProxyToLeader }
import mesosphere.marathon.api.v2.LeaderResource
import mesosphere.marathon.api.{ JsonTestHelper, TestAuthFixture }
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.storage.repository.RuntimeConfigurationRepository
import mesosphere.marathon.test.{ SettableClock, SimulatedScheduler }
import mesosphere.{ UnitTest, ValidationTestLike }
import org.scalatest.Inside

import scala.concurrent.Future

class LeaderControllerTest extends UnitTest with ScalatestRouteTest with Inside with ValidationTestLike with RouteBehaviours {

  "LeaderController" should {

    // Unauthenticated access test cases
    {
      val controller = Fixture(authenticated = false, isLeader = true).controller()
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./))
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Delete(Uri./))
    }

    // Unauthorized access test cases
    {
      val controller = Fixture(authorized = false, isLeader = true).controller()
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Get(Uri./))
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Delete(Uri./))
    }

    // Entity not found
    {
      Given("no leader has been elected")
      val f = Fixture()
      val controller = f.controller()
      f.electionService.leaderHostPort returns (None)

      behave like unknownEntity(forRoute = controller.route, withRequest = Get(Uri./), withMessage = "There is no leader")
    }

    "return the leader info" in {
      Given("a leader has been elected")
      val f = Fixture()
      val controller = f.controller()
      f.electionService.leaderHostPort returns (Some("new.leader.com"))

      When("we try to fetch the info")
      Get(Uri./) ~> controller.route ~> check {
        Then("we receive all info")
        status should be(StatusCodes.OK)
        val expected =
          """{
            |  "leader": "new.leader.com"
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }

    "abdicate leadership" in {
      Given("the host is leader")
      val f = Fixture(isLeader = true)
      val controller = f.controller()
      f.runtimeRepo.store(raml.RuntimeConfiguration(Some("s3://mybucket/foo"), None)) returns (Future.successful(Done))

      When("we try to abdicate")
      Delete("/?backup=s3://mybucket/foo") ~> controller.route ~> check {
        Then("we abdicate in 500ms")
        verify(f.electionService, times(0)).abdicateLeadership()
        f.clock += LeaderResource.abdicationDelay
        verify(f.electionService, times(1)).abdicateLeadership()

        And("receive HTTP ok")
        status should be(StatusCodes.OK)
        val expected =
          """{
            |  "message": "Leadership abdicated"
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }

    "reject an invalid backup or restore parameter" in {
      Given("the host is leader")
      val f = new Fixture()
      val controller = f.controller()
      f.electionService.isLeader returns (true)

      When("we try to abdicate")
      Delete("/?backup=norealuri&restore=alsowrong") ~> controller.route ~> check {
        Then("then the request should be rejected")
        rejection shouldBe a[ValidationFailed]
        inside(rejection) {
          case ValidationFailed(failure) =>
            failure should haveViolations("/" -> "Invalid URI or unsupported scheme: norealuri")
            failure should haveViolations("/" -> "Invalid URI or unsupported scheme: alsowrong")
        }
      }
    }

    "not abdicate leadership if there is no leader" in {
      Given("there is no leader")
      val f = Fixture(isLeader = false)
      val controller = f.controller()
      f.electionService.leaderHostPort returns (None)

      When("we try to abdicate")
      Delete(Uri./) ~> controller.route ~> check {
        Then("we receive EntityNotFound response")
        rejection should be(NoLeader)
      }
    }

    "proxy the request if instance is not the leader" in {
      Given("the instance is not the leader")
      val f = Fixture(isLeader = false)
      val controller = f.controller()

      And("there is a leader")
      f.electionService.leaderHostPort returns (Some("awesome.leader.com"))
      f.electionService.localHostPort returns ("localhost:8080")

      When("we try to abdicate")
      Delete(Uri./) ~> controller.route ~> check {
        Then("we receive EntityNotFound response")
        rejection shouldBe a[ProxyToLeader]
        inside(rejection) {
          case ProxyToLeader(request, localHostPort, leaderHost) =>
            leaderHost should be("awesome.leader.com")
            localHostPort should be("localhost:8080")
        }
      }
    }
  }

  case class Fixture(authenticated: Boolean = true, authorized: Boolean = true, isLeader: Boolean = true) {
    val electionService = mock[ElectionService]
    val runtimeRepo = mock[RuntimeConfigurationRepository]

    val auth = new TestAuthFixture()
    auth.authenticated = authenticated
    auth.authorized = authorized

    val config = AllConf.withTestConfig()
    val clock = new SettableClock()
    val scheduler = new SimulatedScheduler(clock)

    electionService.isLeader returns (isLeader)

    val executionContext = ExecutionContexts.callerThread

    def controller() = new LeaderController(electionService, runtimeRepo, scheduler)(auth.auth, auth.auth, executionContext)
  }
}
