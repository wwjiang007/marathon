""" """
from shakedown import *
from shakedown import http

from utils import *
from dcos import mesos
from dcos.errors import DCOSException
from distutils.version import LooseVersion
from urllib.parse import urljoin

import uuid
import random
import retrying
import pytest
import shakedown
import shlex


marathon_1_3 = pytest.mark.skipif('marthon_version_less_than("1.3")')
marathon_1_4 = pytest.mark.skipif('marthon_version_less_than("1.4")')
marathon_1_5 = pytest.mark.skipif('marthon_version_less_than("1.5")')


def app(id=1, instances=1):
    app_json = {
      "id": "",
      "instances":  1,
      "cmd": "sleep 100000000",
      "cpus": 0.01,
      "mem": 1,
      "disk": 0
    }
    if not str(id).startswith("/"):
        id = "/" + str(id)
    app_json['id'] = id
    app_json['instances'] = instances

    return app_json


def app_mesos(app_id=None):
    if app_id is None:
        app_id = uuid.uuid4().hex

    return {
        'id': app_id,
        'cmd': 'sleep 1000',
        'cpus': 0.5,
        'mem': 32.0,
        'container': {
            'type': 'MESOS'
        }
    }


def constraints(name, operator, value=None):
    constraints = [name, operator]
    if value is not None:
        constraints.append(value)
    return [constraints]


def pod_constraints(name, operator, value=None):
    constraints = {
        'fieldName': name,
        'operator': operator,
        'value': value
    }

    return constraints


def unique_host_constraint():
    return constraints('hostname', 'UNIQUE')


def group():

    return {
        "apps": [],
        "dependencies": [],
        "groups": [
            {
                "apps": [
                    {
                        "cmd": "sleep 1000",
                        "cpus": 0.01,
                        "dependencies": [],
                        "disk": 0.0,
                        "id": "/test-group/sleep/goodnight",
                        "instances": 1,
                        "mem": 32.0
                    },
                    {
                        "cmd": "sleep 1000",
                        "cpus": 0.01,
                        "dependencies": [],
                        "disk": 0.0,
                        "id": "/test-group/sleep/goodnight2",
                        "instances": 1,
                        "mem": 32.0
                    }
                ],
                "dependencies": [],
                "groups": [],
                "id": "/test-group/sleep",
            }
        ],
        "id": "/test-group"
    }


def python_http_app():
    return {
        'id': 'python-http',
        'cmd': '/opt/mesosphere/bin/python -m http.server $PORT0',
        'cpus': 0.5,
        'mem': 128,
        'disk': 0,
        'instances': 1
        }


def nginx_with_ssl_support():
    return {
        "id": "/web-server",
  "instances": 1,
  "cpus": 1,
  "mem": 128,
  "container": {
   "type": "DOCKER",
   "docker": {
    "image": "mesosphere/simple-docker:with-ssl",
    "network": "BRIDGE",
    "portMappings": [
     {
      "containerPort": 80,
      "hostPort": 0,
      "protocol": "tcp",
      "name": "http"
     },
     {
      "containerPort": 443,
      "hostPort": 0,
      "protocol": "tcp",
      "name": "https"
     }
    ]
   }
  }
 }


