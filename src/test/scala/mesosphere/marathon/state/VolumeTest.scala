package mesosphere.marathon
package state

import com.wix.accord._
import mesosphere.UnitTest
import mesosphere.marathon.api.serialization.VolumeSerializer
import mesosphere.marathon.api.v2.ValidationHelper
import org.apache.mesos.Protos.Resource.DiskInfo.Source

class VolumeTest extends UnitTest {
  import mesosphere.marathon.test.MarathonTestHelper.constraint

  def survivesProtobufSerializationRoundtrip(title: => String, volumeWithMount: => VolumeWithMount[Volume]): Unit = {
    s"$title survives protobuf serialization round-trip" in {
      val protobuf = VolumeSerializer.toProto(volumeWithMount)
      val resurrected = VolumeWithMount(None, protobuf)
      resurrected should be(volumeWithMount)
    }
  }

  def persistent(info: PersistentVolumeInfo, mountPath: String = "cpath", readOnly: Boolean = false): VolumeWithMount[PersistentVolume] = {
    val volume = PersistentVolume(None, info)
    val mount = VolumeMount(None, mountPath, readOnly)
    VolumeWithMount(volume, mount)
  }

  def external(info: ExternalVolumeInfo, mountPath: String = "cpath", readOnly: Boolean = false): VolumeWithMount[ExternalVolume] = {
    val volume = ExternalVolume(None, info)
    val mount = VolumeMount(None, mountPath, readOnly)
    VolumeWithMount(volume, mount)
  }

  trait Fixture {
    val rootVolNoConstraints = PersistentVolumeInfo(
      size = 1024,
      constraints = Set.empty)
    val pathVolWithConstraint = PersistentVolumeInfo(
      size = 1024,
      `type` = DiskType.Path,
      constraints = Set(constraint("path", "LIKE", Some("valid regex"))))
    val mountVolWithMaxSize = PersistentVolumeInfo(
      size = 1024,
      `type` = DiskType.Mount,
      maxSize = Some(2048))
    val mountVolWithProfile = PersistentVolumeInfo(
      size = 1024,
      `type` = DiskType.Mount,
      profileName = Some("ssd-fast"))
    val extVolNoSize = ExternalVolumeInfo(
      name = "volname",
      provider = "provider",
      options = Map("foo" -> "bar")
    )
    val extVolWithSize = ExternalVolumeInfo(
      size = Option(1),
      name = "volname",
      provider = "provider",
      options = Map("foo" -> "bar", "baz" -> "qaw")
    )
    val hostVol = VolumeWithMount(
      volume = HostVolume(None, hostPath = "/host/path"),
      mount = VolumeMount(None, "cpath", false)
    )

    val secretVol = VolumeWithMount(
      volume = SecretVolume(None, secret = "secret-name"),
      mount = VolumeMount(None, "secret-path", true))
  }
  object Fixture extends Fixture

