pipeline {
    agent any
    parameters {
        string(name: 'APP_GIT_URL', defaultValue: '', description: 'GitHub URL of the ACE Application')
        string(name: 'APP_BRANCH', defaultValue: 'main', description: 'Branch to checkout')
    }
    stages {
        stage('1. Checkout ACE App Repo') {
            steps {
                dir('app-src') {
                    deleteDir()
                    git url: "${params.APP_GIT_URL}", branch: "${params.APP_BRANCH}", credentialsId: 'github-creds'
                }
            }
        }

        stage('2. Read App Configuration') {
            steps {
                script {
                    def config = readJSON file: "app-src/pipeline-config.json"

                    env.APP_NAME = config.appName
                    env.IMAGE_NAME = config.appName.toLowerCase()
                    env.ENVIRONMENT = config.environment ?: 'dev'
                    env.BAR_FILE = "${config.appName}.bar"

                    env.REQUIRES_MQ = config.dependencies.mq
                    env.REQUIRES_REDIS = config.dependencies.redis
                    env.REQUIRES_CREDS = config.requiresDbParms
                }
            }
        }

        stage('3. Configure MQ/DB Passwords') {
            when { expression { env.REQUIRES_CREDS == 'true' } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'DB_CRED', passwordVariable: 'DB_PWD', usernameVariable: 'DB_USER')]) {
                    script {
                        sh "echo 'odbc::myDB ${DB_USER} ${DB_PWD}' > setdbparms.txt"
                        sh "echo 'redis::myRedis serverName::${DB_PWD}' >> setdbparms.txt"
                    }
                }
            }
        }

        stage('4. Build Target Container') {
            steps {
                script {
                    sh "docker build -f Dockerfile.ace-generic --build-arg BAR_FILE=${env.BAR_FILE} -t app-ace-${env.IMAGE_NAME}:latest ."
                }
            }
        }

        stage('5. Run Standardized ACE Container') {
            steps {
                script {
                    def portRegistry = load 'vars/portRegistry.groovy'
                    def environment = env.ENVIRONMENT ?: 'dev'
                    def ports = portRegistry.allocatePort(env.IMAGE_NAME, environment, env.BUILD_NUMBER)

                    env.CONTAINER_HOST_PORT = ports.hostFlowPort.toString()
                    env.CONTAINER_ADMIN_PORT = ports.hostAdminPort.toString()
                    env.PORT_KEY = ports.key
                    env.CONTAINER_NAME = "ace-${env.IMAGE_NAME}-${env.BUILD_NUMBER}"

                    echo "Starting ACE container: ${env.CONTAINER_NAME}"
                    echo "Environment: ${environment}"
                    echo "Allocated ports: Flow=${env.CONTAINER_HOST_PORT}:7800, Admin=${env.CONTAINER_ADMIN_PORT}:7600"

                    def runCmd = "docker run -d --name ${env.CONTAINER_NAME} "
                    runCmd += "-p ${env.CONTAINER_ADMIN_PORT}:7600 "
                    runCmd += "-p ${env.CONTAINER_HOST_PORT}:7800 "
                    runCmd += "-l app=${env.IMAGE_NAME} "
                    runCmd += "-l build=${env.BUILD_NUMBER} "
                    runCmd += "-l jenkins_job=${env.JOB_NAME} "

                    if (env.REQUIRES_MQ == 'true') { runCmd += "--network mq-net " }
                    if (env.REQUIRES_REDIS == 'true') { runCmd += "--network redis-net " }
                    if (env.REQUIRES_CREDS == 'true') {
                        runCmd += "-v ${WORKSPACE}/setdbparms.txt:/home/aceuser/initial-config/setdbparms/setdbparms.txt:ro "
                    }
                    runCmd += "app-ace-${env.IMAGE_NAME}:latest"

                    sh "docker rm -f ${env.CONTAINER_NAME} || true"
                    sh "${runCmd}"
                    sh "docker ps --filter 'name=${env.CONTAINER_NAME}' | grep ${env.CONTAINER_NAME} || exit 1"
                }
            }
        }
    }
}