def fake_framework_app():
    return {
        "id": "/python-http",
        "cmd": "/opt/mesosphere/bin/python -m http.server $PORT0",
        "cpus": 0.5,
        "mem": 128,
        "disk": 0,
        "instances": 1,
        "readinessChecks": [
        {
            "name": "readiness",
            "protocol": "HTTP",
            "path": "/",
            "portName": "api",
            "intervalSeconds": 2,
            "timeoutSeconds": 1,
            "httpStatusCodesForReady": [200]
        }],
        "healthChecks": [
        {
            "gracePeriodSeconds": 10,
            "intervalSeconds": 2,
            "maxConsecutiveFailures": 0,
            "path": "/",
            "portIndex": 0,
            "protocol": "HTTP",
            "timeoutSeconds": 2
        }],
        "labels": {
            "DCOS_PACKAGE_FRAMEWORK_NAME": "pyfw",
            "DCOS_MIGRATION_API_VERSION": "v1",
            "DCOS_MIGRATION_API_PATH": "/v1/plan",
            "MARATHON_SINGLE_INSTANCE_APP": "true",
            "DCOS_SERVICE_NAME": "pyfw",
            "DCOS_SERVICE_PORT_INDEX": "0",
            "DCOS_SERVICE_SCHEME": "http"
        },
        "upgradeStrategy": {
            "minimumHealthCapacity": 0,
            "maximumOverCapacity": 0
        },
        "portDefinitions": [
        {
            "protocol": "tcp",
            "port": 0,
            "name": "api"
        }]
    }


def persistent_volume_app():
    return {
    "id": uuid.uuid4().hex,
    "cmd": "env && echo 'hello' >> $MESOS_SANDBOX/data/foo && /opt/mesosphere/bin/python -m http.server $PORT_API",
    "cpus": 0.5,
    "mem": 32,
    "disk": 0,
    "instances": 1,
    "acceptedResourceRoles": [
        "*"
    ],
    "container": {
        "type": "MESOS",
        "volumes": [
            {
                "containerPath": "data",
                "mode": "RW",
                "persistent": {
                    "size": 10,
                    "type": "root",
                    "constraints": []
                }
            }
        ]
    },
    "portDefinitions": [
        {
            "port": 0,
            "protocol": "tcp",
            "name": "api",
            "labels": {}
        }
    ],
    "upgradeStrategy": {
    "minimumHealthCapacity": 0.5,
    "maximumOverCapacity": 0
    }
}


def readiness_and_health_app():
    return {
        "id": "/python-http",
        "cmd": "/opt/mesosphere/bin/python -m http.server $PORT0",
        "cpus": 0.5,
        "mem": 128,
        "disk": 0,
        "instances": 1,
        "readinessChecks": [
        {
            "name": "readiness",
            "protocol": "HTTP",
            "path": "/",
            "portName": "api",
            "intervalSeconds": 2,
            "timeoutSeconds": 1,
            "httpStatusCodesForReady": [200]
        }],
        "healthChecks": [
        {
            "gracePeriodSeconds": 10,
            "intervalSeconds": 2,
            "maxConsecutiveFailures": 0,
            "path": "/",
            "portIndex": 0,
            "protocol": "HTTP",
            "timeoutSeconds": 2
        }],
        "upgradeStrategy": {
            "minimumHealthCapacity": 0,
            "maximumOverCapacity": 0
        },
        "portDefinitions": [
        {
            "protocol": "tcp",
            "port": 0,
            "name": "api"
        }]
    }


def peristent_volume_app():
    return {
          "id": uuid.uuid4().hex,
          "cmd": "env; echo 'hello' >> $MESOS_SANDBOX/data/foo; /opt/mesosphere/bin/python -m http.server $PORT_API",
          "cpus": 0.5,
          "mem": 32,
          "disk": 0,
          "instances": 1,
          "acceptedResourceRoles": [
            "*"
          ],
          "container": {
            "type": "MESOS",
            "volumes": [
              {
                "containerPath": "data",
                "mode": "RW",
                "persistent": {
                  "size": 10,
                  "type": "root",
                  "constraints": []
                }
              }
            ]
          },
          "portDefinitions": [
            {
              "port": 0,
              "protocol": "tcp",
              "name": "api",
              "labels": {}
            }
          ],
          "upgradeStrategy": {
            "minimumHealthCapacity": 0.5,
            "maximumOverCapacity": 0
          }
        }


def assert_http_code(url, http_code='200'):
    cmd = r'curl -s -o /dev/null -w "%{http_code}"'
    cmd = cmd + ' {}'.format(url)
    status, output = shakedown.run_command_on_master(cmd)

    assert status
    assert output == http_code


