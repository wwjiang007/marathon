/* BEGIN: Block of stuff that we can't have in the library for this job until the patch itself lands - chicken and egg: all in marathon.groovy */

def setBuildInfo(displayName, description) {
  currentBuild.displayName = displayName
  currentBuild.description = description
  return this
}

def install_dependencies() {
  // JQ is broken in the image
  sh "curl -L https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 > /tmp/jq && sudo mv /tmp/jq /usr/bin/jq && sudo chmod +x /usr/bin/jq"
  // install ammonite (scala shell)
  sh """mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y && mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y"""
  sh """sudo curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/0.8.2/2.12-0.8.2 && sudo chmod +x /usr/local/bin/amm"""
  return this
}

def checkout_marathon_master() {
  git changelog: false, credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', poll: false, url: 'git@github.com:mesosphere/marathon.git'
  sh "sudo git clean -fdx"
  return this
}

def phabricator_apply_diff(phid, build_url, revision_id, diff_id) {
  phabricator("harbormaster.createartifact", """buildTargetPHID: "$phid", artifactType: "uri", artifactKey: "$build_url", artifactData: { uri: "$build_url", name: "Velocity Results", "ui.external": true }""")
  phabricator("differential.revision.edit", """transactions: [{type: "reject", value: true}], objectIdentifier: "D$revision_id" """)
  phabricator("harbormaster.sendmessage", """ buildTargetPHID: "$phid", type: "work" """)
  sh "arc patch --diff $diff_id"
}

def phabricator(method, args) {
  sh "jq -n '{ $args }' | arc call-conduit $method || true"
  return this
}

/* END: Block of stuff that can be removed after the patch lands */
if (env.APPLIED_DIFF == "true") {
  ansiColor('gnome-terminal') {
    node('JenkinsMarathonCI-Debian8-1-2017-02-23') {
      if (fileExists('marathon.groovy')) {
        load('marathon.groovy')
      }
      setBuildInfo("D$REVISION_ID($DIFF_ID) #$BUILD_NUMBER", "<a href=\"https://phabricator.mesosphere.com/D$REVISION_ID\">D$REVISION_ID</a>")

      stage("Install Dependencies") {
        install_dependencies()
      }
      stage("Checkout D$REVISION_ID($DIFF_ID)") {
        checkout_marathon_master()
        phabricator_apply_diff("$PHID", "$BUILD_URL", "$REVISION_ID", "$DIFF_ID")
      }
      withEnv(['APPLIED_DIFF=true']) {
        // reload the script.
        jobDsl targets: 'Jenkinsfile_phab2.groovy'
      }
    }
  }
} else {
  if (fileExists('marathon.groovy')) {
    load('marathon.groovy')
  }

  try {
    stage("Install Mesos") {
      install_mesos()
    }
    stage("Kill junk processes") {
      kill_junk()
    }
    stage("Compile") {
      compile()
    }
    stage("Test") {
      try {
        test()
      } finally {
        phabricator_convert_test_coverage()
        publish_test_coverage("Test Coverage")
      }
    }
    stage("Integration Test") {
      try {
        integration_test()
      } finally {
        phabricator_convert_test_coverage()
        publish_test_coverage("Integration Test Coverage")
      }
    }
    stage("Assemble Binaries") {
      assembly()
    }
    stage("Package Binaries") {
      package_binaries()
    }
    stage("Unstable Test") {
      if (has_unstable_tests()) {
        try {
          unstable_test()
        } catch (Exception err) {
          phabricator("differential.revision.edit", """ transactions: [{type: "comment", value: "Warning $BUILD_URL has failing unstable tests!"}], objectIdentifier: "D$REVISION_ID" """)
        } finally {
          phabricator_convert_test_coverage()
          publish_test_coverage("Unstable Test Coverage")
        }
      } else {
        echo "No Unstable Tests \u2713"
      }
    }
    stage("Publish Results") {
      sh "git branch | grep -v master | xargs git branch -D"
      phabricator_test_results("fail")
      phabricator("differential.revision.edit", """ transactions: [{type: "accept", value: true}, {type: "comment", value: "Build Succeeded at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
    }
  } catch (Exception err) {
    stage("Publish Results") {
      sh "git checkout master && git branch | grep -v master | xargs git branch -D || true"
      phabricator_test_results("fail")
      phabricator("differential.revision.edit", """ transactions: [{type: "reject", value: true}, {type: "comment", value: "Build Failed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
      currentBuild.result = "FAILURE"
    }
  }
}
