package mesosphere.marathon
package core.instance

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.{ AgentInfo, InstanceState, LegacyInstanceImprovement }
import mesosphere.marathon.core.instance.update.{ InstanceUpdateOperation, InstanceUpdater }
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.{ AgentInfoPlaceholder, AgentTestDefaults, NetworkInfoPlaceholder }
import mesosphere.marathon.state.{ PathId, Timestamp, UnreachableEnabled, UnreachableStrategy }
import org.apache.mesos

import scala.collection.immutable.Seq
import scala.concurrent.duration._

case class TestInstanceBuilder(instance: Instance, now: Timestamp = Timestamp.now()) {

  def addTaskLaunched(container: Option[MesosContainer] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskLaunched(container).build()

  def addTaskReserved(containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskReserved(containerName).build()

  def addTaskReserved(volumeIds: Seq[LocalVolumeId]): TestInstanceBuilder =
    withReservation(volumeIds).addTaskWithBuilder().taskResidentReserved().build()

  def addTaskResidentReserved(volumeIds: Seq[LocalVolumeId]): TestInstanceBuilder =
    withReservation(volumeIds).addTaskWithBuilder().taskResidentReserved().build()

  def addTaskResidentReserved(state: Reservation.State): TestInstanceBuilder =
    withReservation(state).addTaskWithBuilder().taskResidentReserved().build()

  def addTaskResidentLaunched(volumeIds: Seq[LocalVolumeId]): TestInstanceBuilder =
    withReservation(volumeIds).addTaskWithBuilder().taskResidentLaunched().build()

  def addTaskUnreachable(volumeIds: Seq[LocalVolumeId]): TestInstanceBuilder =
    withReservation(volumeIds).addTaskWithBuilder().taskUnreachable().build()

  def addTaskRunning(containerName: Option[String] = None, stagedAt: Timestamp = now,
    startedAt: Timestamp = now): TestInstanceBuilder =
    addTaskWithBuilder().taskRunning(containerName, stagedAt, startedAt).build()

  def addTaskLost(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskLost(since, containerName).build()

  def addTaskUnreachable(since: Timestamp = now, containerName: Option[String] = None,
    unreachableStrategy: UnreachableStrategy = UnreachableEnabled()): TestInstanceBuilder = {
    // we need to update the unreachable strategy first before adding an unreachable task
    this.copy(instance = instance.copy(unreachableStrategy = unreachableStrategy))
      .addTaskWithBuilder().taskUnreachable(since, containerName).build()
  }

  def addTaskUnreachableInactive(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskUnreachableInactive(since, containerName).build()

  def addTaskError(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskError(since, containerName).build()

  def addTaskGone(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskGone(since, containerName).build()

  def addTaskUnknown(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskUnknown(since, containerName).build()

  def addTaskKilled(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskKilled(since, containerName).build()

  def addTaskDropped(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskDropped(since, containerName).build()

  def addTaskFinished(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskFinished(since, containerName).build()

  def addTaskFailed(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskFailed(since, containerName).build()

  def addTaskCreated(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskCreated(since, containerName).build()

  def addTaskKilling(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskKilling(since, containerName).build()

  def addTaskStaging(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskStaging(since, containerName).build()

  def addTaskStarting(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskStarting(since, containerName).build()

  def addTaskStaged(stagedAt: Timestamp = now, version: Option[Timestamp] = None,
    containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskStaged(containerName, stagedAt, version).build()

  def addTaskWithBuilder(): TestTaskBuilder = TestTaskBuilder.newBuilder(this)

  private[instance] def addTask(task: Task): TestInstanceBuilder = {
    this.copy(instance = InstanceUpdater.updatedInstance(instance, task, now + 1.second))
  }

  def getInstance(): Instance = instance

  def withAgentInfo(agentInfo: AgentInfo): TestInstanceBuilder = copy(instance = instance.copy(agentInfo = agentInfo))

  def withAgentInfo(
    agentId: Option[String] = None,
    hostName: Option[String] = None,
    attributes: Option[Seq[mesos.Protos.Attribute]] = None,
    region: Option[String] = None,
    zone: Option[String] = None
  ): TestInstanceBuilder =
    copy(instance = instance.copy(agentInfo = instance.agentInfo.copy(
      agentId = agentId.orElse(instance.agentInfo.agentId),
      host = hostName.getOrElse(instance.agentInfo.host),
      region = region.orElse(instance.agentInfo.region),
      zone = zone.orElse(instance.agentInfo.zone),
      attributes = attributes.getOrElse(instance.agentInfo.attributes))))

  def withReservation(volumeIds: Seq[LocalVolumeId]): TestInstanceBuilder =
    withReservation(volumeIds, reservationStateNew)

  def withReservation(state: Reservation.State): TestInstanceBuilder =
    withReservation(Seq.empty, state)

  def withReservation(volumeIds: Seq[LocalVolumeId], state: Reservation.State): TestInstanceBuilder =
    withReservation(Reservation(volumeIds, state))

  def withReservation(reservation: Reservation): TestInstanceBuilder =
    copy(instance = instance.copy(reservation = Some(reservation)))

  def reservationStateNew = Reservation.State.New(timeout = None)

  def stateOpLaunch() = InstanceUpdateOperation.LaunchEphemeral(instance)

  def stateOpUpdate(mesosStatus: mesos.Protos.TaskStatus) =
    InstanceUpdateOperation.MesosUpdate(instance, mesosStatus, now)

  def taskLaunchedOp(): InstanceUpdateOperation.LaunchOnReservation = {
    val taskId = instance.appTask.taskId
    val newTaskId = Task.Id.forResidentTask(taskId)
    InstanceUpdateOperation.LaunchOnReservation(
      instanceId = instance.instanceId,
      oldToNewTaskIds = Map(taskId -> newTaskId),
      timestamp = now,
      runSpecVersion = instance.runSpecVersion,
      statuses = Map(taskId -> Task.Status(
        stagedAt = now, condition = Condition.Running, networkInfo = NetworkInfoPlaceholder())),
      hostPorts = Map.empty,
      agentInfo = AgentInfoPlaceholder())
  }

  def stateOpExpunge() = InstanceUpdateOperation.ForceExpunge(instance.instanceId)

  def stateOpReservationTimeout() = InstanceUpdateOperation.ReservationTimeout(instance.instanceId)
}

object TestInstanceBuilder {

  def emptyInstance(now: Timestamp = Timestamp.now(), version: Timestamp = Timestamp.zero,
    instanceId: Instance.Id): Instance = Instance(
    instanceId = instanceId,
    agentInfo = TestInstanceBuilder.defaultAgentInfo,
    state = InstanceState(Condition.Created, now, None, healthy = None),
    tasksMap = Map.empty,
    runSpecVersion = version,
    UnreachableStrategy.default(),
    None
  )

  private val defaultAgentInfo = Instance.AgentInfo(
    host = AgentTestDefaults.defaultHostName,
    agentId = Some(AgentTestDefaults.defaultAgentId), region = None, zone = None, attributes = Seq.empty)

  def newBuilder(runSpecId: PathId, now: Timestamp = Timestamp.now(),
    version: Timestamp = Timestamp.zero): TestInstanceBuilder =
    newBuilderWithInstanceId(Instance.Id.forRunSpec(runSpecId), now, version)

  def newBuilderWithInstanceId(instanceId: Instance.Id, now: Timestamp = Timestamp.now(),
    version: Timestamp = Timestamp.zero): TestInstanceBuilder =
    TestInstanceBuilder(emptyInstance(now, version, instanceId), now)

  def newBuilderWithLaunchedTask(runSpecId: PathId, now: Timestamp = Timestamp.now(),
    version: Timestamp = Timestamp.zero): TestInstanceBuilder =
    newBuilder(runSpecId, now, version).addTaskLaunched()

  @SuppressWarnings(Array("AsInstanceOf"))
  implicit class EnhancedLegacyInstanceImprovement(val instance: Instance) extends AnyVal {
    /** Convenient access to a legacy instance's only task */
    def appTask[T <: Task]: T = new LegacyInstanceImprovement(instance).appTask.asInstanceOf[T]
  }
}
