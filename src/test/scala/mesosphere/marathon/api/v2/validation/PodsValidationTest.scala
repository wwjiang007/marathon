package mesosphere.marathon
package api.v2.validation

import com.wix.accord.Validator
import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.raml.{ Constraint, ConstraintOperator, Endpoint, Network, NetworkMode, Pod, PodContainer, Resources, Volume, VolumeMount }
import mesosphere.marathon.util.SemanticVersion

class PodsValidationTest extends UnitTest with ValidationTestLike with PodsValidation with SchedulingValidation {

  "A pod definition" should {

    "be rejected if the id is empty" in new Fixture {
      shouldViolate(validPod.copy(id = "/"), "/id", "Path must contain at least one path element")
    }

    "be rejected if the id is not absolute" in new Fixture {
      shouldViolate(validPod.copy(id = "some/foo"), "/id", "Path needs to be absolute")
    }

    "be rejected if a defined user is empty" in new Fixture {
      shouldViolate(validPod.copy(user = Some("")), "/user", "must not be empty")
    }

    "be rejected if no container is defined" in new Fixture {
      shouldViolate(validPod.copy(containers = Seq.empty), "/containers", "must not be empty")
    }

    "be rejected if container names are not unique" in new Fixture {
      shouldViolate(validPod.copy(containers = Seq(validContainer, validContainer)), "/containers", "container names are unique")
    }

    "be rejected if endpoint names are not unique" in new Fixture {
      val endpoint1 = Endpoint("endpoint", hostPort = Some(123))
      val endpoint2 = Endpoint("endpoint", hostPort = Some(124))
      private val invalid = validPod.copy(containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2))))
      shouldViolate(invalid, "/", "Endpoint names are unique")
    }

    "be rejected if endpoint host ports are not unique" in new Fixture {
      val endpoint1 = Endpoint("endpoint1", hostPort = Some(123))
      val endpoint2 = Endpoint("endpoint2", hostPort = Some(123))
      private val invalid = validPod.copy(containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2))))
      shouldViolate(invalid, "/", "Host ports are unique")
    }

    "be rejected if endpoint container ports are not unique" in new Fixture {
      val endpoint1 = Endpoint("endpoint1", containerPort = Some(123))
      val endpoint2 = Endpoint("endpoint2", containerPort = Some(123))
      private val invalid = validPod.copy(
        networks = Seq(Network(mode = NetworkMode.Container)),
        containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2)))
      )
      shouldViolate(invalid, "/", "Container ports are unique")
    }

    "be rejected if volume names are not unique" in new Fixture {
      val volume = Volume("volume", host = Some("/foo"))
      val volumeMount = VolumeMount(volume.name, "/bla")
      private val invalid = validPod.copy(
        volumes = Seq(volume, volume),
        containers = Seq(validContainer.copy(volumeMounts = Seq(volumeMount)))
      )
      shouldViolate(invalid, "/volumes", "volume names are unique")
    }
  }

  "A constraint definition" should {

    "MaxPer is accepted with an integer value" in {
      complyWithConstraintRules(Constraint("foo", ConstraintOperator.MaxPer, Some("3"))).isSuccess shouldBe true
    }

    "MaxPer is rejected with no value" in {
      complyWithConstraintRules(Constraint("foo", ConstraintOperator.MaxPer)).isSuccess shouldBe false
    }
  }

  class Fixture {
    val validContainer = PodContainer(
      name = "ct1",
      resources = Resources()
    )
    val validPod = Pod(
      id = "/some/pod",
      containers = Seq(validContainer),
      networks = Seq(Network(mode = NetworkMode.Host))
    )
    implicit val validator: Validator[Pod] = podDefValidator(Set.empty, SemanticVersion.zero)
  }
}
