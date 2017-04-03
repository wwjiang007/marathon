def setJUnitPrefix(prefix, files) {
  // add prefix to qualified classname
  sh "bash -c 'shopt -s globstar && sed -i \"s/\\(<testcase .*classname=['\\\"]\\)\\([a-z]\\)/\\1${prefix.toUpperCase()}.\\2/g\" $files'"
  return this
}

def phabricator(method, args) {
  sh "jq -n '{ $args }' | arc call-conduit $method || true"
  return this
}

def phabricator_test_results(status) {
  sh """jq '{buildTargetPHID: "$PHID", type: "$status", unit: [.[] * .[]] }' target/phabricator-test-reports/* | arc call-conduit harbormaster.sendmessage """
  return this
}

def convert_test_coverage() {
  sh """sudo sh -c "python bin/convert_test_coverage.py target/scala-2.11/coverage-report/cobertura.xml Test > target/phabricator-test-reports/test-coverage.json" """
  return this
}

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-1-2017-02-23') {
    currentBuild.displayName = "D$REVISION_ID ($DIFF_ID $BUILD_NUMBER)"
    currentBuild.description = "<a href=\"https://phabricator.mesosphere.com/D$REVISION_ID\">D$REVISION_ID</a>"
    try {
      stage("Install Dependencies") {
        // JQ is broken in the image
        sh "curl -L https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 > /tmp/jq && sudo mv /tmp/jq /usr/bin/jq && sudo chmod +x /usr/bin/jq"
        sh """mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y && mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y"""
        sh """sudo curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/0.8.2/2.12-0.8.2 && sudo chmod +x /usr/local/bin/amm"""
      }
      stage("Checkout") {
        git changelog: false, credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', poll: false, url: 'git@github.com:mesosphere/marathon.git'
        sh "sudo git clean -fdx"
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
          //sh "sudo -E sbt -Dsbt.log.format=false clean scapegoat doc coverage test:compile"
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
          sh "ls bin/"
          sh "pwd"
          convert_test_coverage()
          junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'
          currentBuild.description += "<h3>Test Coverage</h3>"
          currentBuild.description += readFile("target/scala-2.11/scoverage-report/index.hml")
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
          convert_test_coverage()
          junit allowEmptyResults: true, testResults: 'target/test-reports/*integration/**/*.xml'
          currentBuild.description += "<h3>Integration Test Coverage</h3>"
          currentBuild.description += readFile("target/scala-2.11/scoverage-report/index.hml")
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
            convert_test_coverage()
            junit allowEmptyResults: true, testResults: 'target/test-reports/unstable-integration/**/*.xml'
            junit allowEmptyResults: true, testResults: 'target/test-reports/unstable/**/*.xml'
            currentBuild.description += "<h3>Unstable Coverage</h3>"
            currentBuild.description += readFile("target/scala-2.11/scoverage-report/index.hml")
            //publishHtml([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'target/scoverage-report-unstable', reportFiles: 'index.html', reportName: "Unstable Test Coverage"])
          } catch (Exception err) {
            phabricator("differential.revision.edit", """ transactions: [{type: "comment", value: "Warning $BUILD_URL has failing unstable tests!"}], objectIdentifier: "D$REVISION_ID" """)
          }
        } else {
          echo "No Unstable Tests \u2713"
        }
      }
      stage("Publish Results") {
        phabricator_test_results("fail")
        phabricator("differential.revision.edit", """ transactions: [{type: "accept", value: true}, {type: "comment", value: "Build Succeeded at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
      }
    } catch (Exception err) {
      stage("Publish Results") {
        phabricator_test_results("fail")
        phabricator("differential.revision.edit", """ transactions: [{type: "reject", value: true}, {type: "comment", value: "Build Failed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
        currentBuild.result = "FAILURE"
      }
    }
  }
}