def add_role_constraint_to_app_def(app_def, roles=['*']):
    """ Roles are a comma delimited list.  acceptable roles include:
        '*'
        'slave_public'
        '*, slave_public'
    """
    app_def['acceptedResourceRoles'] = roles
    return app_def


def pending_deployment_due_to_resource_roles(app_id):
    resource_role = str(random.getrandbits(32))

    return {
      "id": app_id,
      "cpus": 0.01,
      "instances": 1,
      "mem": 32,
      "cmd": "sleep 12345",
      "acceptedResourceRoles": [
        resource_role
      ]
    }


def pending_deployment_due_to_cpu_requirement(app_id):
    return {
      "id": app_id,
      "instances": 1,
      "mem": 128,
      "cpus": 65536,
      "cmd": "sleep 12345"
    }


def pin_to_host(app_def, host):
    app_def['constraints'] = constraints('hostname', 'LIKE', host)


def pin_pod_to_host(app_def, host):
    app_def['scheduling']['placement']['constraints'].append(pod_constraints('hostname', 'LIKE', host))


def health_check(path='/', protocol='HTTP', port_index=0, failures=1, timeout=2):

    return {
          'protocol': protocol,
          'path': path,
          'timeoutSeconds': timeout,
          'intervalSeconds': 2,
          'maxConsecutiveFailures': failures,
          'portIndex': port_index
        }


def external_volume_mesos_app(volume_name=None):
    if volume_name is None:
        volume_name = 'marathon-si-test-vol-{}'.format(uuid.uuid4().hex)

    return {
      "id": "/external-volume-app",
      "instances": 1,
      "cpus": 0.1,
      "mem": 32,
      "cmd": "env && echo 'hello' >> /test-rexray-volume && /opt/mesosphere/bin/python -m http.server $PORT_API",
      "container": {
        "type": "MESOS",
        "volumes": [
          {
            "containerPath": "test-rexray-volume",
            "external": {
              "size": 1,
              "name": volume_name,
              "provider": "dvdi",
              "options": {"dvdi/driver": "rexray"}
              },
            "mode": "RW"
          }
        ]
      },
      "portDefinitions": [
        {
          "port": 0,
          "protocol": "tcp",
          "name": "api"
        }
      ],
      "healthChecks": [
        {
          "portIndex": 0,
          "protocol": "MESOS_HTTP",
          "path": "/"
        }
      ],
      "upgradeStrategy": {
        "minimumHealthCapacity": 0,
        "maximumOverCapacity": 0
      }
    }


def command_health_check(command='true', failures=1, timeout=2):

    return {
        'protocol': 'COMMAND',
        'command': {'value': command},
        'timeoutSeconds': timeout,
        'intervalSeconds': 2,
        'maxConsecutiveFailures': failures
    }


def private_docker_container_app(docker_credentials_filename='docker.tar.gz'):
    return {
        "id": "/private-docker-app",
        "instances": 1,
        "cpus": 1,
        "mem": 128,
        "container": {
        "type": 'DOCKER',
        "docker": {
            "image": "mesosphere/simple-docker-ee:latest",
            }
        },
        "fetch": [
            {
            "uri": "file:///home/core/{}".format(docker_credentials_filename)
            }
        ]
    }


def private_mesos_container_app(secret_name, app_id=None):
    """ Returns an application definition that uses Mesos containerizer and
        expects a valid Docker config.json referenced by the given `secret_name`.

        :param secret_name: secret name which value is a Docker config.json
        :param app_id: optional application ID, if not given, a random UUID is used
        :return: application definition represented using Python data structures
    """
    if app_id is None:
        app_id = '/{}'.format(uuid.uuid4().hex)

    return {
        "id": app_id,
        "instances": 1,
        "cpus": 1,
        "mem": 128,
        "container": {
            "type": 'MESOS',
            "docker": {
                "image": "mesosphere/simple-docker-ee:latest",
                "config": {
                    "secret": "pullConfigSecret"
                }
            }
        },
        "secrets": {
            "pullConfigSecret": {
                "source": '/{}'.format(secret_name)
            }
        }
    }


