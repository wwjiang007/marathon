package mesosphere

import java.util.concurrent.{ LinkedBlockingDeque, TimeUnit }

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.testkit.{ TestActor, TestActorRef, TestKitBase }
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.StrictLogging
import com.wix.accord.{ Failure, Result, Success }
import mesosphere.marathon.Normalization
import mesosphere.marathon.ValidationFailedException
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.test.Mockito
import org.scalatest.matchers.{ BeMatcher, MatchResult, Matcher }
import org.scalatest._
import org.scalatest.concurrent.{ JavaFutures, ScalaFutures, TimeLimitedTests }
import org.scalatest.time.{ Minute, Minutes, Seconds, Span }
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.integration.setup.RestResult

import scala.concurrent.ExecutionContextExecutor

/**
  * Tests which fail due to a known issue can be tagged. They are executed but are marked as canceled when they fail.
  */
case class KnownIssue(jira: String) extends Tag(s"mesosphere.marathon.KnownIssue:$jira")

/**
  * All integration tests should be marked with this tag.
  * Integration tests need a special set up and can take a long time.
  * So it is not desirable, that these kind of tests run every time all the unit tests run.
  */
object IntegrationTag extends Tag("mesosphere.marathon.IntegrationTest")

/**
  * Tag that will conditionally enable a specific test case if an environment variable is set.
  * @param envVarName The name of the environment variable to check if it is set to "true"
  * @param default The default value of the variable.
  * {{{
  *   "Something" should "do something" taggedAs WhenEnvSet("ABC") in {...}
  * }}}
  */
case class WhenEnvSet(envVarName: String, default: String = "false") extends Tag(if (sys.env.getOrElse(envVarName, default) == "true") "" else classOf[Ignore].getName)

trait CancelFailedTestWithKnownIssue extends TestSuite {

  val cancelFailedTestsWithKnownIssue = sys.env.getOrElse("MARATHON_CANCEL_TESTS", "false") == "true"
  val containsJira = """mesosphere\.marathon\.KnownIssue\:(\S+)""".r

  def knownIssue(testData: TestData): Option[String] = testData.tags.collectFirst{ case containsJira(jira) => jira }

  def markAsCanceledOnFailure(jira: String)(blk: => Outcome): Outcome =
    blk match {
      case Failed(ex) => Canceled(s"Known issue $jira: ${ex.getMessage}", ex)
      case other => other
    }

  override def withFixture(test: NoArgTest): Outcome = knownIssue(test) match {
    case Some(jira) if cancelFailedTestsWithKnownIssue => markAsCanceledOnFailure(jira) { super.withFixture(test) }
    case _ => super.withFixture(test)
  }

}

trait ValidationTestLike extends Validation {
  this: Assertions =>

  protected implicit val normalizeResult: Normalization[Result] = Normalization {
    // normalize failures => human readable error messages
    case f: Failure => Failure(f.violations.flatMap(allRuleViolationsWithFullDescription(_)))
    case x => x
  }

  def withValidationClue[T](f: => T): T = scala.util.Try { f }.recover {
    // handle RAML validation errors
    case vfe: ValidationFailedException => fail(vfe.failure.violations.toString())
    case th => throw th
  }.get

  def containViolation(tuple: (String, String)): Matcher[Result] = containViolation(tuple._1, tuple._2)

  def containViolation(path: String, message: String): Matcher[Result] = {
    Matcher {
      case Success =>
        MatchResult(
          false,
          s"result had no violations; expected ${path} -> ${message}",
          s"result was success")

      case f: Failure =>
        val violations = ValidationHelper.getAllRuleConstrains(f)

        MatchResult(
          violations.exists { v =>
            v.path.contains(path) && v.message == message
          },
          s"Violations:\n${violations.mkString("\n")} did not contain ${path} -> ${message}",
          s"Violation contains ${path} -> ${message}"
        )
    }
  }
}

/**
  * Base trait for all unit tests in WordSpec style with common matching/before/after and Option/Try/Future
  * helpers all mixed in.
  */
trait UnitTestLike extends WordSpecLike
    with GivenWhenThen
    with ScalaFutures
    with JavaFutures
    with Matchers
    with BeforeAndAfter
    with BeforeAndAfterEach
    with OptionValues
    with TryValues
    with AppendedClues
    with StrictLogging
    with Mockito
    with BeforeAndAfterAll
    with TimeLimitedTests
    with CancelFailedTestWithKnownIssue {

  override val timeLimit = Span(1, Minute)

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))
}

abstract class UnitTest extends WordSpec with UnitTestLike

trait AkkaUnitTestLike extends UnitTestLike with TestKitBase {
  protected lazy val akkaConfig: Config = ConfigFactory.parseString(
    s"""
      |akka.test.default-timeout=${patienceConfig.timeout.millisPart}
    """.stripMargin).withFallback(ConfigFactory.load())
  implicit lazy val system: ActorSystem = {
    ActorSystem(suiteName, akkaConfig)
  }
  implicit lazy val scheduler: Scheduler = system.scheduler
  implicit lazy val mat: Materializer = ActorMaterializer()
  implicit lazy val ctx: ExecutionContextExecutor = system.dispatcher
  implicit val askTimeout: Timeout = Timeout(patienceConfig.timeout.toMillis, TimeUnit.MILLISECONDS)

  def newTestActor() =
    TestActorRef[TestActor](TestActor.props(new LinkedBlockingDeque()))

  abstract override def afterAll(): Unit = {
    super.afterAll()
    // intentionally shutdown the actor system last.
    system.terminate().futureValue
  }
}

abstract class AkkaUnitTest extends UnitTest with AkkaUnitTestLike

trait IntegrationTestLike extends UnitTestLike {
  override val timeLimit = Span(15, Minutes)

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(300, Seconds))

  /**
    * Custom matcher for HTTP responses that print response body.
    * @param status The expected status code.
    */
  class HttpResponseMatcher(status: Int) extends BeMatcher[RestResult[_]] {
    def apply(left: RestResult[_]) =
      MatchResult(
        left.code == status,
        s"Response code was not $status but ${left.code} with body '${left.entityString}'",
        s"Response code was $status with body '${left.entityString}'"
      )
  }

  def Accepted = new HttpResponseMatcher(202)
  def Created = new HttpResponseMatcher(201)
  def Conflict = new HttpResponseMatcher(409)
  def Deleted = new HttpResponseMatcher(202)
  def OK = new HttpResponseMatcher(200)
  def NotFound = new HttpResponseMatcher(404)
}

abstract class IntegrationTest extends WordSpec with IntegrationTestLike

trait AkkaIntegrationTestLike extends AkkaUnitTestLike with IntegrationTestLike {
  protected override lazy val akkaConfig: Config = ConfigFactory.parseString(
    s"""
       |akka.test.default-timeout=${patienceConfig.timeout.toMillis}
    """.stripMargin).withFallback(ConfigFactory.load())
}

abstract class AkkaIntegrationTest extends IntegrationTest with AkkaIntegrationTestLike
