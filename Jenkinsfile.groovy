#!/usr/bin/env groovy

trait BuildType {
  static void setBuildInfo(displayName, description) {
    currentBuild.displayName = displayName
    currentBuild.description = description
  }
  abstract void buildStarted()
  void installDependencies() {}
  void applyPatches() {}
  void cleanup() {}

  abstract void onFailure(String stageName, Exception err)
  abstract void onSuccess(String stageName)
}

class MainlineBuild implements BuildType {
  void buildStarted() {
    gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    shortCommit = gitCommit.take(8)
    currentBuild.displayName = "#${env.BUILD_NUMBER}: ${shortCommit}"
  }

  void onFailure(String stageName, Exception err) {}
  void onSuccess(String stageName) {}
}

class PhabricatorBuild implements BuildType {
  String parentBranch
  String revisionId
  String diffId
  String phId
  boolean mergeOnSuccess

  // Run the given phabricator method (e.g. arc call-conduit <method>) with
  // the given jq arguments wrapped in a json object.
  // e.g. phabricator("differential.revision.edit", """ transactions: [{type: "comment", "value": "Some comment"}], objectIdentifier: "D1" """)
  def phabricator(method, args) {
    sh "jq -n '{ $args }' | arc call-conduit $method || true"
  }


  // Applies the phabricator diff and posts messages to phabricator
  // that the build is in progress, the revision is rejected and
  // the harbormaster build has the given URL.
// Ephid: the harbormaster phid to update.
// build_url: The build URL of the jenkins build
// revision_id: the revision id being built, e.g. D123
// diff_id: The diff id to apply (e.g. 2458)
  def phabricator_apply_diff(phid, build_url, revision_id, diff_id) {
    phabricator("harbormaster.createartifact", """buildTargetPHID: "$phid", artifactType: "uri", artifactKey: "$build_url", artifactData: { uri: "$build_url", name: "Velocity Results", "ui.external": true }""")
    phabricator("differential.revision.edit", """transactions: [{type: "request-review", value: true}], objectIdentifier: "D$revision_id" """)
    phabricator("harbormaster.sendmessage", """ buildTargetPHID: "$phid", type: "work" """)
    sh "arc patch --diff $diff_id"
  }

  PhabricatorBuild(String parentBranch, String revisionId, String diffId, String phId, boolean mergeOnSuccess) {
    this.parentBranch = parentBranch
    this.revisionId = revisionId
    this.diffId = diffId
    this.phId = phId
    this.mergeOnSuccess = mergeOnSuccess
  }
  void buildStarted() {
    if (mergeOnSuccess && parentBranch != null) {
      buildUrl = "<a href='https://phabricator.mesosphere.com/D$revisionId'>D$revisionId</a>\n\n"
      revisionInfo = sh(returnStdout: true, script: """jq -n '{ "revision_id": "$revisionId" }' | arc call-conduit differential.getcommitmessage | jq '.response' """)

      description = "$buildUrl$revisionInfo"

      setBuildInfo("Merge D$diffId($revisionId) onto $parentBranch - #${env.BUILD_NUMBER}", description)
    } else if (parentBranch != null) {
      setBuildInfo("Try merge D$diffId($revisionId) onto $parentBranch -  #${env.BUILD_NUMBER}", description)
    } else {
      setBuildInfo("D$diffId($revisionId) - #${env.BUILD_NUMBER}", description)
    }
  }

  void applyPatches() {

  }

  void onFailure(String stageName, Exception err) {}
  void onSuccess(String stageName) {}
}


def builder

def createBuilder(String parentBranch, String revisionId, String diffId, String phId, String prBranch, boolean mergeOnSuccess) {
  return new PhabricatorBuild(parentBranch, revisionId, diffId, phId, mergeOnSuccess)
}

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-1-2017-03-21') {
    builder = createBuilder("$PARENT_BRANCH", "$REVISION_ID", "$DIFF_ID", "$PHID", "$BRANCH", MERGE_ON_SUCCESS)

    stage("Checkout Repo") {
      git changelog: false, credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', poll: false, url: 'git@github.com:mesosphere/marathon.git'

      builder.buildStarted()
    }


  }
}