def private_docker_pod(secret_name, pod_id=None):
    """ Returns a pod definition that uses a Docker image and
        expects a valid Docker config.json referenced by the given `secret_name`.

        :param secret_name: secret name which value is a Docker config.json
        :param pod_id: optional pod ID, if not given, a random UUID is used
        :return: pod definition represented using Python data structures
    """
    if pod_id is None:
        pod_id = '/{}'.format(uuid.uuid4().hex)

    return {
        "id": pod_id,
        "scaling": {
            "kind": "fixed",
            "instances": 1
        },
        "containers": [{
            "name": "simple-docker",
            "resources": {
                "cpus": 1,
                "mem": 128
            },
            "image": {
                "kind": "DOCKER",
                "id": "mesosphere/simple-docker-ee:latest",
                "config": {
                    "secret": "pullConfigSecret"
                }
            }
        }],
        "networks": [{"mode": "host"}],
        "secrets": {
            "pullConfigSecret": {
                "source": '/{}'.format(secret_name)
            }
        }
    }


def pinger_localhost_app(id='pinger', port=7777):
    """ pinger app requires, the pinger.py app in fixure_dir and the master
        http service started at port 7777

        This app also defaults to 7777 for easy service locating
    """
    return {
      "id": id,
      "instances": 1,
      "cpus": 0.1,
      "mem": 128,
      "cmd": "/opt/mesosphere/bin/python pinger.py {}".format(port),
      "fetch": [
        {
          "uri": "http://master.mesos:7777/pinger.py"
        }
      ],
      "portDefinitions": [
        {
          "port": port,
          "protocol": "tcp",
          "name": "api"
        }
      ],
      "requirePorts": True
    }


def pinger_bridge_app(id='pinger', port=7777):

    return {
      "id": id,
      "instances": 1,
      "container": {
        "type": "DOCKER",
        "docker": {
          "image": "python:3.5-alpine",
          "network": "BRIDGE",
          "portMappings": [
            {
                "containerPort": 80,
                "hostPort": port,
                "protocol": "tcp",
                "name": "http"
            }
            ],
            "requirePorts": True
        },
        "volumes": [
           {
             "containerPath": "/opt/pinger.py",
             "hostPath": "pinger.py",
             "mode": "RO"
           }
         ]
      },
      "cpus": 0.1,
      "mem": 128,
      "cmd": "python3 /opt/pinger.py 80",
      "fetch": [
        {
          "uri": "http://master.mesos:7777/pinger.py"
        }
      ]
    }


def cluster_info(mom_name='marathon-user'):
    agents = get_private_agents()
    print("agents: {}".format(len(agents)))
    client = marathon.create_client()
    about = client.get_about()
    print("marathon version: {}".format(about.get("version")))
    # see if there is a MoM

    if service_available_predicate(mom_name):
        with shakedown.marathon_on_marathon(mom_name):
            try:
                client = marathon.create_client()
                about = client.get_about()
                print("marathon MoM version: {}".format(about.get("version")))

            except Exception as e:
                print("Marathon MoM not present")
    else:
        print("Marathon MoM not present")


def delete_all_apps():
    client = marathon.create_client()
    apps = client.get_apps()
    for app in apps:
        if app['id'] == '/marathon-user':
            print('WARNING: marathon-user installed')
        else:
            client.remove_app(app['id'], True)


def stop_all_deployments(noisy=False):
    client = marathon.create_client()
    deployments = client.get_deployments()
    for deployment in deployments:
        try:
            client.stop_deployment(deployment['id'], True)
        except Exception as e:
            if noisy:
                print(e)