  "Volume" should {

    behave like survivesProtobufSerializationRoundtrip("root vol, no constraints", persistent(Fixture.rootVolNoConstraints))
    behave like survivesProtobufSerializationRoundtrip("path vol w/ constraint", persistent(Fixture.pathVolWithConstraint))
    behave like survivesProtobufSerializationRoundtrip("mount vol w/ maxSize", persistent(Fixture.mountVolWithMaxSize))
    behave like survivesProtobufSerializationRoundtrip("mount vol w/ profile", persistent(Fixture.mountVolWithProfile))
    behave like survivesProtobufSerializationRoundtrip("ext vol w/o size", external(Fixture.extVolNoSize))
    behave like survivesProtobufSerializationRoundtrip("ext vol w/ size", external(Fixture.extVolWithSize))
    behave like survivesProtobufSerializationRoundtrip("host vol", Fixture.hostVol)
    behave like survivesProtobufSerializationRoundtrip("secret vol", Fixture.secretVol)

    "validating PersistentVolumeInfo constraints accepts an empty constraint list" in new Fixture {
      validate(rootVolNoConstraints).isSuccess shouldBe true
    }

    "validating PersistentVolumeInfo constraints rejects unsupported fields" in {
      val pvi = PersistentVolumeInfo(
        1024,
        `type` = DiskType.Path,
        constraints = Set(constraint("invalid", "LIKE", Some("regex"))))

      val result = validate(pvi)
      result.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstraints(result).map(_.constraint) shouldBe Set("Unsupported field")
    }

    "validating PersistentVolumeInfo constraints rejected for root resources" in {
      val result = validate(
        PersistentVolumeInfo(
          1024,
          `type` = DiskType.Root,
          constraints = Set(constraint("path", "LIKE", Some("regex")))))
      result.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstraints(result).map(_.constraint) shouldBe Set("Constraints on root volumes are not supported")
    }

    "validating PersistentVolumeInfo constraints rejects bad regex" in {
      val pvi = PersistentVolumeInfo(
        1024,
        `type` = DiskType.Path,
        constraints = Set(constraint("path", "LIKE", Some("(bad regex"))))
      val result = validate(pvi)
      result.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstraints(result).map(_.constraint) shouldBe Set("Invalid regular expression")
    }

    "validating PersistentVolumeInfo accepts a valid constraint" in new Fixture {
      val result = validate(pathVolWithConstraint)
      result.isSuccess shouldBe true
    }

    "validating PersistentVolumeInfo accepts a valid profileName" in new Fixture {
      val result = validate(mountVolWithProfile)
      result.isSuccess shouldBe true
    }

    "validating PersistentVolumeInfo rejects an empty profileName" in new Fixture {
      val result = validate(PersistentVolumeInfo(
        size = 1024,
        `type` = DiskType.Mount,
        profileName = Some("")))
      result.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstraints(result).map(_.constraint) shouldBe Set("must not be empty")
    }

    "validating PersistentVolumeInfo maxSize parameter wrt type" in new Fixture {
      val resultRoot = validate(
        PersistentVolumeInfo(1024, `type` = DiskType.Root, maxSize = Some(2048)))
      resultRoot.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstraints(resultRoot).map(_.constraint) shouldBe Set("Only mount volumes can have maxSize")

      val resultPath = validate(
        PersistentVolumeInfo(1024, `type` = DiskType.Path, maxSize = Some(2048)))
      resultPath.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstraints(resultPath).map(_.constraint) shouldBe Set("Only mount volumes can have maxSize")

      validate(mountVolWithMaxSize).isSuccess shouldBe true
    }

    "validating that DiskSource asMesos converts to an Option Mesos Protobuffer" in {
      DiskSource(DiskType.Root, None, None, None, None).asMesos shouldBe None
      val Some(pathDisk) = DiskSource(DiskType.Path, Some("/path/to/folder"), None, None, None).asMesos
      pathDisk.getPath.getRoot shouldBe "/path/to/folder"
      pathDisk.getType shouldBe Source.Type.PATH
      pathDisk.hasId shouldBe false
      pathDisk.hasMetadata shouldBe false
      pathDisk.hasProfile shouldBe false

      val Some(mountDisk) = DiskSource(DiskType.Mount, Some("/path/to/mount"), None, None, None).asMesos
      mountDisk.getMount.getRoot shouldBe "/path/to/mount"
      mountDisk.getType shouldBe Source.Type.MOUNT
      pathDisk.hasId shouldBe false
      pathDisk.hasMetadata shouldBe false
      pathDisk.hasProfile shouldBe false

      val Some(pathCsiDisk) = DiskSource(DiskType.Path, Some("/path/to/folder"),
        Some("csiPathDisk"), Some(Map("pathKey" -> "pathValue")), Some("pathProfile")).asMesos
      pathCsiDisk.getPath.getRoot shouldBe "/path/to/folder"
      pathCsiDisk.getType shouldBe Source.Type.PATH
      pathCsiDisk.getId shouldBe "csiPathDisk"
      pathCsiDisk.getMetadata.getLabelsCount shouldBe 1
      pathCsiDisk.getMetadata.getLabels(0).getKey shouldBe "pathKey"
      pathCsiDisk.getMetadata.getLabels(0).getValue shouldBe "pathValue"
      pathCsiDisk.getProfile shouldBe "pathProfile"

      val Some(mountCsiDisk) = DiskSource(DiskType.Mount, Some("/path/to/mount"),
        Some("csiMountDisk"), Some(Map("mountKey" -> "mountValue")), Some("mountProfile")).asMesos
      mountCsiDisk.getMount.getRoot shouldBe "/path/to/mount"
      mountCsiDisk.getType shouldBe Source.Type.MOUNT
      mountCsiDisk.getId shouldBe "csiMountDisk"
      mountCsiDisk.getMetadata.getLabelsCount shouldBe 1
      mountCsiDisk.getMetadata.getLabels(0).getKey shouldBe "mountKey"
      mountCsiDisk.getMetadata.getLabels(0).getValue shouldBe "mountValue"
      mountCsiDisk.getProfile shouldBe "mountProfile"

      a[IllegalArgumentException] shouldBe thrownBy {
        DiskSource(DiskType.Root, Some("/path"), None, None, None).asMesos
      }
      a[IllegalArgumentException] shouldBe thrownBy {
        DiskSource(DiskType.Path, None, None, None, None).asMesos
      }
      a[IllegalArgumentException] shouldBe thrownBy {
        DiskSource(DiskType.Mount, None, None, None, None).asMesos
      }
    }
  }
}
