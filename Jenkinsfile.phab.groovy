def setJUnitPrefix(prefix, files) {
  // add prefix to qualified classname
  sh "shopt -s globstar && sed -i \"s/\\(<testcase .*classname=['\\\"]\\)\\([a-z]\\)/\\1${prefix.toUpperCase()}.\\2/g\" $files"
}

def phabricator(method, args) {
  sh "jq -n '{ $args }' | arc call-conduit $method || true"
}

def phabricator_test_results(status) {
  sh """shopt -s globstar && jq '{buildTargetPHID: "$PHID", type: "$status", unit: [.[] * .[]] }' target/phabricator-test-reports' | arc call-conduit harbormaster.sendmessage || true"""
}

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-1-2017-02-23') {
    currentBuild.displayName = "D$REVISION_ID ($DIFF_ID $BUILD_NUMBER)"
    currentBuild.description = "<a href=\"https://phabricator.mesosphere.com/D$REVISION_ID\">D$REVISION_ID</a>"
    try {
      stage("Checkout") {
        git changelog: false, credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', poll: false, url: 'git@github.com:mesosphere/marathon.git'
        sh "sudo git clean -fdx"
        // JQ is broken in the image
        sh "curl -L https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 > /tmp/jq && sudo mv /tmp/jq /usr/bin/jq && sudo chmod +x /usr/bin/jq"
        phabricator("harbormaster.createartifact", """buildTargetPHID: "$PHID", artifactType: "uri", artifactKey: "$BUILD_URL", artifactData: { uri: "$BUILD_URL", name: "Velocity Results", "ui.external": true }""")
        phabricator("differential.revision.edit", """transactions: [{type: "reject", value: true}], objectIdentifier: "D$REVISION_ID" """)
        phabricator("harbormaster.sendmessage", """ buildTargetPHID: "$PHID", type: "work" """)
      }
      stage("Install mesos") {
        sh """if grep -q MesosDebian \$WORKSPACE/project/Dependencies.scala; then
                      MESOS_VERSION=\$(sed -n 's/^.*MesosDebian = "\\(.*\\)"/\\1/p' <\$WORKSPACE/project/Dependencies.scala)
                    else
                       MESOS_VERSION=\$(sed -n 's/^.*mesos=\\(.*\\)&&.*/\\1/p' <\$WORKSPACE/Dockerfile)
                    fi
                    sudo apt-get install -y --force-yes --no-install-recommends mesos=\$MESOS_VERSION
                """
      }
      stage("Apply $DIFF_ID - $REVISION_ID") {
        sh "arc patch --diff $DIFF_ID"
      }
      stage("Kill junk processes") {
        sh "bin/kill-stale-test-processes"
      }
      stage("Compile") {
        withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
          sh "sudo -E sbt -Dsbt.log.format=false clean scapegoat doc coverage test:compile"
          sh """if git diff --quiet; then echo 'No format issues detected'; else echo 'Patch has Format Issues'; exit 1; fi"""
        }
      }
      stage("Test") {
        try {
          timeout(time: 30, unit: 'MINUTES') {
            withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
              sh """sudo -E sbt -Dsbt.log.format=false coverage test coverageReport"""
            }
          }
        } finally {
          sh "python bin/convert_test_coverage.py target/scala-2.11/coverage-report/cobertura.xml Test > target/phabricator-test-reports/test-coverage.json"
          junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'
          currentBuild.description += "<h3>Test Coverage</h3>"
          currentBuild.description += "cat target/scoverage-report/index.html".execute().text
          //publishHtml([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'target/scoverage-report', reportFiles: 'index.html', reportName: "Test Coverage"])
        }
      }
      stage("Integration Test") {
        try {
          timeout(time: 30, unit: 'MINUTES') {
            withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
              sh "sudo -E sbt -Dsbt.log.format=false clean coverage integration:test coverageReport mesos-simulation/integration:test"
            }
          }
        } finally {
          sh "python bin/convert_test_coverage.py target/scala-2.11/coverage-report/cobertura.xml Test > target/phabricator-test-reports/integration-test-coverage.json"
          junit allowEmptyResults: true, testResults: 'target/test-reports/*integration/**/*.xml'
          currentBuild.description += "<h3>Integration Test Coverage</h3>"
          currentBuild.description += "cat target/scoverage-report/index.html".execute().text
          //publishHtml([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'target/scoverage-report', reportFiles: 'index.html', reportName: "Integration Test Coverage"])
        }
      }

      stage("Unstable Test") {
        if ("git grep \"@UnstableTest\"".execute().text != "0") {
          try {
            timeout(time: 60, unit: 'MINUTES') {
              withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
                sh "sudo -E sbt -Dsbt.log.format=false clean coverage unstable:test unstable-integration:test coverageReport"
              }
            }
            setJUnitPrefix("Unstable", "target/test-reports/unstable-integration/**/*.xml")
            setJUnitPrefix("Unstable", "target/test-reports/unstable/**/*.xml")
            sh "python bin/convert_test_coverage.py target/scala-2.11/coverage-report/cobertura.xml Test > target/phabricator-test-reports/unstable-test-coverage.json"
            junit allowEmptyResults: true, testResults: 'target/test-reports/unstable-integration/**/*.xml'
            junit allowEmptyResults: true, testResults: 'target/test-reports/unstable/**/*.xml'
            currentBuild.description += "<h3>Unstable Coverage</h3>"
            currentBuild.description += "cat target/scoverage-report/index.html".execute().text
            //publishHtml([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'target/scoverage-report-unstable', reportFiles: 'index.html', reportName: "Unstable Test Coverage"])
          } catch (Exception err) {
            phabricator("differential.revision.edit", """ transactions: [{type: "comment", value: "Warning $BUILD_URL has failing unstable tests!"}], objectIdentifier: "D$REVISION_ID" """)
          }
        } else {
          echo "No Unstable Tests \u2713"
        }
      }
    } catch (Exception err) {
      phabricator("differential.revision.edit", """ transactions: [{type: "reject", value: true}, {type: "comment", value: "Build Failed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)\
      phabricator_test_results("fail")
      throw err
    }
    phabricator("differential.revision.edit", """ transactions: [{type: "accept", value: true}, {type: comment, value: "Build Succeeded at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
    // TODO: Need to include unit tests and coverage
    phabricator_test_results("pass")
  }
}