def delete_all_apps_wait():
    delete_all_apps()
    deployment_wait(timedelta(minutes=5).total_seconds())


def ip_other_than_mom():
    mom_ip = ip_of_mom()

    agents = get_private_agents()
    for agent in agents:
        if agent != mom_ip:
            return agent

    return None


@pytest.fixture(scope="function")
def event_fixture():
    run_command_on_master('rm test.txt')
    run_command_on_master('curl --compressed -H "Cache-Control: no-cache" -H "Accept: text/event-stream" -o test.txt leader.mesos:8080/v2/events &')
    yield
    kill_process_on_host(master_ip(), '[c]url')
    run_command_on_master('rm test.txt')


def ip_of_mom():
    service_ips = get_service_ips('marathon', 'marathon-user')
    for mom_ip in service_ips:
        return mom_ip


def ensure_mom():
    if not is_mom_installed():
        # if there is an active deployment... wait for it.
        # it is possible that mom is currently in the process of being uninstalled
        # in which case it will not report as installed however install will fail
        # until the deployment is finished.
        deployment_wait()

        try:
            install_package_and_wait('marathon')
            deployment_wait()
        except:
            pass

        if not wait_for_service_endpoint('marathon-user'):
            print('ERROR: Timeout waiting for endpoint')


def is_mom_installed():
    return package_installed('marathon')


def restart_master_node():
    """ Restarts the master node
    """

    run_command_on_master("sudo /sbin/shutdown -r now")


def systemctl_master(command='restart'):
    run_command_on_master('sudo systemctl {} dcos-mesos-master'.format(command))


def save_iptables(host):
    run_command_on_agent(host, 'if [ ! -e iptables.rules ] ; then sudo iptables -L > /dev/null && sudo iptables-save > iptables.rules ; fi')


def restore_iptables(host):
    run_command_on_agent(host, 'if [ -e iptables.rules ]; then sudo iptables-restore < iptables.rules && rm iptables.rules ; fi')


def block_port(host, port, direction='INPUT'):
    run_command_on_agent(host, 'sudo iptables -I {} -p tcp --dport {} -j DROP'.format(direction, port))


def wait_for_task(service, task, timeout_sec=120):
    """Waits for a task which was launched to be launched"""

    now = time.time()
    future = now + timeout_sec

    while now < future:
        response = None
        try:
            response = get_service_task(service, task)
        except Exception as e:
            pass

        if response is not None and response['state'] == 'TASK_RUNNING':
            return response
        else:
            time.sleep(5)
            now = time.time()

    return None


def clear_pods():
    # clearing doesn't cause
    try:
        client = marathon.create_client()
        pods = client.list_pod()
        for pod in pods:
            client.remove_pod(pod["id"], True)
        shakedown.deployment_wait()
    except:
        pass


def get_pod_tasks(pod_id):
    pod_id = pod_id.lstrip('/')
    pod_tasks = []
    tasks = get_marathon_tasks()
    for task in tasks:
        if task['discovery']['name'] == pod_id:
            pod_tasks.append(task)

    return pod_tasks


def marathon_version():
    client = marathon.create_client()
    about = client.get_about()
    # 1.3.9 or 1.4.0-RC8
    return LooseVersion(about.get("version"))


def marthon_version_less_than(version):
    return marathon_version() < LooseVersion(version)


dcos_1_10 = pytest.mark.skipif('dcos_version_less_than("1.10")')
dcos_1_9 = pytest.mark.skipif('dcos_version_less_than("1.9")')
dcos_1_8 = pytest.mark.skipif('dcos_version_less_than("1.8")')
dcos_1_7 = pytest.mark.skipif('dcos_version_less_than("1.7")')


def dcos_canonical_version():
    version = dcos_version().replace('-dev', '')
    return LooseVersion(version)


