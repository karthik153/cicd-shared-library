pipeline {
    agent any
    parameters {
        string(name: 'APP_GIT_URL', defaultValue: '', description: 'GitHub URL of the ACE Application')
        string(name: 'APP_BRANCH', defaultValue: 'main', description: 'Branch to checkout')
    }
    stages {
        stage('1. Checkout ACE App Repo') {
            steps {
                cleanWs()
                dir('app-src') {
                    // Uses 'github-creds' to authenticate with Github
                    git url: "${params.APP_GIT_URL}", branch: "${params.APP_BRANCH}", credentialsId: 'github-creds'
                }
            }
        }
        stage('2. Read App Configuration') {
            steps {
                script {
                    def config = readJSON file: "app-src/pipeline-config.json"
                    env.APP_NAME = config.appName
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
                        // Writes db credentials into setdbparms.txt securely
                        sh "echo 'odbc::myDB ${DB_USER} ${DB_PWD}' > setdbparms.txt"
                        sh "echo 'redis::myRedis serverName::${DB_PWD}' >> setdbparms.txt" 
                    }
                }
            }
        }
        stage('4. Build Target Container') {
            steps {
                script {
                    sh "docker build -f Dockerfile.ace-generic --build-arg APP_NAME=${env.APP_NAME} -t app-ace-${env.APP_NAME}:latest ."
                }
            }
        }
        stage('5. Run Standardized ACE Container') {
            steps {
                script {
                    def runCmd = "docker run -d --name ${env.APP_NAME} "
                    
                    if (env.REQUIRES_MQ == 'true') { runCmd += "--network mq-net " }
                    if (env.REQUIRES_REDIS == 'true') { runCmd += "--network redis-net " }
                    if (env.REQUIRES_CREDS == 'true') { 
                        runCmd += "-v ${WORKSPACE}/setdbparms.txt:/home/aceuser/initial-config/setdbparms/setdbparms.txt:ro "
                    }
                    runCmd += "app-ace-${env.APP_NAME}:latest"
                    
                    // Stop any older versions and deploy the newly built version!
                    sh "docker rm -f ${env.APP_NAME} || true"
                    sh "${runCmd}"
                }
            }
        }
    }
}