def dcos_version_less_than(version):
    return dcos_canonical_version() < LooseVersion(version)


def assert_app_tasks_running(client, app_def):
    app_id = app_def['id']
    instances = app_def['instances']

    app = client.get_app(app_id)
    assert app['tasksRunning'] == instances


def assert_app_tasks_healthy(client, app_def):
    app_id = app_def['id']
    instances = app_def['instances']

    app = client.get_app(app_id)
    assert app['tasksHealthy'] == instances


def get_marathon_leader_not_on_master_leader_node():
    marathon_leader = shakedown.marathon_leader_ip()
    master_leader = shakedown.master_leader_ip()
    print('marathon: {}'.format(marathon_leader))
    print('leader: {}'.format(master_leader))

    if marathon_leader == master_leader:
        # switch
        delete_marathon_path('v2/leader')
        shakedown.wait_for_service_endpoint('marathon', timedelta(minutes=5).total_seconds())
        new_leader = shakedown.marathon_leader_ip()
        assert new_leader != marathon_leader
        marathon_leader = new_leader
        print('switched leader to: {}'.format(marathon_leader))

    return marathon_leader


def docker_env_set():
    return 'DOCKER_HUB_USERNAME' not in os.environ and 'DOCKER_HUB_PASSWORD' not in os.environ


#############
#  moving to shakedown  START
#############


def install_enterprise_cli_package():
    """ Install `dcos-enterprise-cli` package. It is required by the `dcos security`
        command to create secrets, manage service accounts etc.
    """
    print('Installing dcos-enterprise-cli package')
    stdout, stderr, return_code = run_dcos_command('package install dcos-enterprise-cli')
    assert return_code == 0, "Failed to install dcos-enterprise-cli package"


def is_enterprise_cli_package_installed():
    """ Returns `True` if `dcos-enterprise-cli` package is installed.
    """
    stdout, stderr, return_code = run_dcos_command('package list --json')
    result_json = json.loads(stdout)
    return any(cmd['name'] == 'dcos-enterprise-cli' for cmd in result_json)


def create_docker_pull_config_json(username, password):
    """ Create a Docker config.json represented using Python data structures.

        :param username: username for a private Docker registry
        :param password: password for a private Docker registry
        :return: Docker config.json
    """
    print('Creating a config.json content for dockerhub username {}'.format(username))

    import base64
    auth_hash = base64.b64encode('{}:{}'.format(username, password).encode()).decode()

    return {
        "auths": {
            "https://index.docker.io/v1/": {
                "auth": auth_hash
            }
        }
    }


def create_docker_credentials_file(username, password, file_name='docker.tar.gz'):
    """ Create a docker credentials file. Docker username and password are used to create
        a `{file_name}` with `.docker/config.json` containing the credentials.

        :param file_name: credentials file name `docker.tar.gz` by default
        :type command: str
    """

    print('Creating a tarball {} with json credentials for dockerhub username {}'.format(file_name, username))
    config_json_filename = 'config.json'

    config_json = create_docker_pull_config_json(username, password)

    # Write config.json to file
    with open(config_json_filename, 'w') as f:
        json.dump(config_json, f, indent=4)

    try:
        # Create a docker.tar.gz
        import tarfile
        with tarfile.open(file_name, 'w:gz') as tar:
            tar.add(config_json_filename, arcname='.docker/config.json')
            tar.close()
    except Exception as e:
        print('Failed to create a docker credentils file {}'.format(e))
        raise e
    finally:
        os.remove(config_json_filename)


def copy_docker_credentials_file(agents, file_name='docker.tar.gz'):
    """ Create and copy docker credentials file to passed `{agents}`. Used to access private
        docker repositories in tests. File is removed at the end.

        :param agents: list of agent IPs to copy the file to
        :type agents: list
    """

    assert os.path.isfile(file_name), "Failed to upload credentials: file {} not found".format(file_name)

    # Upload docker.tar.gz to all private agents
    try:
        print('Uploading tarball with docker credentials to all private agents...')
        for agent in agents:
            print("Copying docker credentials to {}".format(agent))
            copy_file_to_agent(agent, file_name)
    except Exception as e:
        print('Failed to upload {} to agent: {}'.format(file_name, agent))
        raise e
    finally:
        os.remove(file_name)


def has_secret(secret_name):
    """ Returns `True` if the secret with given name exists in the vault.
        This method uses `dcos security secrets` command and assumes that `dcos-enterprise-cli`
        package is installed.

        :param secret_name: secret name
        :type secret_name: str
    """
    stdout, stderr, return_code = run_dcos_command('security secrets list / --json')
    if stdout:
        result_json = json.loads(stdout)
        return secret_name in result_json
    return False


def delete_secret(secret_name):
    """ Delete a secret with a given name from the vault.
        This method uses `dcos security org` command and assumes that `dcos-enterprise-cli`
        package is installed.

       :param secret_name: secret name
       :type secret_name: str
    """
    print('Removing existing secret {}'.format(secret_name))
    stdout, stderr, return_code = run_dcos_command('security secrets delete {}'.format(secret_name))
    assert return_code == 0, "Failed to remove existing secret"


def create_secret(name, value=None, description=None):
    """ Create a secret with a passed `{name}` and optional `{value}`.
        This method uses `dcos security secrets` command and assumes that `dcos-enterprise-cli`
        package is installed.

        :param name: secret name
        :type name: str
        :param value: optional secret value
        :type value: str
        :param description: option secret description
        :type description: str
    """
    print('Creating new secret {}:{}'.format(name, value))

    value_opt = '-v {}'.format(shlex.quote(value)) if value else ''
    description_opt = '-d "{}"'.format(description) if description else ''

    stdout, stderr, return_code = run_dcos_command('security secrets create {} {} "{}"'.format(
        value_opt,
        description_opt,
        name), print_output=True)
    assert return_code == 0, "Failed to create a secret"


def create_sa_secret(secret_name, service_account, strict=False, private_key_filename='private-key.pem'):
    """ Create an sa-secret with a given private key file for passed service account in the vault. Both
        (service account and secret) should share the same key pair. `{strict}` parameter should be
        `True` when creating a secret in a `strict` secure cluster. Private key file will be removed
        after secret is successfully created.
        This method uses `dcos security org` command and assumes that `dcos-enterprise-cli`
        package is installed.

       :param secret_name: secret name
       :type secret_name: str
       :param service_account: service account name
       :type service_account: str
       :param strict: `True` is this a `strict` secure cluster
       :type strict: bool
       :param private_key_filename: private key file name
       :type private_key_filename: str
    """
    assert os.path.isfile(private_key_filename), "Failed to create secret: private key not found"

    print('Creating new sa-secret {} for service-account: {}'.format(secret_name, service_account))
    strict_opt = '--strict' if strict else ''
    stdout, stderr, return_code = run_dcos_command('security secrets create-sa-secret {} {} {} {}'.format(
        strict_opt,
        private_key_filename,
        service_account,
        secret_name))

    os.remove(private_key_filename)
    assert return_code == 0, "Failed to create a secret"


def has_service_account(service_account):
    """ Returns `True` if a service account with a given name already exists.
        This method uses `dcos security org` command and assumes that `dcos-enterprise-cli`
        package is installed.

       :param service_account: service account name
       :type service_account: str
    """
    stdout, stderr, return_code = run_dcos_command('security org service-accounts show --json')
    result_json = json.loads(stdout)
    return service_account in result_json


def delete_service_account(service_account):
    """ Removes an existing service account. This method uses `dcos security org`
        command and assumes that `dcos-enterprise-cli` package is installed.

        :param service_account: service account name
        :type service_account: str
    """
    print('Removing existing service account {}'.format(service_account))
    stdout, stderr, return_code = run_dcos_command('security org service-accounts delete {}'.format(service_account))
    assert return_code == 0, "Failed to create a service account"


def create_service_account(
        service_account,
        private_key_filename='private-key.pem',
        public_key_filename='public-key.pem',
        account_description='SI test account'):
    """ Create new private and public key pair and use them to add a new service
        with a give name. Public key file is then removed, however private key file
        is left since it might be used to create a secret. If you don't plan on creating
        a secret afterwards, please remove it manually.
        This method uses `dcos security org` command and assumes that `dcos-enterprise-cli`
        package is installed.

        :param service_account: service account name
        :type service_account: str
        :param private_key_filename: optional private key file name
        :type private_key_filename: str
        :param public_key_filename: optional public key file name
        :type public_key_filename: str
        :param account_description: service account description
        :type account_description: str
    """
    print('Creating a key pair for the service account')
    stdout, stderr, return_code = run_dcos_command('security org service-accounts keypair {} {}'.format(private_key_filename, public_key_filename))
    assert os.path.isfile(private_key_filename), "Private key of the service account key pair not found"
    assert os.path.isfile(public_key_filename), "Public key of the service account key pair not found"

    print('Creating {} service account'.format(service_account))
    stdout, stderr, return_code = run_dcos_command('security org service-accounts create -p {} -d "{}" {}'.format(
        public_key_filename,
        account_description,
        service_account))

    os.remove(public_key_filename)

    assert return_code == 0


def set_service_account_permissions(service_account, ressource='dcos:superuser', action='full'):
    """ Set permissions for given `{service_account}` for passed `{ressource}` with
        `{action}`. For more information consult the DC/OS documentation:
        https://docs.mesosphere.com/1.9/administration/id-and-access-mgt/permissions/user-service-perms/

    """
    print('Granting {} permissions to {}/users/{}'.format(action, ressource, service_account))
    url = urljoin(dcos_url(), 'acs/api/v1/acls/{}/users/{}/{}'.format(ressource, service_account, action))
    req = http.put(url)
    assert req.status_code == 204, 'Failed to grant permissions to the service account: {}, {}'.format(req, req.text)


def get_marathon_endpoint(path, marathon_name='marathon'):
    """Returns the url for the marathon endpoint
    """
    return shakedown.dcos_url_path('service/{}/{}'.format(marathon_name, path))


def http_get_marathon_path(name, marathon_name='marathon'):
    """ Invokes HTTP GET for marathon url with name
        ex.  name='ping'  http GET {dcos_url}/service/marathon/ping
    """
    url = get_marathon_endpoint(name, marathon_name)
    headers = {'Accept': '*/*'}
    return http.get(url, headers=headers)


# PR added to dcos-cli (however it takes weeks)
# https://github.com/dcos/dcos-cli/pull/974
def delete_marathon_path(name, marathon_name='marathon'):
    """ Invokes HTTP DELETE for marathon url with name
        ex.  name='v2/leader'  http GET {dcos_url}/service/marathon/v2/leader
    """
    url = get_marathon_endpoint(name, marathon_name)
    return http.delete(url)


def multi_master():
    """ Returns True if this is a multi master cluster. This is useful in
    using pytest skipif when testing single master clusters such as:
    `pytest.mark.skipif('multi_master')` which will skip the test if
    the number of masters is > 1.
    """
    # reverse logic (skip if multi master cluster)
    return len(get_all_masters()) > 1


def __get_all_agents():
    """Provides all agent json in the cluster which can be used for filtering"""

    client = mesos.DCOSClient()
    agents = client.get_state_summary()['slaves']
    return agents


def agent_hostname_by_id(agent_id):
    """Given a agent_id provides the agent ip"""
    for agent in __get_all_agents():
        if agent['id'] == agent_id:
            return agent['hostname']

    return None


#############
# moving to shakedown  END
#############
